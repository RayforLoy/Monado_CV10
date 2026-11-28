// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.openxr_runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sin
import org.freedesktop.monado.auxiliary.Cv10ControllerRoles
import org.freedesktop.monado.auxiliary.Cv10PhonePadHost

/** The Monado runtime owns the phone IMU; the focused app supplies only pad button transitions. */
@Singleton
class Cv10PhoneControllerService @Inject constructor(@ApplicationContext private val context: Context) :
    Cv10PhonePadHost, SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val handler = Handler(Looper.getMainLooper())
    private val orientation = Cv10PhoneOrientation()
    private var owner: Any? = null
    private var rotation = 0
    private var heading = 0f
    private var centerEpoch = 0
    @Volatile private var pose: Pose? = null
    @Volatile private var held = 0
    @Volatile private var selectUntil = 0L
    @Volatile private var menuUntil = 0L
    private data class Touch(val x: Float, val y: Float)
    @Volatile private var contact: Touch? = null

    @Synchronized override fun activate(client: Any, portraitRotation: Int): Boolean {
        if (portraitRotation !in Surface.ROTATION_0..Surface.ROTATION_270 ||
            !Cv10ControllerRoles.enabled(context) || rotationSensor == null) return false
        if (owner === client && rotation == portraitRotation) return true
        if (owner == null && !sensors.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME, handler)) return false
        owner = client
        rotation = portraitRotation
        heading = 0f
        held = 0; selectUntil = 0; menuUntil = 0
        contact = null
        pose = null
        orientation.reset()
        current = this
        return true
    }

    @Synchronized override fun release(client: Any) {
        if (owner !== client) return
        owner = null
        current = null
        sensors.unregisterListener(this)
        pose = null
        held = 0; selectUntil = 0; menuUntil = 0
        contact = null
        orientation.reset()
    }

    @Synchronized override fun buttons(client: Any, buttons: Int) {
        if (owner !== client) return
        val rising = buttons and held.inv()
        // A tap on UP is a pulse. Keep it visible across ordinary 30, 60 and 90 Hz action polls.
        if (rising and 1 != 0) selectUntil = System.nanoTime() + 64_000_000L
        if (rising and 2 != 0) menuUntil = System.nanoTime() + 32_000_000L
        held = buttons
    }

    @Synchronized override fun touch(client: Any, active: Boolean, x: Float, y: Float) {
        if (owner !== client) return
        contact = if (active) Touch(x, y) else null
    }

    @Synchronized override fun recenter(client: Any) {
        if (owner === client) center()
    }

    @Synchronized private fun center() {
        centerEpoch = (centerEpoch + 1) and 0xffff
        orientation.center()
        val head = Cv10SimulationSettings.head
        if (head != null && head.hasHeading() && System.nanoTime() - head.ns in 0..500_000_000L) heading = head.yaw()
        if (orientation.hasSample) publish()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Synchronized override fun onSensorChanged(event: SensorEvent) {
        if (owner == null) return
        if (orientation.update(event.values, rotation)) center() else publish()
    }

    private fun publish() {
        val yaw = (heading - orientation.yaw) * 0.5f
        val pitch = orientation.pitch * 0.5f
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        pose = Pose(System.nanoTime(), cy * sp, sy * cp, -sy * sp, cy * cp, centerEpoch, orientation.grip(heading))
    }

    private data class Pose(val time: Long, val x: Float, val y: Float, val z: Float, val w: Float, val centerEpoch: Int,
                            val grip: Cv10PhoneRotation)

    companion object {
        @Volatile private var current: Cv10PhoneControllerService? = null
        @JvmStatic fun isRunning() = current != null
        @JvmStatic fun recenterToHead() { current?.center() }
        @JvmStatic fun read(values: FloatArray): Long {
            val pad = current ?: return 0
            val pose = pad.pose ?: return 0
            val now = System.nanoTime()
            if (now - pose.time > 500_000_000L) return 0
            values[0] = pose.x; values[1] = pose.y; values[2] = pose.z; values[3] = pose.w
            values[4] = 1f
            values[5] = if (pad.held and 1 != 0 || now < pad.selectUntil) 1f else 0f
            values[6] = if (pad.held and 2 != 0 || now < pad.menuUntil) 1f else 0f
            values[7] = 1f
            if (values.size > 14) {
                values[8] = pose.grip.x; values[9] = pose.grip.y; values[10] = pose.grip.z; values[11] = pose.grip.w
                values[14] = pose.centerEpoch.toFloat()
            }
            if (values.size >= 18) {
                val touch = pad.contact
                values[15] = if (touch == null) 0f else 1f
                values[16] = touch?.x ?: 0f
                values[17] = touch?.y ?: 0f
            }
            return pose.time
        }
    }
}
