// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.openxr_runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin

/** The pad owns its touches while the OpenXR client keeps Android and OpenXR focus. */
class Cv10PhoneControllerService : Service(), SensorEventListener {
    private val orientation = Cv10PhoneOrientation()
    private var pad: LinearLayout? = null
    private var windows: WindowManager? = null
    private var receiverRegistered = false
    private var screenOn = false
    @Volatile private var sample: PoseSample? = null
    @Volatile private var selectUntil = 0L

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == SPATIAL_VISIBILITY) {
                pad?.visibility = if (intent.getBooleanExtra("visible", false)) View.GONE else View.VISIBLE
                return
            }
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            sample = null
            selectUntil = 0L
            orientation.reset()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (pad != null) return START_NOT_STICKY

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.cv10_phone_start),
            NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.cv10_phone_active))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.cv10_phone_stop), stop).build())
            .build())

        try {
            if (!Settings.canDrawOverlays(this)) error("Overlay permission is required")
            val sensors = getSystemService(SensorManager::class.java)
            val rotation = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: error("No rotation sensor")
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                ?: error("No phone display")
            val displayContext = createDisplayContext(display)
            val context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                displayContext
            }
            val overlay = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(context).apply {
                    setText(R.string.cv10_phone_select)
                    setOnClickListener {
                        if (sample != null) selectUntil = System.nanoTime() + SELECT_PULSE_NS
                    }
                })
                addView(Button(context).apply {
                    setText(R.string.cv10_phone_recenter)
                    setOnClickListener { this@Cv10PhoneControllerService.orientation.center() }
                })
                addView(Button(context).apply {
                    setText(android.R.string.cancel)
                    setOnClickListener { stopSelf() }
                })
            }
            val layout = WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            val windowManager = context.getSystemService(WindowManager::class.java)
            windowManager.addView(overlay, layout)
            windows = windowManager
            pad = overlay
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(SPATIAL_VISIBILITY)
            }
            screenOn = getSystemService(PowerManager::class.java).isInteractive
            ContextCompat.registerReceiver(this, events, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            check(sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME))
            current = this
        } catch (error: RuntimeException) {
            Log.w(TAG, "Couldn't start the OpenXR phone controller", error)
            Toast.makeText(this, R.string.cv10_phone_unavailable, Toast.LENGTH_SHORT).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        current = null
        sample = null
        selectUntil = 0L
        getSystemService(SensorManager::class.java).unregisterListener(this)
        if (receiverRegistered) unregisterReceiver(events)
        pad?.let { windows?.removeViewImmediate(it) }
        pad = null
        super.onDestroy()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (!screenOn) return
        val rotation = pad?.display?.rotation ?: return
        orientation.update(event.values, rotation)
        val yaw = -orientation.yaw * 0.5f
        val pitch = orientation.pitch * 0.5f
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        // OpenXR aims along -Z, with Y up. Yaw is applied after pitch.
        sample = PoseSample(System.nanoTime(), cy * sp, sy * cp, -sy * sp, cy * cp)
    }

    private data class PoseSample(val time: Long, val x: Float, val y: Float, val z: Float, val w: Float)

    companion object {
        private const val TAG = "Cv10PhoneController"
        private const val CHANNEL = "cv10_phone_controller"
        private const val STOP = "org.cv10.monado.action.STOP_PHONE_CONTROLLER"
        private const val SPATIAL_VISIBILITY = "org.cv10.monado.action.SPATIAL_LAUNCHER_VISIBILITY"
        private const val NOTIFICATION_ID = 10170
        private const val SELECT_PULSE_NS = 100_000_000L
        @Volatile private var current: Cv10PhoneControllerService? = null

        @JvmStatic fun isRunning() = current != null

        internal fun showSpatialLauncher(context: Context, visible: Boolean) {
            context.sendBroadcast(Intent(SPATIAL_VISIBILITY).setPackage(context.packageName).putExtra("visible", visible))
        }

        @JvmStatic fun read(values: FloatArray): Long {
            val service = current ?: return 0
            val pose = service.sample ?: return 0
            values[0] = pose.x
            values[1] = pose.y
            values[2] = pose.z
            values[3] = pose.w
            values[4] = 1f
            values[5] = if (pose.time < service.selectUntil) 1f else 0f
            values[6] = 0f
            values[7] = 1f
            return pose.time
        }
    }
}
