// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.openxr_runtime

import android.view.Surface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Regression cases for physical phone grip vs the independently centered ray. Run with Android SensorManager. */
fun main() {
    fun axis(q: Cv10PhoneRotation, x: Float, y: Float, z: Float): FloatArray {
        val v = q * Cv10PhoneRotation(x, y, z, 0f) * Cv10PhoneRotation(-q.x, -q.y, -q.z, q.w)
        return floatArrayOf(v.x, v.y, v.z)
    }
    fun expect(actual: FloatArray, x: Float, y: Float, z: Float) {
        check(actual.zip(floatArrayOf(x, y, z).asIterable()).all { (v, e) -> abs(v - e) < 0.0001f })
    }
    val o = Cv10PhoneOrientation()
    val s = sqrt(0.5f)
    val flat = floatArrayOf(0f, 0f, 0f, 1f)
    check(o.update(flat, Surface.ROTATION_0))
    expect(axis(o.grip(0f), 0f, 0f, 1f), 0f, 1f, 0f)
    expect(axis(o.grip(0f), 0f, 1f, 0f), 0f, 0f, -1f)
    val halfRoll = (Math.PI / 8).toFloat()
    o.update(floatArrayOf(0f, sin(halfRoll), 0f, cos(halfRoll)), Surface.ROTATION_0)
    expect(axis(o.grip(0f), 0f, 0f, 1f), s, s, 0f)
    check(abs(o.yaw) < 0.0001f && abs(o.pitch) < 0.0001f)
    o.center()
    expect(axis(o.grip((Math.PI / 2).toFloat()), 0f, 0f, 1f), 0f, s, -s)

    o.reset()
    check(o.update(floatArrayOf(s, 0f, 0f, s), Surface.ROTATION_0))
    expect(axis(o.grip(0f), 0f, 0f, 1f), 0f, 0f, 1f)
    expect(axis(o.grip(0f), 0f, 1f, 0f), 0f, 1f, 0f)
    check(abs(o.yaw) < 0.0001f && abs(o.pitch) < 0.0001f)

    o.reset()
    check(o.update(flat, Surface.ROTATION_90))
    expect(axis(o.grip(0f), 0f, 0f, 1f), 0f, 1f, 0f)
    expect(axis(o.grip(0f), 0f, 1f, 0f), 0f, 0f, -1f)
    println("PASS phone flat/upright, roll with a steady ray, horizontal recenter, rotated display axes")
}
