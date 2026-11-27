// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.annotation.Keep

/** Consumes side-by-side output in the same EGL context that submits OpenXR frames. */
internal class Cv10PocketXrRenderer(
    private val activity: Activity,
    private val captureWidth: Int? = null,
    val worldAnchored: Boolean,
    private val wideCanvas: Boolean,
    private val opticalEyeSize: Int,
    private val direct: Boolean,
    private val onSurface: (Surface, IBinder?) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onFinished: (String?) -> Unit,
) {
    private val state = SpatialLauncherState {}
    private val capture = Capture()
    private var framePoses: Cv10PocketFramePoses? = null

    fun start() {
        Thread({
            val failure = try {
                System.loadLibrary("cv10-spatial-launcher")
                val context = activity.applicationContext
                if (direct) framePoses = Cv10PocketFramePoses.create(context)
                val manifest = Cv10BundledRuntime.manifestPath(context, "cv10-pocket-runtime.json")
                nativeRun(activity, context, state, capture, manifest, worldAnchored, wideCanvas, opticalEyeSize)
            } catch (error: LinkageError) {
                Log.e(TAG, "OpenXR capture library unavailable", error)
                error.message ?: error.toString()
            } finally {
                runCatching { framePoses?.close() }
                    .onFailure { Log.w(TAG, "Cannot close CV10 frame poses", it) }
            }
            activity.runOnUiThread { onFinished(capture.failure ?: failure) }
        }, "Cv10PocketXrRenderer").start()
    }

    fun stop() {
        state.stop = true
    }

    fun recenter() {
        state.recenter()
    }

    @Keep
    private external fun nativeRun(
        activity: Activity,
        context: Context,
        state: SpatialLauncherState,
        capture: Capture,
        runtimeManifest: String?,
        worldAnchored: Boolean,
        wideCanvas: Boolean,
        opticalEyeSize: Int,
    ): String?

    @Keep
    private inner class Capture {
        private var texture: SurfaceTexture? = null
        private var surface: Surface? = null
        private var lastTimestamp = 0L
        private var posesMatched = 0
        private var posesMissing = 0
        var failure: String? = null
            private set

        fun startCapture(textureName: Int, width: Int, height: Int): Boolean {
            try {
                val current = SurfaceTexture(textureName)
                texture = current
                current.setDefaultBufferSize(captureWidth ?: width, height)
                val output = Surface(current)
                surface = output
                val poseSource = framePoses?.source
                activity.runOnUiThread { if (!state.stop && output.isValid) onSurface(output, poseSource) }
                return true
            } catch (error: RuntimeException) {
                Log.e(TAG, "Cannot create a Pocket XR capture surface", error)
                return false
            }
        }

        fun updateCapture(transform: FloatArray): Long {
            val current = texture ?: return 0L
            val timestamp: Long
            try {
                current.updateTexImage()
                timestamp = current.timestamp
                if (timestamp == 0L || timestamp == lastTimestamp) return timestamp
                current.getTransformMatrix(transform)
            } catch (error: RuntimeException) {
                if (!state.stop) {
                    Log.e(TAG, "Cannot read the Pocket XR capture surface", error)
                    failure = error.message ?: error.toString()
                    state.stop = true
                }
                return 0L
            }
            if (lastTimestamp == 0L) {
                activity.runOnUiThread { if (!state.stop) onFirstFrame() }
            }
            lastTimestamp = timestamp
            framePoses?.let { channel ->
                val pose = channel.match(timestamp)
                if (pose == null) posesMissing++ else posesMatched++
                val tag = Cv10PocketFramePoses.TAG
                if (Log.isLoggable(tag, Log.DEBUG)) {
                    Log.d(tag, if (pose == null) "CV10_RX_POSE stamp=$timestamp missing" else
                        "CV10_RX_POSE stamp=${pose.stamp} pose=${pose.predictionTime} epoch=${pose.recenter} " +
                        "x=${pose.center.x} y=${pose.center.y} z=${pose.center.z} w=${pose.center.w} " +
                        "rowPoses=${pose.top != pose.bottom}")
                }
            }
            return timestamp
        }

        fun stopCapture() {
            if (framePoses != null) {
                Log.i(Cv10PocketFramePoses.TAG, "CV10 capture poses matched=$posesMatched missing=$posesMissing")
            }
            lastTimestamp = 0L
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
