// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Turns the foreground phone into a pointer. Aim is in [-1, 1], with right and down positive. */
internal class Cv10LaserInput(
    private val view: View,
    private val onAim: (Float, Float) -> Unit,
    private val onConfirm: (Float, Float) -> Unit,
    private val onBack: () -> Unit,
    private val onRecenter: () -> Unit,
) : View.OnTouchListener, SensorEventListener {
    private val sensors = view.context.getSystemService(SensorManager::class.java)
    private val rotationSensor = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private val density = view.resources.displayMetrics.density
    private val sample = FloatArray(9)
    private val forward = FloatArray(3)
    private val right = FloatArray(3)
    private val up = FloatArray(3)

    private var running = false
    private var hasSample = false
    private var headingAxis = 2
    private var headingSign = -1f
    private var displayRotation = Surface.ROTATION_0
    private var aimX = 0f
    private var aimY = 0f

    private enum class Zone { POINTER, LEFT, RIGHT, RECENTER, SYSTEM }

    internal data class GestureRegions(
        val leftInset: Float,
        val rightInset: Float,
        val bottomInset: Float,
        val backBandWidth: Float,
        val recenterBandHeight: Float,
    )

    private var finger = MotionEvent.INVALID_POINTER_ID
    private var zone = Zone.POINTER
    private var downX = 0f
    private var downY = 0f
    private var furthestMovement = 0f
    private var holdStarted = false
    private var holdX = 0f
    private var holdY = 0f
    private var held = false
    private val finishHold = Runnable {
        if (finger != MotionEvent.INVALID_POINTER_ID && holdStarted && !held) {
            held = true
            recenter()
        }
    }

    fun start() {
        if (running) return
        running = true
        rotationSensor?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        updateAim(0f, 0f)
    }

    fun stop() {
        if (!running) return
        running = false
        sensors?.unregisterListener(this)
        hasSample = false
        cancelTouch()
    }

    fun confirm() = onConfirm(aimX, aimY)

    fun recenter() {
        if (hasSample) calibrate()
        updateAim(0f, 0f)
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onRecenter()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        SensorManager.getRotationMatrixFromVector(sample, event.values)
        if (!hasSample || displayRotation != (view.display?.rotation ?: Surface.ROTATION_0)) {
            hasSample = true
            calibrate()
            updateAim(0f, 0f)
            return
        }

        val x = headingSign * sample[headingAxis]
        val y = headingSign * sample[headingAxis + 3]
        val z = headingSign * sample[headingAxis + 6]
        val horizontal = right[0] * x + right[1] * y
        val vertical = up[0] * x + up[1] * y + up[2] * z
        val depth = forward[0] * x + forward[1] * y + forward[2] * z
        val targetX = atan2(horizontal, depth) / 0.52f
        // Elevation increases upward while menu Y increases downward.
        val targetY = -atan2(vertical, hypot(horizontal, depth)) / 0.40f
        updateAim(aimX + (targetX - aimX) * 0.35f, aimY + (targetY - aimY) * 0.35f)
    }

    private fun calibrate() {
        displayRotation = view.display?.rotation ?: Surface.ROTATION_0
        // A flat phone points from its top edge. An upright phone points from its back.
        val topAxis = if (displayRotation == Surface.ROTATION_90 ||
            displayRotation == Surface.ROTATION_270) 0 else 1
        if (abs(sample[topAxis + 6]) < abs(sample[8])) {
            headingAxis = topAxis
            headingSign = if (displayRotation == Surface.ROTATION_90 ||
                displayRotation == Surface.ROTATION_180) -1f else 1f
        } else {
            headingAxis = 2
            headingSign = -1f
        }
        for (i in forward.indices) forward[i] = headingSign * sample[3 * i + headingAxis]

        // The world's Z is gravity-up. Keep the pointer level even when the phone is rolled.
        val horizontalLength = sqrt(forward[0] * forward[0] + forward[1] * forward[1])
        right[0] = forward[1] / horizontalLength
        right[1] = -forward[0] / horizontalLength
        up[0] = right[1] * forward[2]
        up[1] = -right[0] * forward[2]
        up[2] = horizontalLength
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (!running) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTouch()
                downX = event.x
                downY = event.y
                zone = zoneFor(downX, downY)
                if (zone != Zone.SYSTEM) {
                    finger = event.getPointerId(0)
                    aimByTouch(downX, downY)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(finger)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                val dx = x - downX
                val dy = y - downY
                furthestMovement = maxOf(furthestMovement, hypot(dx, dy))
                aimByTouch(x, y)
                if (zone == Zone.RECENTER) updateHold(x, y, dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(0) != finger) return true
                val dx = event.x - downX
                val dy = event.y - downY
                val inward = when (zone) {
                    Zone.LEFT -> dx
                    Zone.RIGHT -> -dx
                    else -> 0f
                }
                val goBack = inward >= 56 * density && inward > abs(dy) * 1.3f
                val tap = !held && maxOf(furthestMovement, hypot(dx, dy)) <= touchSlop &&
                    event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()
                cancelTouch()
                if (goBack) onBack()
                else if (tap) {
                    if (!v.performClick()) confirm()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    fun gestureRegions(): GestureRegions {
        val windowInsets = view.rootWindowInsets
        @Suppress("DEPRECATION")
        val systemInsets = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                windowInsets?.getInsets(WindowInsets.Type.systemGestures())
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> windowInsets?.systemGestureInsets
            else -> null
        }
        @Suppress("DEPRECATION")
        val mandatoryBottom = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                windowInsets?.getInsets(WindowInsets.Type.mandatorySystemGestures())?.bottom ?: 0
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                windowInsets?.mandatorySystemGestureInsets?.bottom ?: 0
            else -> 0
        }
        val bottomGap = (maxOf(systemInsets?.bottom ?: 0, mandatoryBottom) + 8 * density)
            .coerceAtLeast(24 * density)
        return GestureRegions(
            leftInset = (systemInsets?.left ?: 0).toFloat(),
            rightInset = (systemInsets?.right ?: 0).toFloat(),
            bottomInset = bottomGap,
            backBandWidth = 40 * density,
            recenterBandHeight = 48 * density,
        )
    }

    private fun zoneFor(x: Float, y: Float): Zone {
        val regions = gestureRegions()
        val fromRight = view.width - x
        val fromBottom = view.height - y
        return when {
            fromBottom < regions.bottomInset || x < regions.leftInset ||
                fromRight < regions.rightInset -> Zone.SYSTEM
            x < regions.leftInset + regions.backBandWidth -> Zone.LEFT
            fromRight < regions.rightInset + regions.backBandWidth -> Zone.RIGHT
            fromBottom < regions.bottomInset + regions.recenterBandHeight -> Zone.RECENTER
            else -> Zone.POINTER
        }
    }

    private fun updateHold(x: Float, y: Float, dx: Float, dy: Float) {
        if (held) return
        val aboveStart = -dy >= 48 * density && -dy > abs(dx) * 1.3f
        if (!aboveStart) {
            holdStarted = false
            view.removeCallbacks(finishHold)
        } else if (!holdStarted || hypot(x - holdX, y - holdY) > 14 * density) {
            holdX = x
            holdY = y
            holdStarted = true
            view.removeCallbacks(finishHold)
            view.postDelayed(finishHold, 350)
        }
    }

    private fun aimByTouch(x: Float, y: Float) {
        if (hasSample || zone != Zone.POINTER || view.width <= 0 || view.height <= 0) return
        updateAim((2 * x / view.width - 1).coerceIn(-1f, 1f), (2 * y / view.height - 1).coerceIn(-1f, 1f))
    }

    private fun updateAim(x: Float, y: Float) {
        aimX = x
        aimY = y
        onAim(x, y)
    }

    private fun cancelTouch() {
        finger = MotionEvent.INVALID_POINTER_ID
        furthestMovement = 0f
        holdStarted = false
        held = false
        view.removeCallbacks(finishHold)
    }
}
