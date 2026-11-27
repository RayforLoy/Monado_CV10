// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import org.freedesktop.monado.auxiliary.Cv10PocketXrDisplayService

/** Session-local metadata for buffers rendered straight into Monado's Surface. */
internal class Cv10PocketFramePoses private constructor(
    private val reader: ParcelFileDescriptor,
    private var writer: ParcelFileDescriptor?,
    private val producerUid: Int,
) : Closeable {
    data class Pose(
        val stamp: Long,
        val predictionTime: Long,
        val recenter: Int,
        val center: Orientation,
        val top: Orientation,
        val bottom: Orientation,
    )

    data class Orientation(
        val x: Float,
        val y: Float,
        val z: Float,
        val w: Float,
    )

    private val bytes = ByteArray(RECORD_BYTES)
    private val record = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private var extent = 0
    private val pending = TreeMap<Long, Pose>()

    // An Activity launch cannot carry file descriptors. The designated producer redeems this
    // Binder once after checking the launch identity. Frame delivery itself does not use Binder.
    val source: IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != FIRST_CALL_TRANSACTION || reply == null) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            if (getCallingUid() != producerUid) throw SecurityException("Unexpected CV10 pose producer")
            synchronized(this@Cv10PocketFramePoses) {
                reply.writeNoException()
                reply.writeTypedObject(writer, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                writer = null
            }
            return true
        }
    }

    /** Called on the capture thread, with the timestamp of the image currently latched by EGL. */
    fun match(stamp: Long): Pose? {
        pending.headMap(stamp, false).clear()
        repeat(MAX_PENDING) {
            val read = try {
                Os.read(reader.fileDescriptor, bytes, extent, bytes.size - extent)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN) {
                    Log.w(TAG, "CV10 pose channel read failed", error)
                }
                return pending.remove(stamp)
            } catch (error: IOException) {
                Log.w(TAG, "CV10 pose channel read failed", error)
                return pending.remove(stamp)
            }
            if (read == 0) return pending.remove(stamp)
            extent += read
            if (extent < bytes.size) return@repeat
            extent = 0
            record.rewind()
            if (record.int != MAGIC) return@repeat
            val recenter = record.int
            val timestamp = record.long
            val predictionTime = record.long
            if (timestamp < stamp || predictionTime <= 0 || recenter < 0) return@repeat
            val center = readOrientation() ?: return@repeat
            val top = readOrientation() ?: return@repeat
            val bottom = readOrientation() ?: return@repeat
            pending[timestamp] = Pose(timestamp, predictionTime, recenter, center, top, bottom)
            if (pending.size > MAX_PENDING) pending.pollLastEntry()
        }
        return pending.remove(stamp)
    }

    private fun readOrientation(): Orientation? {
        val x = record.float
        val y = record.float
        val z = record.float
        val w = record.float
        val norm = x * x + y * y + z * z + w * w
        return if (norm in 0.98f..1.02f) Orientation(x, y, z, w) else null
    }

    @Synchronized
    override fun close() {
        try {
            writer?.close()
            writer = null
        } finally {
            reader.close()
        }
    }

    companion object {
        const val TAG = "Cv10PocketFramePoses"
        private const val DESCRIPTOR = "org.cv10.pocketxr.frame_pose_source.v1"
        private const val MAGIC = 0x31505643 // CVP1
        private const val RECORD_BYTES = 72
        private const val MAX_PENDING = 32

        fun create(context: Context): Cv10PocketFramePoses? = runCatching {
            val uid = context.packageManager.getPackageUid(Cv10PocketXrDisplayService.POCKET_CV10_PACKAGE, 0)
            val (receive, send) = ParcelFileDescriptor.createPipe()
            try {
                for (endpoint in listOf(receive, send)) {
                    val fd = endpoint.fileDescriptor
                    val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
                    Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
                }
                Cv10PocketFramePoses(receive, send, uid)
            } catch (error: Exception) {
                runCatching { receive.close() }
                runCatching { send.close() }
                throw error
            }
        }.onFailure { Log.w(TAG, "CV10 frame metadata unavailable", it) }.getOrNull()
    }
}
