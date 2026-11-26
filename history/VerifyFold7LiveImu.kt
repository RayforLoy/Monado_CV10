// SPDX-License-Identifier: BSL-1.0
import com.hayeco.flipspaces.core.spatial.ControlFramer
import com.hayeco.flipspaces.core.spatial.ControlMessage
import com.hayeco.flipspaces.core.spatial.ControlWire
import com.hayeco.flipspaces.core.spatial.GlassesControl
import com.hayeco.flipspaces.core.spatial.HeadFusion
import com.hayeco.flipspaces.core.spatial.OneImuFramer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

private const val sampleCount = 3000

private fun connect(port: Int) = Socket().apply {
    connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000)
    soTimeout = 10000
}

private fun verifyControl(port: Int) {
    connect(port).use { socket ->
        val now = System.nanoTime()
        val bytes = ControlWire.heartbeat(now) + ControlWire.stereoGet(ControlWire.rpcId(41))
        val output = socket.getOutputStream()
        bytes.asList().chunked(5).forEach { chunk -> output.write(chunk.toByteArray()) }

        val framer = ControlFramer()
        val responses = mutableListOf<ControlMessage>()
        val buffer = ByteArray(19)
        while (responses.size < 2) {
            val count = socket.getInputStream().read(buffer)
            check(count > 0) { "Control socket closed before both replies" }
            responses.addAll(framer.append(buffer, count))
        }
        val heartbeat = responses[0] as ControlMessage.Heartbeat
        val reply = responses[1] as ControlMessage.Rpc
        check(heartbeat.kind == GlassesControl.HEARTBEAT_RESPONSE && heartbeat.t1 == now)
        check(heartbeat.t3 >= heartbeat.t2 && heartbeat.t2 > 0)
        check(reply.rpcId == 42 && reply.result == 1L && reply.value == null)
        println("Stock control parser verified heartbeat and unsupported stereo-control reply")
    }
}

private fun verifyImu(port: Int) {
    connect(port).use { socket ->
        val framer = OneImuFramer()
        val fusion = HeadFusion()
        val buffer = ByteArray(997)
        val intervals = mutableListOf<Long>()
        var last = 0L
        var frames = 0
        while (frames < sampleCount) {
            val count = socket.getInputStream().read(buffer)
            check(count > 0) { "IMU socket closed after $frames samples" }
            for (sample in framer.append(buffer, count).take(sampleCount - frames)) {
                check(sample.timestampNs > last) { "CV10 timestamp moved backwards at frame $frames" }
                if (last != 0L) intervals.add(sample.timestampNs - last)
                val a = sample.accel
                check(a.x * a.x + a.y * a.y + a.z * a.z in 64f..144f)
                fusion.update(sample)
                last = sample.timestampNs
                frames++
            }
        }
        check(framer.counts.droppedBytes == 0L)
        val rateHz = 1e9 * intervals.size / intervals.sum()
        check(rateHz in 750.0..1100.0) { "Unexpected CV10 IMU rate: $rateHz Hz" }
        val (yaw, pitch, roll) = fusion.pose().orientation.yawPitchRoll()
        check(yaw.isFinite() && pitch.isFinite() && roll.isFinite())
        println(String.format(Locale.ROOT, "%d live frames, 0 dropped bytes, %.2f Hz, max interval %.3f ms",
            frames, rateHz, intervals.max() / 1e6))
        println(String.format(Locale.ROOT, "Stock fusion angles (deg): yaw %+.3f, pitch %+.3f, roll %+.3f",
            yaw, pitch, roll))
    }
}

fun main(args: Array<String>) {
    require(args.size == 2) { "Pass the two local ADB forward ports for IMU and control" }
    val (imuPort, controlPort) = args.map(String::toInt)
    repeat(2) { round ->
        println("Connection ${round + 1}")
        verifyControl(controlPort)
        verifyImu(imuPort)
    }
}
