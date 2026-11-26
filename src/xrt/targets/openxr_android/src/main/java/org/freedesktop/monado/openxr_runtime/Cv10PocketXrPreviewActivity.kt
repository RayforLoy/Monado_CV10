// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.freedesktop.monado.auxiliary.Cv10ExternalPresentation
import rikka.shizuku.Shizuku

class Cv10PocketXrPreviewActivity : Activity(), Cv10ExternalPresentation {
    private lateinit var status: TextView
    private lateinit var open: Button
    private lateinit var test: Button
    private lateinit var controls: Button
    private lateinit var stop: Button
    private lateinit var headLink: CheckBox
    private var transport: Cv10PocketXrTransport? = null
    private var renderer: Cv10PocketXrRenderer? = null
    private var pendingPermission: Boolean? = null
    private var stopping = false
    private var pendingVpn = false
    private var headLinkStarted = false
    private var controlsAvailable = false
    override var keepSessionWhilePaused = false
        private set

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (renderer != null) stopPreview(getString(R.string.cv10_pocket_preview_intro))
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { request, result ->
        if (request == REQUEST_DISPLAY_PERMISSION) {
            val showTestCard = pendingPermission
            pendingPermission = null
            if (result == PackageManager.PERMISSION_GRANTED && showTestCard != null && !isFinishing) {
                startPreview(showTestCard)
            } else {
                status.setText(R.string.cv10_pocket_preview_shizuku_denied)
                updateButtons()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.cv10LauncherPreview || BuildConfig.inProcess) {
            finish()
            return
        }
        setTitle(R.string.cv10_pocket_preview_title)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        status = TextView(this).apply {
            setText(R.string.cv10_pocket_preview_intro)
            setPadding(0, 0, 0, padding)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        open = Button(this).apply {
            setText(R.string.cv10_pocket_preview_open)
            setOnClickListener { startPreview(false) }
        }
        test = Button(this).apply {
            setText(R.string.cv10_pocket_preview_test)
            setOnClickListener { startPreview(true) }
        }
        controls = Button(this).apply {
            setText(R.string.cv10_pocket_preview_controls)
            setOnClickListener { openPhoneControls() }
        }
        stop = Button(this).apply {
            setText(R.string.cv10_pocket_preview_stop)
            setOnClickListener { stopPreview(getString(R.string.cv10_pocket_preview_intro)) }
        }
        headLink = CheckBox(this).apply {
            setText(R.string.cv10_pocket_head_link_opt_in)
            setOnClickListener { if (isChecked) requestHeadLink() }
        }
        panel.addView(status)
        panel.addView(headLink)
        panel.addView(open)
        panel.addView(test)
        panel.addView(controls)
        panel.addView(stop)
        setContentView(panel)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        val stopFilter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Cv10PocketControlsService.STOP_ACTION)
        }
        ContextCompat.registerReceiver(this, stopReceiver, stopFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        updateButtons()
    }

    override fun onResume() {
        super.onResume()
        leavePhoneControls()
    }

    override fun onStop() {
        if (renderer != null && !keepSessionWhilePaused) {
            stopPreview(getString(R.string.cv10_pocket_preview_intro))
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::status.isInitialized) {
            if (renderer != null) stopPreview(getString(R.string.cv10_pocket_preview_intro))
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            unregisterReceiver(stopReceiver)
        }
        super.onDestroy()
    }

    private fun openPhoneControls() {
        if (!controlsAvailable || keepSessionWhilePaused) return
        try {
            val launch = checkNotNull(packageManager.getLaunchIntentForPackage(Cv10PocketHeadLinkService.POCKET_PACKAGE))
            startForegroundService(Cv10PocketControlsService.intent(this))
            keepSessionWhilePaused = true
            startActivity(launch)
            Log.i(TAG, "Opened Pocket XR phone controls with the external session active")
        } catch (error: RuntimeException) {
            leavePhoneControls()
            Log.w(TAG, "Could not open Pocket XR phone controls", error)
            status.setText(R.string.cv10_pocket_controls_launch_failed)
        }
    }

    private fun leavePhoneControls() {
        if (!keepSessionWhilePaused) return
        keepSessionWhilePaused = false
        stopService(Cv10PocketControlsService.intent(this))
    }

    private fun requestHeadLink() {
        try {
            packageManager.getApplicationInfo(Cv10PocketHeadLinkService.POCKET_PACKAGE, 0)
            if (Cv10PocketHeadLinkService.vpnVisible(this)) {
                headLink.isChecked = false
                status.setText(R.string.cv10_pocket_head_link_vpn_conflict)
                return
            }
            val consent = VpnService.prepare(this)
            if (consent != null) {
                pendingVpn = true
                updateButtons()
                startActivityForResult(consent, REQUEST_HEAD_LINK)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not request the Pocket XR head link", error)
            pendingVpn = false
            headLink.isChecked = false
            status.setText(R.string.cv10_pocket_head_link_unavailable)
            updateButtons()
        }
    }

    @Deprecated("Used for Android's VPN consent activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_HEAD_LINK || !::headLink.isInitialized) return
        pendingVpn = false
        headLink.isChecked = resultCode == RESULT_OK
        status.setText(if (headLink.isChecked) R.string.cv10_pocket_preview_intro else R.string.cv10_pocket_head_link_denied)
        updateButtons()
    }

    private fun startPreview(showTestCard: Boolean) {
        if (renderer != null || stopping || pendingPermission != null || pendingVpn) return
        try {
            if (!Shizuku.pingBinder()) {
                status.setText(R.string.cv10_pocket_preview_needs_shizuku)
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                pendingPermission = showTestCard
                updateButtons()
                Shizuku.requestPermission(REQUEST_DISPLAY_PERMISSION)
                return
            }
        } catch (error: RuntimeException) {
            pendingPermission = null
            updateButtons()
            showError(error)
            return
        }

        status.setText(R.string.cv10_pocket_preview_starting)
        var displayId: Int? = null
        var hasFrame = false
        lateinit var nextTransport: Cv10PocketXrTransport
        nextTransport = Cv10PocketXrTransport(applicationContext, showTestCard = showTestCard, onInterrupted = { error ->
            if (transport === nextTransport) showError(error)
        })
        fun updateStatus() {
            val id = displayId ?: return
            if (hasFrame && transport === nextTransport) {
                status.text = getString(R.string.cv10_pocket_preview_receiving, id)
                Log.i(TAG, "First virtual display frame available on display $id")
                controlsAvailable = !showTestCard
                updateButtons()
            }
        }
        val nextRenderer = Cv10PocketXrRenderer(
            activity = this,
            onSurface = { surface ->
                if (transport === nextTransport) {
                    nextTransport.start(surface) { result ->
                        if (transport === nextTransport) {
                            result.onSuccess { id -> displayId = id; updateStatus() }.onFailure(::showError)
                        }
                    }
                }
            },
            onFirstFrame = { hasFrame = true; updateStatus() },
            onFinished = { failure ->
                if (transport === nextTransport) {
                    if (failure == null) {
                        stopPreview(getString(R.string.cv10_pocket_preview_session_ended))
                    } else {
                        showError(IllegalStateException(failure))
                    }
                }
            },
        )
        transport = nextTransport
        renderer = nextRenderer
        if (!showTestCard && headLink.isChecked) {
            try {
                startForegroundService(Cv10PocketHeadLinkService.intent(this))
                headLinkStarted = true
            } catch (error: RuntimeException) {
                showError(error)
                return
            }
        }
        updateButtons()
        nextRenderer.start()
    }

    private fun showError(error: Throwable) {
        Log.w(TAG, "Pocket XR display preview failed", error)
        stopPreview(getString(R.string.cv10_pocket_preview_failure, error.message ?: error.javaClass.simpleName))
    }

    private fun stopPreview(message: String) {
        val previousTransport = transport
        val previousRenderer = renderer
        transport = null
        renderer = null
        controlsAvailable = false
        leavePhoneControls()
        if (headLinkStarted) {
            stopService(Cv10PocketHeadLinkService.intent(this))
            headLinkStarted = false
        }
        status.text = message
        if (previousTransport != null) {
            stopping = true
            previousTransport.close {
                previousRenderer?.stop()
                stopping = false
                updateButtons()
            }
        } else {
            previousRenderer?.stop()
        }
        updateButtons()
    }

    private fun updateButtons() {
        val available = renderer == null && !stopping && pendingPermission == null && !pendingVpn
        headLink.isEnabled = available
        open.isEnabled = available
        test.isEnabled = available
        controls.isEnabled = controlsAvailable
        stop.isEnabled = renderer != null
    }

    private companion object {
        const val TAG = "Cv10PocketXrPreview"
        const val REQUEST_DISPLAY_PERMISSION = 18_011
        const val REQUEST_HEAD_LINK = 18_012
    }
}
