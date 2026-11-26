// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.freedesktop.monado.auxiliary.Cv10ExternalPresentation
import org.freedesktop.monado.auxiliary.Cv10PocketXrDisplayService
import org.freedesktop.monado.auxiliary.ICv10PocketXrDisplay.MODE_PC_SCREEN_SBS
import org.freedesktop.monado.auxiliary.ICv10PocketXrDisplay.MODE_SPACE
import org.freedesktop.monado.auxiliary.ICv10PocketXrDisplay.MODE_TEST_CARD
import org.freedesktop.monado.auxiliary.ICv10PocketXrDisplay.PC_SCREEN_WIDTH
import rikka.shizuku.Shizuku

class Cv10PocketXrPreviewActivity : Activity(), Cv10ExternalPresentation {
    private lateinit var status: TextView
    private lateinit var open: Button
    private lateinit var pcSetup: Button
    private lateinit var pcScreen: Button
    private lateinit var test: Button
    private lateinit var controls: Button
    private lateinit var openXrApp: Button
    private lateinit var stop: Button
    private lateinit var headLink: CheckBox
    private lateinit var worldAnchor: CheckBox
    private lateinit var recenter: Button
    private var transport: Cv10PocketXrTransport? = null
    private var renderer: Cv10PocketXrRenderer? = null
    private var pendingPermission: Int? = null
    private var stopping = false
    private var pendingVpn = false
    private var headLinkStarted = false
    private var controlsAvailable = false
    private var openingClient = false
    override var keepSessionWhilePaused = false
        private set

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (renderer != null) stopPreview(getString(R.string.cv10_pocket_preview_intro))
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { request, result ->
        if (request == REQUEST_DISPLAY_PERMISSION) {
            val mode = pendingPermission
            pendingPermission = null
            if (result == PackageManager.PERMISSION_GRANTED && mode != null && !isFinishing) {
                startPreview(mode)
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
            setOnClickListener { startPreview(MODE_SPACE) }
        }
        pcSetup = Button(this).apply {
            setText(R.string.cv10_pocket_pc_setup)
            setOnClickListener {
                try {
                    startActivity(Intent().setComponent(Cv10PocketXrDisplayService.POCKET_PC_SCREEN))
                } catch (error: ActivityNotFoundException) {
                    Log.w(TAG, "Pocket XR PC Screen is unavailable", error)
                    status.setText(R.string.cv10_pocket_pc_unavailable)
                }
            }
        }
        pcScreen = Button(this).apply {
            setText(R.string.cv10_pocket_pc_sbs)
            setOnClickListener { startPreview(MODE_PC_SCREEN_SBS) }
        }
        test = Button(this).apply {
            setText(R.string.cv10_pocket_preview_test)
            setOnClickListener { startPreview(MODE_TEST_CARD) }
        }
        controls = Button(this).apply {
            setText(R.string.cv10_pocket_preview_controls)
            setOnClickListener { openPhoneControls() }
        }
        openXrApp = Button(this).apply {
            setText(R.string.cv10_pocket_open_xr_app)
            setOnClickListener { chooseOpenXrApp() }
        }
        stop = Button(this).apply {
            setText(R.string.cv10_pocket_preview_stop)
            setOnClickListener { stopPreview(getString(R.string.cv10_pocket_preview_intro)) }
        }
        headLink = CheckBox(this).apply {
            setText(R.string.cv10_pocket_head_link_opt_in)
            setOnClickListener { if (isChecked) requestHeadLink() }
        }
        worldAnchor = CheckBox(this).apply {
            setText(R.string.cv10_pocket_world_anchor)
            setOnClickListener { if (isChecked) headLink.isChecked = false }
        }
        recenter = Button(this).apply {
            setText(R.string.cv10_pocket_recenter)
            setOnClickListener { renderer?.recenter() }
        }
        panel.addView(status)
        panel.addView(headLink)
        panel.addView(worldAnchor)
        panel.addView(open)
        panel.addView(recenter)
        panel.addView(controls)
        panel.addView(openXrApp)
        panel.addView(pcSetup)
        panel.addView(pcScreen)
        panel.addView(test)
        panel.addView(stop)
        setContentView(ScrollView(this).apply { addView(panel) })
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

    private fun openPhoneControls(): Boolean {
        if (!controlsAvailable || keepSessionWhilePaused) return false
        try {
            val launch = checkNotNull(packageManager.getLaunchIntentForPackage(Cv10PocketHeadLinkService.POCKET_PACKAGE))
            startForegroundService(Cv10PocketControlsService.intent(this))
            keepSessionWhilePaused = true
            startActivity(launch)
            Log.i(TAG, "Opened Pocket XR phone controls with the external session active")
            return true
        } catch (error: RuntimeException) {
            leavePhoneControls()
            Log.w(TAG, "Could not open Pocket XR phone controls", error)
            status.setText(R.string.cv10_pocket_controls_launch_failed)
            return false
        }
    }

    private fun chooseOpenXrApp() {
        val apps = Cv10AppCatalog.load(this).filter(Cv10AppEntry::isXr)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.cv10_pocket_open_xr_app)
            .setNegativeButton(android.R.string.cancel, null)
        if (apps.isEmpty()) {
            dialog.setMessage(R.string.cv10_pocket_no_xr_apps)
        } else {
            dialog.setItems(apps.map { it.label }.toTypedArray()) { _, index -> openClientApp(apps[index]) }
        }
        dialog.show()
    }

    private fun openClientApp(entry: Cv10AppEntry) {
        val session = transport ?: return
        if (!controlsAvailable || openingClient) return
        when (activeBrokerPackage()) {
            Cv10PocketHeadLinkService.POCKET_PACKAGE -> Unit
            POCKET_CLIENT_PACKAGE -> {
                if (!hasMatchingPocketClient()) {
                    status.setText(R.string.cv10_pocket_client_incompatible)
                    return
                }
            }
            else -> {
                status.setText(R.string.cv10_pocket_choose_runtime)
                return
            }
        }
        openingClient = true
        updateButtons()
        session.openClientDisplay { result ->
            if (transport !== session) return@openClientDisplay
            openingClient = false
            updateButtons()
            result.onSuccess { displayId ->
                if (!Cv10AppCatalog.launch(this, entry, displayId)) {
                    session.closeClientDisplay()
                    status.text = getString(R.string.cv10_app_launch_failed, entry.label)
                } else if (!openPhoneControls()) {
                    session.closeClientDisplay()
                } else {
                    Log.i(TAG, "Requested Pocket XR client ${entry.component.flattenToShortString()} on display $displayId")
                }
            }.onFailure { error ->
                Log.w(TAG, "Could not create the Pocket XR client display", error)
                status.setText(R.string.cv10_pocket_client_display_failed)
            }
        }
    }

    private fun hasMatchingPocketClient(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val manager = packageManager
        if (manager.checkSignatures(packageName, POCKET_CLIENT_PACKAGE) != PackageManager.SIGNATURE_MATCH) {
            return false
        }
        val pocketPackage = Cv10PocketHeadLinkService.POCKET_PACKAGE
        val version = try {
            manager.getPackageInfo(pocketPackage, 0).longVersionCode
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        // Official 1.6.28 and 1.6.31 ship byte-identical arm64 Monado services.
        return (version == 214L || version == 217L) && manager.hasSigningCertificate(
            pocketPackage,
            Base64.decode(POCKET_RELEASE_SIGNER, Base64.DEFAULT),
            PackageManager.CERT_INPUT_SHA256,
        )
    }

    private fun activeBrokerPackage(): String? {
        // Match the Android OpenXR loader: check the installable broker before the system one.
        val abi = Build.SUPPORTED_ABIS.first()
        return listOf("runtime_broker", "system_runtime_broker").firstNotNullOfOrNull { broker ->
            val uri = Uri.parse("content://org.khronos.openxr.$broker/openxr/1/abi/$abi/runtimes/active")
            try {
                contentResolver.query(uri, arrayOf("package_name"), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (error: RuntimeException) {
                Log.d(TAG, "Could not read $broker", error)
                null
            }
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
            } else {
                worldAnchor.isChecked = false
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
        if (headLink.isChecked) worldAnchor.isChecked = false
        status.setText(if (headLink.isChecked) R.string.cv10_pocket_preview_intro else R.string.cv10_pocket_head_link_denied)
        updateButtons()
    }

    private fun startPreview(mode: Int) {
        if (renderer != null || stopping || pendingPermission != null || pendingVpn) return
        try {
            if (!Shizuku.pingBinder()) {
                status.setText(R.string.cv10_pocket_preview_needs_shizuku)
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                pendingPermission = mode
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
        nextTransport = Cv10PocketXrTransport(applicationContext, mode = mode, onInterrupted = { error ->
            if (transport === nextTransport) showError(error)
        })
        fun updateStatus() {
            val id = displayId ?: return
            if (transport !== nextTransport) return
            if (hasFrame) {
                status.text = getString(R.string.cv10_pocket_preview_receiving, id)
                Log.i(TAG, "First virtual display frame available on display $id")
                controlsAvailable = mode == MODE_SPACE
                updateButtons()
            } else if (mode == MODE_PC_SCREEN_SBS) {
                status.setText(R.string.cv10_pocket_pc_waiting_for_stream)
            }
        }
        val nextRenderer = Cv10PocketXrRenderer(
            activity = this,
            captureWidth = if (mode == MODE_PC_SCREEN_SBS) PC_SCREEN_WIDTH else null,
            worldAnchored = worldAnchor.isChecked && mode != MODE_PC_SCREEN_SBS,
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
        if (mode == MODE_SPACE && headLink.isChecked) {
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
        openingClient = false
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
        worldAnchor.isEnabled = available
        open.isEnabled = available
        pcSetup.isEnabled = available
        pcScreen.isEnabled = available
        test.isEnabled = available
        controls.isEnabled = controlsAvailable && !openingClient
        openXrApp.visibility = if (controlsAvailable) View.VISIBLE else View.GONE
        openXrApp.isEnabled = controlsAvailable && !openingClient
        recenter.visibility = if (renderer?.worldAnchored == true) View.VISIBLE else View.GONE
        stop.isEnabled = renderer != null
    }

    companion object {
        internal fun resumeIntent(context: Context): Intent =
            Intent(context, Cv10PocketXrPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private const val TAG = "Cv10PocketXrPreview"
        private const val POCKET_CLIENT_PACKAGE = "org.cv10.monado.pocket_client"
        private const val POCKET_RELEASE_SIGNER = "M+rUzfrJVxaWq8sq6kRliRww7R2CVxJ4pKVLhwuik60="
        private const val REQUEST_DISPLAY_PERMISSION = 18_011
        private const val REQUEST_HEAD_LINK = 18_012
    }
}
