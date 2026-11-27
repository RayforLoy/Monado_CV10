package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        source = thread(isDaemon = true, name = "fold7-live-feed") {
            try {
                ServerSocket(INPUT_PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    Log.i(TAG, "Waiting for a live Fold7 IMU stream on loopback")
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        readLive(DataInputStream(socket.getInputStream()))
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Live input failed", error)
            }
        }
    }

    private fun readLive(input: DataInputStream) {
        val packet = ByteArray(128)
        val bytes = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val imu = FloatArray(6)
        var samples = 0
        var time = 0L
        try {
            while (true) {
                check(input.readUnsignedShort() == 0x2836 && input.readInt() == packet.size)
                input.readFully(packet)
                val next = bytes.getLong(8)
                check(next > time)
                time = next
                bytes.position(0x1c)
                for (axis in 3..5) imu[axis] = bytes.float
                for (axis in 0..2) imu[axis] = bytes.float
                check(imu.all { it.isFinite() })
                Cv10PocketImu.publish(time, imu)
                samples++
                if (samples == 1 || samples % 5000 == 0) Log.i(TAG, "Published $samples live CV10 samples")
            }
        } catch (_: EOFException) {
            Log.i(TAG, "Live Fold7 stream ended after $samples samples")
        }
    }

    companion object {
        private const val INPUT_PORT = 18107
        private const val TAG = "Cv10LiveRelay"
        private var source: Thread? = null
    }
}
