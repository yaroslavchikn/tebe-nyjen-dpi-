package com.yourcompany.wifiunblocker

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.*
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.animation.CycleInterpolator
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private lateinit var actionCard: MaterialCardView
    private lateinit var actionButton: ImageView
    private lateinit var statusText: TextView
    private lateinit var statusIndicator: View
    private lateinit var chipGroup: ChipGroup
    private lateinit var chipFragment: Chip
    private lateinit var chipTtl: Chip
    private lateinit var chipHybrid: Chip

    private var isVpnRunning = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pulseAnimator: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        actionCard = findViewById(R.id.actionCard)
        actionButton = findViewById(R.id.actionButton)
        statusText = findViewById(R.id.statusText)
        statusIndicator = findViewById(R.id.statusIndicator)
        chipGroup = findViewById(R.id.modeChipGroup)
        chipFragment = findViewById(R.id.chipFragment)
        chipTtl = findViewById(R.id.chipTtl)
        chipHybrid = findViewById(R.id.chipHybrid)

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when {
                checkedIds.contains(chipFragment.id) -> 1
                checkedIds.contains(chipTtl.id) -> 2
                else -> 3
            }
            VpnService.currentMode = mode
            if (isVpnRunning) {
                Toast.makeText(this, "Режим изменён, перезапустите VPN для применения", Toast.LENGTH_SHORT).show()
            }
        }

        actionCard.setOnClickListener {
            if (isVpnRunning) {
                stopVpn()
            } else {
                startVpn()
            }
        }

        updateUi(false)
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 0)
        } else {
            onVpnPrepared()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 0 && resultCode == RESULT_OK) {
            onVpnPrepared()
        } else {
            Toast.makeText(this, "Разрешение VPN не получено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onVpnPrepared() {
        val vpnIntent = Intent(this, VpnService::class.java)
        startService(vpnIntent)
        isVpnRunning = true
        updateUi(true)
        Toast.makeText(this, getString(R.string.toast_started), Toast.LENGTH_SHORT).show()
    }

    private fun stopVpn() {
        val vpnIntent = Intent(this, VpnService::class.java)
        stopService(vpnIntent)
        isVpnRunning = false
        updateUi(false)
        Toast.makeText(this, getString(R.string.toast_stopped), Toast.LENGTH_SHORT).show()
    }

    private fun updateUi(running: Boolean) {
        if (running) {
            statusText.text = getString(R.string.status_connected)
            statusIndicator.setBackgroundResource(R.drawable.circle_indicator_on)
            actionButton.setImageResource(R.drawable.ic_power_on)
            actionCard.setCardBackgroundColor(resources.getColor(android.R.color.holo_green_dark, theme))
            startPulseAnimation()
        } else {
            statusText.text = getString(R.string.status_disconnected)
            statusIndicator.setBackgroundResource(R.drawable.circle_indicator_off)
            actionButton.setImageResource(R.drawable.ic_power_off)
            actionCard.setCardBackgroundColor(resources.getColor(android.R.color.holo_blue_dark, theme))
            stopPulseAnimation()
        }
    }

    private fun startPulseAnimation() {
        if (pulseAnimator != null && pulseAnimator!!.isRunning) return
        val scaleX = ObjectAnimator.ofFloat(actionCard, "scaleX", 1.0f, 1.08f, 1.0f)
        val scaleY = ObjectAnimator.ofFloat(actionCard, "scaleY", 1.0f, 1.08f, 1.0f)
        val alpha = ObjectAnimator.ofFloat(actionCard, "alpha", 1.0f, 0.85f, 1.0f)
        pulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 1200
            interpolator = CycleInterpolator(0.5f)
            repeatCount = Animator.INFINITE
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        actionCard.scaleX = 1.0f
        actionCard.scaleY = 1.0f
        actionCard.alpha = 1.0f
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        if (isVpnRunning) stopVpn()
    }
}
