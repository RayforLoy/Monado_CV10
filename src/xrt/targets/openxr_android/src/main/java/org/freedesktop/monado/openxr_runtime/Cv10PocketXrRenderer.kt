// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.annotation.Keep

/** Consumes a side-by-side virtual display in the same EGL context that submits OpenXR frames. */
internal class Cv10PocketXrRenderer(
    private val activity: Activity,
    private val onSurface: (Surface) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onFinished: (String?) -> Unit,
) {
    private val state = SpatialLauncherState {}
    private val capture = Capture()

    fun start() {
        Thread({
            val failure = try {
                System.loadLibrary("cv10-spatial-launcher")
                val context = activity.applicationContext
                val manifest = Cv10BundledRuntime.manifestPath(context, "cv10-pocket-runtime.json")
                nativeRun(activity, context, state, capture, manifest)
            } catch (error: LinkageError) {
                Log.e(TAG, "OpenXR capture library unavailable", error)
                error.message ?: error.toString()
            }
            activity.runOnUiThread { onFinished(capture.failure ?: failure) }
        }, "Cv10PocketXrRenderer").start()
    }

    fun stop() {
        state.stop = true
    }

    @Keep
    private external fun nativeRun(
        activity: Activity,
        context: Context,
        state: SpatialLauncherState,
        capture: Capture,
        runtimeManifest: String?,
    ): String?

    @Keep
    private inner class Capture {
        private var texture: SurfaceTexture? = null
        private var surface: Surface? = null
        private var hasFrame = false
        var failure: String? = null
            private set

        fun startCapture(textureName: Int, width: Int, height: Int): Boolean {
            try {
                val current = SurfaceTexture(textureName)
                texture = current
                current.setDefaultBufferSize(width, height)
                val output = Surface(current)
                surface = output
                activity.runOnUiThread { if (!state.stop && output.isValid) onSurface(output) }
                return true
            } catch (error: RuntimeException) {
                Log.e(TAG, "Cannot create a virtual display surface", error)
                return false
            }
        }

        fun updateCapture(transform: FloatArray): Boolean {
            val current = texture ?: return false
            try {
                current.updateTexImage()
                if (current.timestamp == 0L) return false
                current.getTransformMatrix(transform)
            } catch (error: RuntimeException) {
                if (!state.stop) {
                    Log.e(TAG, "Cannot read the virtual display surface", error)
                    failure = error.message ?: error.toString()
                    state.stop = true
                }
                return false
            }
            if (!hasFrame) {
                hasFrame = true
                activity.runOnUiThread { if (!state.stop) onFirstFrame() }
            }
            return true
        }

        fun stopCapture() {
            surface?.release()
            surface = null
            texture?.release()
            texture = null
        }
    }

    private companion object {
        const val TAG = "Cv10PocketXrRenderer"
    }
}
