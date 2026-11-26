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
    // Use the same discontinuity threshold as the CV10 OpenXR driver.
    private static final long MAX_SAMPLE_GAP_NS = 50_000_000;
    private static final AtomicReference<Cv10PocketImu> active = new AtomicReference<>();

    private final ArrayBlockingQueue<Sample> samples = new ArrayBlockingQueue<>(64);
    private final ByteBuffer packet = ByteBuffer.allocate(HEADER_BYTES + REPORT_BYTES);
    private volatile boolean closed;
    private Sample previous;
    private boolean clockDiscontinuous;
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
        Sample sample = new Sample(timestamp, System.nanoTime(), imu[0], imu[1], imu[2], imu[3], imu[4], imu[5]);
        if (!source.samples.offer(sample)) {
            source.samples.poll();
            source.samples.offer(sample);
        }
    }

    /** Called by one consumer. Returns false if the headset is silent or this source was closed. */
    boolean writeNext(OutputStream output, long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!closed) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            Sample sample = samples.poll(remaining, TimeUnit.NANOSECONDS);
            if (sample == null || closed) return false;
            long interval = previous == null ? 0 : sample.timestamp() - previous.timestamp();
            previous = sample;
            // USB may deliver old reports on open. Resume once two adjacent reports have a valid interval.
            if (interval <= 0 || interval > MAX_SAMPLE_GAP_NS) {
                clockDiscontinuous = true;
                continue;
            }
            lastWritten = clockDiscontinuous ? Math.max(lastWritten + 1, sample.arrivalNs()) : lastWritten + interval;
            clockDiscontinuous = false;
            writeSample(output, sample);
            return true;
        }
        return false;
    }

    private void writeSample(OutputStream output, Sample sample) throws IOException {
        packet.putLong(HEADER_BYTES + 8, lastWritten);
        packet.position(HEADER_BYTES + 0x1c);
        // Both the Monado driver and Pocket XR's default axis map use x right, y up, z back.
        // Physical axis sign and scale still require a controlled motion check on this headset.
        packet.putFloat(sample.gx()).putFloat(sample.gy()).putFloat(sample.gz());
        packet.putFloat(sample.ax()).putFloat(sample.ay()).putFloat(sample.az());
        output.write(packet.array());
    }

    @Override
    public void close() {
        closed = true;
        active.compareAndSet(this, null);
        samples.clear();
    }

    private record Sample(long timestamp, long arrivalNs, float ax, float ay, float az, float gx, float gy, float gz) {}
}
