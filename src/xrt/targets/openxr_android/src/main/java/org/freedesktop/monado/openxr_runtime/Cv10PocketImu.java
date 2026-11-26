// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.openxr_runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** A local feed from the runtime's existing HID reader for the Pocket XR compatibility preview. */
final class Cv10PocketImu implements AutoCloseable {
    private static final int HEADER_BYTES = 6;
    private static final int REPORT_BYTES = 128;
    private static final int IMU_MAGIC = 0x2836;
    private static final int IMU_REPORT = 0x0b;
    private static final long CV10_DEVICE = 0x43563130L;
    private static final AtomicReference<Cv10PocketImu> active = new AtomicReference<>();

    private final ArrayBlockingQueue<Sample> samples = new ArrayBlockingQueue<>(64);
    private final ByteBuffer packet = ByteBuffer.allocate(HEADER_BYTES + REPORT_BYTES);
    private volatile boolean closed;
    private long lastWritten;

    private Cv10PocketImu() {
        packet.putShort((short) IMU_MAGIC).putInt(REPORT_BYTES);
        packet.order(ByteOrder.LITTLE_ENDIAN);
        packet.putLong(HEADER_BYTES, CV10_DEVICE);
        packet.putInt(HEADER_BYTES + 0x18, IMU_REPORT);
    }

    /** Opens a single local subscription. Construction has no effect on the USB interface. */
    static Cv10PocketImu open() {
        Cv10PocketImu source = new Cv10PocketImu();
        if (!active.compareAndSet(null, source)) throw new IllegalStateException("CV10 IMU feed is already in use");
        return source;
    }

    /** Called by the one HID reader thread. A slow consumer may lose samples without stalling OpenXR. */
    static void publish(long timestamp, float[] imu) {
        Cv10PocketImu source = active.get();
        if (source == null || source.closed || timestamp <= 0) return;
        Sample sample = new Sample(timestamp, imu[0], imu[1], imu[2], imu[3], imu[4], imu[5]);
        if (!source.samples.offer(sample)) {
            source.samples.poll();
            source.samples.offer(sample);
        }
    }

    /** Called by one consumer. Returns false if the headset is silent or this source was closed. */
    boolean writeNext(OutputStream output, long timeoutMs) throws IOException, InterruptedException {
        if (closed) return false;
        Sample sample = samples.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (sample == null || closed) return false;
        if (sample.timestamp() <= lastWritten) throw new IOException("CV10 IMU device clock restarted");
        lastWritten = sample.timestamp();
        packet.putLong(HEADER_BYTES + 8, sample.timestamp());
        packet.position(HEADER_BYTES + 0x1c);
        // Both the Monado driver and Pocket XR's default axis map use x right, y up, z back.
        // Physical axis sign and scale still require a controlled motion check on this headset.
        packet.putFloat(sample.gx()).putFloat(sample.gy()).putFloat(sample.gz());
        packet.putFloat(sample.ax()).putFloat(sample.ay()).putFloat(sample.az());
        output.write(packet.array());
        return true;
    }

    @Override
    public void close() {
        closed = true;
        active.compareAndSet(this, null);
        samples.clear();
    }

    private record Sample(long timestamp, float ax, float ay, float az, float gx, float gy, float gz) {}
}
