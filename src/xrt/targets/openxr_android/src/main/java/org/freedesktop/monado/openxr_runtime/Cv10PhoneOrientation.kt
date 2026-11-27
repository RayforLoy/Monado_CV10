// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.openxr_runtime

import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Resolves phone motion to rightward yaw and upward pitch relative to the last center. */
internal class Cv10PhoneOrientation {
    private val matrix = FloatArray(9)
    private val forward = FloatArray(3)
    private val right = FloatArray(2)
    private val up = FloatArray(3)
    private var axis = 2
    private var sign = -1f
    private var rotation = Surface.ROTATION_0

    var hasSample = false
        private set
    var yaw = 0f
        private set
    var pitch = 0f
        private set

    fun reset() {
        hasSample = false
        yaw = 0f
        pitch = 0f
    }

    /** Returns true when the sample starts a new calibration. */
    fun update(values: FloatArray, displayRotation: Int): Boolean {
        SensorManager.getRotationMatrixFromVector(matrix, values)
        if (!hasSample || rotation != displayRotation) {
            hasSample = true
            rotation = displayRotation
            center()
            return true
        }
        val x = sign * matrix[axis]
        val y = sign * matrix[axis + 3]
        val z = sign * matrix[axis + 6]
        val horizontal = right[0] * x + right[1] * y
        val vertical = up[0] * x + up[1] * y + up[2] * z
        val depth = forward[0] * x + forward[1] * y + forward[2] * z
        yaw = atan2(horizontal, depth)
        pitch = atan2(vertical, hypot(horizontal, depth))
        return false
    }

    fun center() {
        if (!hasSample) return
        val top = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) 0 else 1
        // Use the top edge of a flat phone and the back of an upright phone.
        if (abs(matrix[top + 6]) < abs(matrix[8])) {
            axis = top
            sign = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_180) -1f else 1f
        } else {
            axis = 2
            sign = -1f
        }
        for (i in forward.indices) forward[i] = sign * matrix[3 * i + axis]
        val horizontalLength = hypot(forward[0], forward[1])
        right[0] = forward[1] / horizontalLength
        right[1] = -forward[0] / horizontalLength
        up[0] = right[1] * forward[2]
        up[1] = -right[0] * forward[2]
        up[2] = horizontalLength
        yaw = 0f
        pitch = 0f
    }
}
