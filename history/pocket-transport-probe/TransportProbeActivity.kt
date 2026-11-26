package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import org.freedesktop.monado.auxiliary.ICv10PocketXrDisplay
import rikka.shizuku.Shizuku

class TransportProbeActivity : Activity() {
    private var transport: Cv10PocketXrTransport? = null
    private var reader: ImageReader? = null
    private var pendingMode: Int? = null
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread {
            val mode = pendingMode
            pendingMode = null
            if (result == PERMISSION_GRANTED && mode != null) open(mode)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fun button(label: String, action: () -> Unit) = addView(Button(context).apply {
                text = label
                setOnClickListener { action() }
            })
            button("Open test card") { open(ICv10PocketXrDisplay.MODE_TEST_CARD) }
            button("Open Space") { open(ICv10PocketXrDisplay.MODE_SPACE) }
            button("Open PC screen") { open(ICv10PocketXrDisplay.MODE_PC_SCREEN_SBS) }
            button("Create client display") {
                transport?.openClientDisplay { Log.i(TAG, "Open client display: $it") }
            }
            button("Close client display") { transport?.closeClientDisplay() }
            button("Stop") { stop() }
        })
    }

    private fun open(mode: Int) {
        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "Shizuku not ready")
            return
        }
        if (Shizuku.checkSelfPermission() != PERMISSION_GRANTED) {
            pendingMode = mode
            Shizuku.requestPermission(1)
            return
        }
        check(transport == null)
        val width = if (mode == ICv10PocketXrDisplay.MODE_PC_SCREEN_SBS) ICv10PocketXrDisplay.PC_SCREEN_WIDTH else 3840
        val sink = ImageReader.newInstance(width, 1080, PixelFormat.RGBA_8888, 2)
        reader = sink
        var frames = 0
        sink.setOnImageAvailableListener({ source ->
            source.acquireLatestImage()?.use { image ->
                if (++frames == 2) Log.i(TAG, "Received ${image.width}x${image.height} output frames")
            }
        }, Handler(Looper.getMainLooper()))
        transport = Cv10PocketXrTransport(this, mode) { Log.e(TAG, "Transport interrupted", it) }.apply {
            start(sink.surface) { result ->
                result.onSuccess { Log.i(TAG, "Opened mode=$mode display=$it") }
                    .onFailure { Log.e(TAG, "Open mode=$mode failed", it) }
            }
        }
    }

    private fun stop() {
        val connection = transport ?: return
        transport = null
        val sink = reader
        reader = null
        connection.close {
            sink?.close()
            Log.i(TAG, "Transport closed")
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        stop()
        super.onDestroy()
    }

    private companion object { const val TAG = "Cv10TransportProbe" }
}
