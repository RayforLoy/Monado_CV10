// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

class Cv10PocketXrPreviewActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var open: Button
    private lateinit var test: Button
    private lateinit var stop: Button
    private var transport: Cv10PocketXrTransport? = null
    private var renderer: Cv10PocketXrRenderer? = null
    private var pendingPermission: Boolean? = null
    private var stopping = false

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
        stop = Button(this).apply {
            setText(R.string.cv10_pocket_preview_stop)
            setOnClickListener { stopPreview(getString(R.string.cv10_pocket_preview_intro)) }
        }
        panel.addView(status)
        panel.addView(open)
        panel.addView(test)
        panel.addView(stop)
        setContentView(panel)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        updateButtons()
    }

    override fun onStop() {
        if (::status.isInitialized && renderer != null) {
            stopPreview(getString(R.string.cv10_pocket_preview_intro))
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::status.isInitialized) Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun startPreview(showTestCard: Boolean) {
        if (renderer != null || stopping || pendingPermission != null) return
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
        val available = renderer == null && !stopping && pendingPermission == null
        open.isEnabled = available
        test.isEnabled = available
        stop.isEnabled = renderer != null
    }

    private companion object {
        const val TAG = "Cv10PocketXrPreview"
        const val REQUEST_DISPLAY_PERMISSION = 18_011
    }
}
