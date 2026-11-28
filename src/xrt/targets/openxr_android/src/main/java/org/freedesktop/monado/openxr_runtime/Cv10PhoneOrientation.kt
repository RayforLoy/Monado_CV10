// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.openxr_runtime

import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.cos

internal data class Cv10PhoneRotation(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(b: Cv10PhoneRotation) = Cv10PhoneRotation(
        w*b.x + x*b.w + y*b.z - z*b.y,
        w*b.y - x*b.z + y*b.w + z*b.x,
        w*b.z + x*b.y - y*b.x + z*b.w,
        w*b.w - x*b.x - y*b.y - z*b.z,
    )
}

/** Resolves phone motion to rightward yaw and upward pitch relative to the last center. */
internal class Cv10PhoneOrientation {
    private val matrix = FloatArray(9)
    private val sensorQuaternion = FloatArray(4)
    private var centerAzimuth = 0f
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
        SensorManager.getQuaternionFromVector(sensorQuaternion, values) // Android orders this as WXYZ.
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
        centerAzimuth = atan2(-forward[0], forward[1])
        val horizontalLength = hypot(forward[0], forward[1])
        right[0] = forward[1] / horizontalLength
        right[1] = -forward[0] / horizontalLength
        up[0] = right[1] * forward[2]
        up[1] = -right[0] * forward[2]
        up[2] = horizontalLength
        yaw = 0f
        pitch = 0f
    }

    /** World ENU -> XR (east/up/south); keep the physical screen normal +Z and portrait top +Y. */
    fun grip(heading: Float): Cv10PhoneRotation {
        val halfYaw = (heading - centerAzimuth) * 0.5f
        val worldYaw = Cv10PhoneRotation(0f, sin(halfYaw), 0f, cos(halfYaw))
        val sensor = Cv10PhoneRotation(sensorQuaternion[1], sensorQuaternion[2], sensorQuaternion[3], sensorQuaternion[0])
        return worldYaw * enuToXr * sensor * displayAxes[rotation]
    }

    private companion object {
        const val S = 0.70710678f
        val enuToXr = Cv10PhoneRotation(-S, 0f, 0f, S)
        // Surface.ROTATION_0, 90, 180 and 270: portrait axes expressed in the device's natural axes.
        val displayAxes = arrayOf(Cv10PhoneRotation(0f, 0f, 0f, 1f), Cv10PhoneRotation(0f, 0f, S, S),
            Cv10PhoneRotation(0f, 0f, 1f, 0f), Cv10PhoneRotation(0f, 0f, -S, S))
    }
}
