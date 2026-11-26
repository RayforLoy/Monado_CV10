package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

object BuildConfig {
    const val cv10LauncherPreview = true
    const val inProcess = false
}

class HeadLinkProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val consent = VpnService.prepare(this)
        if (consent == null) open() else startActivityForResult(consent, 1)
    }

    @Deprecated("Used for the system VPN consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) open()
    }

    private fun open() {
        startForegroundService(Cv10PocketHeadLinkService.intent(this))
        if (source?.isAlive == true) return
        source = thread(isDaemon = true, name = "cv10-capture-replay") {
            val frames = assets.open("cv10-pocket-imu.bin").use { it.readBytes() }
            val recordBytes = 134
            require(frames.size % recordBytes == 0)
            val records = frames.size / recordBytes
            val stream = ByteBuffer.wrap(frames).order(ByteOrder.LITTLE_ENDIAN)
            val firstTime = stream.getLong(14)
            val finalTime = stream.getLong((records - 1) * recordBytes + 14)
            val period = finalTime - firstTime + 1_000_000L
            check(records == 3000 && period in 3_000_000_000L..3_100_000_000L)
            val sample = FloatArray(6)
            val start = SystemClock.elapsedRealtimeNanos()
            Log.i("Cv10Replay", "Replaying $records Fold7 CV10 samples, source span ${finalTime - firstTime} ns")
            repeat(9) { loop ->
                repeat(records) { index ->
                    val position = index * recordBytes
                    val time = start + loop * period + stream.getLong(position + 14) - firstTime
                    stream.position(position + 34)
                    for (axis in 3..5) sample[axis] = stream.float
                    for (axis in 0..2) sample[axis] = stream.float
                    val wait = time - SystemClock.elapsedRealtimeNanos()
                    if (wait > 0L) LockSupport.parkNanos(wait)
                    Cv10PocketImu.publish(time, sample)
                }
            }
            Log.i("Cv10Replay", "Finished replay of ${9 * records} captured samples")
        }
    }

    companion object {
        private var source: Thread? = null
    }
}
