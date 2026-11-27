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

/** Turns the foreground phone into a pointer. The menu spans [-1, 1], with right and down positive. */
internal class Cv10LaserInput(
    private val view: View,
    private val onAim: (Float, Float) -> Unit,
    private val onConfirm: (Float, Float) -> Unit,
    private val onBack: () -> Unit,
    private val onHome: () -> Unit,
    private val onRecenter: () -> Unit,
    private val onPage: (Int) -> Unit,
) : View.OnTouchListener, SensorEventListener {
    private val sensors = view.context.getSystemService(SensorManager::class.java)
    private val rotationSensor = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
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
        val topInset: Float,
        val bottomInset: Float,
        val width: Float,
        val height: Float,
    )

    private var finger = MotionEvent.INVALID_POINTER_ID
    private var zone = Zone.POINTER
    private var downX = 0f
    private var downY = 0f
    private var pointerMoved = false
    private var homeStart = 0L
    private var homeLongPressed = false
    private var backPressed = false
    private val finishHomePress = Runnable {
        if (homeStart != 0L) {
            homeLongPressed = true
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
                if (zone == Zone.SYSTEM) return true
                finger = event.getPointerId(0)
                aimByTouch(downX, downY)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(finger)
                if (index < 0) return true
                updateTouch(event.getX(index), event.getY(index), event.eventTime)
            }
            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(0) != finger) return true
                updateTouch(event.x, event.y, event.eventTime)
                when (zone) {
                    Zone.RECENTER -> if (homeStart != 0L && !homeLongPressed) {
                        if (event.eventTime - homeStart >= Cv10ControllerLauncherInput.HOME_HOLD_MILLIS) {
                            recenter()
                        } else {
                            onHome()
                        }
                    }
                    Zone.LEFT, Zone.RIGHT -> if (backPressed) onBack()
                    Zone.POINTER -> {
                        val duration = event.eventTime - event.downTime
                        if (!pointerMoved && duration < ViewConfiguration.getLongPressTimeout()) {
                            // Standard Android also delivers legitimate taps with no MOVE.
                            if (!v.performClick()) confirm()
                        } else {
                            val regions = gestureRegions()
                            val dx = (event.x - downX) / (0.90f * regions.width)
                            val dy = (event.y - downY) / (0.90f * regions.height)
                            if (dx * dx + dy * dy >= 0.04f && abs(dx) > abs(dy)) {
                                onPage(if (dx < 0) 1 else -1)
                            }
                        }
                    }
                    Zone.SYSTEM -> Unit
                }
                cancelTouch()
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
        val left = (systemInsets?.left ?: 0).toFloat()
        val right = (systemInsets?.right ?: 0).toFloat()
        val top = (systemInsets?.top ?: 0).toFloat()
        return GestureRegions(
            leftInset = left,
            rightInset = right,
            topInset = top,
            bottomInset = bottomGap,
            width = (view.width - left - right).coerceAtLeast(1f),
            height = (view.height - top - bottomGap).coerceAtLeast(1f),
        )
    }

    private fun zoneFor(x: Float, y: Float): Zone {
        val regions = gestureRegions()
        val fromRight = view.width - x
        val fromBottom = view.height - y
        return when {
            fromBottom < regions.bottomInset || y < regions.topInset || x < regions.leftInset ||
                fromRight < regions.rightInset -> Zone.SYSTEM
            fromBottom <= regions.bottomInset + 0.10f * regions.height -> Zone.RECENTER
            x < regions.leftInset + 0.05f * regions.width -> Zone.LEFT
            fromRight < regions.rightInset + 0.05f * regions.width -> Zone.RIGHT
            else -> Zone.POINTER
        }
    }

    private fun updateTouch(x: Float, y: Float, eventTime: Long) {
        val dx = x - downX
        val dy = y - downY
        when (zone) {
            Zone.POINTER -> {
                pointerMoved = pointerMoved || abs(dx) >= 5f || abs(dy) >= 5f
                aimByTouch(x, y)
            }
            Zone.RECENTER -> if (homeStart == 0L && -dy >= 40f && -dy > abs(dx)) {
                homeStart = eventTime
                view.postDelayed(finishHomePress, Cv10ControllerLauncherInput.HOME_HOLD_MILLIS)
            }
            Zone.LEFT -> backPressed = backPressed || dx >= 40f && dx > abs(dy)
            Zone.RIGHT -> backPressed = backPressed || -dx >= 40f && -dx > abs(dy)
            Zone.SYSTEM -> Unit
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
        pointerMoved = false
        homeStart = 0
        homeLongPressed = false
        backPressed = false
        view.removeCallbacks(finishHomePress)
    }
}
