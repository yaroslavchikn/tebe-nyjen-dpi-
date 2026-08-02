package com.yourcompany.wifiunblocker

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class VpnService : VpnService() {
    companion object {
        private const val TAG = "VpnService"
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_MASK = 0
        private const val MTU = 1500
        private const val BUFFER_SIZE = MTU + 50

        const val MODE_FRAGMENT = 1
        const val MODE_TTL = 2
        const val MODE_HYBRID = 3
        var currentMode = MODE_HYBRID
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectionCache = ConcurrentHashMap<String, ByteArray>()

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "VPN Service started")
        startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        if (isRunning.get()) return

        try {
            val builder = Builder()
            builder.setAddress(VPN_ADDRESS, 32)
            builder.addRoute(VPN_ROUTE, VPN_MASK)
            builder.setMtu(MTU)
            builder.setSession("WiFi Unblocker (DPI bypass)")
            builder.setBlocking(true)
            builder.allowFamily(android.net.NetworkCapabilities.TRANSPORT_WIFI)

            vpnInterface = builder.establish()
            Log.d(TAG, "VPN established, FD: ${vpnInterface?.fd}")

            isRunning.set(true)

            job = scope.launch {
                processPackets()
            }

            ForegroundService.startForegroundService(this, "Обход DPI активен (режим: ${if (currentMode == MODE_FRAGMENT) "фрагментация" else if (currentMode == MODE_TTL) "TTL" else "гибрид"})")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning.set(false)
        job?.cancel()
        job = null
        vpnInterface?.close()
        vpnInterface = null
        connectionCache.clear()
        ForegroundService.stopForegroundService(this)
        Log.d(TAG, "VPN stopped")
    }

    private suspend fun processPackets() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(fd)
        val outputStream = FileOutputStream(fd)

        val readBuffer = ByteArray(BUFFER_SIZE)
        val writeBuffer = ByteArray(BUFFER_SIZE)

        val outgoingSocket = DatagramSocket()
        outgoingSocket.soTimeout = 0

        val incomingSocket = DatagramSocket()
        incomingSocket.soTimeout = 100

        scope.launch {
            val buffer = ByteArray(BUFFER_SIZE)
            while (isRunning.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    incomingSocket.receive(packet)
                    outputStream.write(packet.data, 0, packet.length)
                    outputStream.flush()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        while (isRunning.get()) {
            try {
                val len = inputStream.read(readBuffer)
                if (len <= 0) continue

                val packetData = readBuffer.copyOf(len)
                val processedPackets = processOutgoingPacket(packetData)

                for (processed in processedPackets) {
                    val destIp = extractDestIp(processed)
                    val destPort = extractDestPort(processed)
                    if (destIp != null && destPort != -1) {
                        val destAddress = InetSocketAddress(destIp, destPort)
                        val packet = DatagramPacket(processed, processed.size, destAddress)
                        outgoingSocket.send(packet)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in outgoing loop", e)
            }
        }

        outgoingSocket.close()
        incomingSocket.close()
    }

    private fun extractDestIp(packet: ByteArray): InetAddress? {
        if (packet.size < 20) return null
        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) return null
        val destIpBytes = packet.sliceArray(16..19)
        return InetAddress.getByAddress(destIpBytes)
    }

    private fun extractDestPort(packet: ByteArray): Int {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (packet.size < ihl + 4) return -1
        val protocol = packet[9].toInt() and 0xFF
        if (protocol == 6 || protocol == 17) {
            val portOffset = ihl + 2
            return ((packet[portOffset].toInt() and 0xFF) shl 8) or (packet[portOffset + 1].toInt() and 0xFF)
        }
        return -1
    }

    private fun processOutgoingPacket(packet: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        if (packet.size < 20) return listOf(packet)

        val ihl = (packet[0].toInt() and 0x0F) * 4
        val totalLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (packet.size < totalLen) return listOf(packet)

        val protocol = packet[9].toInt() and 0xFF

        if (protocol == 6) {
            val tcpHeaderOffset = ihl
            if (packet.size < tcpHeaderOffset + 20) return listOf(packet)
            val srcPort = ((packet[tcpHeaderOffset].toInt() and 0xFF) shl 8) or (packet[tcpHeaderOffset + 1].toInt() and 0xFF)
            val dstPort = ((packet[tcpHeaderOffset + 2].toInt() and 0xFF) shl 8) or (packet[tcpHeaderOffset + 3].toInt() and 0xFF)
            val dataOffset = ((packet[tcpHeaderOffset + 12].toInt() and 0xF0) shr 4) * 4
            val tcpDataStart = tcpHeaderOffset + dataOffset

            if (packet.size > tcpDataStart) {
                val payload = packet.sliceArray(tcpDataStart until packet.size)
                if (payload.size >= 5 && payload[0] == 0x16.toByte() && payload[1] == 0x03.toByte()) {
                    when (currentMode) {
                        MODE_FRAGMENT -> {
                            val fragments = fragmentTlsClientHello(packet, tcpHeaderOffset, dataOffset, payload)
                            result.addAll(fragments)
                            return result
                        }
                        MODE_TTL -> {
                            val modifiedPacket = modifyTtl(packet)
                            result.add(modifiedPacket)
                            return result
                        }
                        MODE_HYBRID -> {
                            val fragments = fragmentTlsClientHello(packet, tcpHeaderOffset, dataOffset, payload)
                            for (frag in fragments) {
                                val withTtl = modifyTtl(frag)
                                result.add(withTtl)
                            }
                            return result
                        }
                    }
                }
            }
        }

        if (protocol == 17) {
            if (currentMode == MODE_TTL || currentMode == MODE_HYBRID) {
                val modified = modifyTtl(packet)
                result.add(modified)
                return result
            }
        }

        result.add(packet)
        return result
    }

    private fun fragmentTlsClientHello(originalPacket: ByteArray, tcpOffset: Int, dataOffset: Int, payload: ByteArray): List<ByteArray> {
        val fragments = mutableListOf<ByteArray>()
        val splitPoint = if (payload.size > 100) 100 else payload.size / 2
        if (splitPoint <= 0) return listOf(originalPacket)

        val firstFragment = ByteArray(tcpOffset + dataOffset + splitPoint)
        System.arraycopy(originalPacket, 0, firstFragment, 0, tcpOffset + dataOffset + splitPoint)
        System.arraycopy(payload, 0, firstFragment, tcpOffset + dataOffset, splitPoint)
        updateIpLength(firstFragment, firstFragment.size)

        val remaining = payload.size - splitPoint
        val secondFragment = ByteArray(tcpOffset + dataOffset + remaining)
        System.arraycopy(originalPacket, 0, secondFragment, 0, tcpOffset + dataOffset)
        System.arraycopy(payload, splitPoint, secondFragment, tcpOffset + dataOffset, remaining)
        updateIpLength(secondFragment, secondFragment.size)

        fragments.add(firstFragment)
        fragments.add(secondFragment)
        return fragments
    }

    private fun modifyTtl(packet: ByteArray): ByteArray {
        val modified = packet.copyOf()
        if (modified.size < 20) return modified
        modified[8] = 8
        updateIpChecksum(modified)
        return modified
    }

    private fun updateIpLength(packet: ByteArray, newLen: Int) {
        if (packet.size < 20) return
        packet[2] = ((newLen shr 8) and 0xFF).toByte()
        packet[3] = (newLen and 0xFF).toByte()
    }

    private fun updateIpChecksum(packet: ByteArray) {
        if (packet.size < 20) return
        packet[10] = 0
        packet[11] = 0
        val ihl = (packet[0].toInt() and 0x0F) * 4
        var sum = 0
        for (i in 0 until ihl step 2) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            if (sum > 0xFFFF) sum = (sum and 0xFFFF) + 1
        }
        val checksum = (sum.inv() and 0xFFFF)
        packet[10] = (checksum shr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()
    }
}
