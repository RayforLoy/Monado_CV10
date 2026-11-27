// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

final class Cv10KitCalibration {
    private static final int HASH = 0x2a;
    private static final int CHUNK = 0x29;
    private static final int ANDROID = 0x02;
    private static final int WINDOWS = 0x05;
    private static final int REPLY_TIMEOUT_MS = 600;
    private static final int MAX_CHUNKS = 64;
    private static final int MAX_CALIBRATION_BYTES = 2048;
    private static final int LOCATOR_IMU = 0x2a0;
    private static final int HAND_IMU = 0x240;
    private static final int IMU_FIELDS_BYTES = 0xda;

    enum Component {
        LOCATOR("locator", 0x81, 22, 0x3de),
        LEFT("left", 0x83, 29, 0x32c),
        RIGHT("right", 0x82, 29, 0x32c);

        final String filename;
        final int selector;
        final int windowsChunks;
        final int windowsBytes;

        Component(String filename, int selector, int windowsChunks, int windowsBytes) {
            this.filename = filename;
            this.selector = selector;
            this.windowsChunks = windowsChunks;
            this.windowsBytes = windowsBytes;
        }
    }

    interface Link {
        byte[] read(int timeoutMs) throws IOException, InterruptedException;
        void write(byte[] report) throws IOException, InterruptedException;
    }

    static final class Calibration {
        final byte[] deviceHash, data;
        final int dialect;

        Calibration(byte[] deviceHash, byte[] data, int dialect) {
            this.deviceHash = deviceHash;
            this.data = data;
            this.dialect = dialect;
        }

        String protocol() {
            return dialect == ANDROID ? "Android" : "Windows";
        }
    }

    static final class CameraCalibration {
        final int id, width, height, rollingMode, distortionMode;
        final float framesPerSecond, autofocusCode, pixelSigma;
        final double readoutSeconds, exposureShiftSeconds, cameraToImuDelaySeconds;
        final boolean needsUndistortion;
        final double[] intrinsics, distortion, autofocusCompensation, tbc, tcm;

        CameraCalibration(ByteBuffer fields) throws IOException {
            id = unsigned(fields.get());
            width = fields.getInt();
            height = fields.getInt();
            framesPerSecond = fields.getFloat();
            rollingMode = unsigned(fields.get());
            readoutSeconds = fields.getDouble();
            exposureShiftSeconds = fields.getDouble();
            cameraToImuDelaySeconds = fields.getDouble();
            intrinsics = readDoubles(fields, 4);
            needsUndistortion = fields.get() != 0;
            distortionMode = unsigned(fields.get());
            distortion = readDoubles(fields, 8);
            autofocusCode = fields.getFloat();
            autofocusCompensation = new double[4];
            for (int i = 0; i < autofocusCompensation.length; i++) {
                autofocusCompensation[i] = fields.getFloat();
            }
            tbc = readDoubles(fields, 7);
            tcm = readDoubles(fields, 7);
            pixelSigma = fields.getFloat();
        }

        private boolean isUsable() {
            return width == Cv10KitReport.CAMERA_WIDTH && height == Cv10KitReport.CAMERA_HEIGHT &&
                    Float.isFinite(framesPerSecond) && framesPerSecond > 0 &&
                    Double.isFinite(readoutSeconds) && Double.isFinite(exposureShiftSeconds) &&
                    Double.isFinite(cameraToImuDelaySeconds) &&
                    intrinsics[0] > 0 && intrinsics[1] > 0;
        }
    }

    static final class ImuCalibration {
        final float gyroHz, accelHz;
        final boolean needsCompensation;
        final double[] gyroBias, gyroScale, gyroMisalignment, accelBias, accelScale, accelMisalignment;
        final double[] gyroToAccel;
        final double gyroNoiseSigma, accelNoiseSigma, gyroBiasSigma, accelBiasSigma;

        private ImuCalibration(ByteBuffer fields) throws IOException {
            fields.get(); // The stock parser skips this byte before the rates.
            gyroHz = fields.getFloat();
            accelHz = fields.getFloat();
            needsCompensation = fields.get() != 0;
            gyroBias = readDoubles(fields, 3);
            gyroScale = readDoubles(fields, 3);
            gyroMisalignment = readDoubles(fields, 3);
            accelBias = readDoubles(fields, 3);
            accelScale = readDoubles(fields, 3);
            accelMisalignment = readDoubles(fields, 3);
            gyroToAccel = readDoubles(fields, 4);
            gyroNoiseSigma = fields.getDouble();
            accelNoiseSigma = fields.getDouble();
            gyroBiasSigma = fields.getDouble();
            accelBiasSigma = fields.getDouble();
        }
    }

    static final class HandCalibration {
        final double[][] ledPositions;
        final double[] imuToHandle;
        final double cameraToImuDelaySeconds;
        final ImuCalibration imu;

        private HandCalibration(ByteBuffer fields, ImuCalibration imu) throws IOException {
            this.imu = imu;
            fields.position(0x40);
            double[] handleToImu = readDoubles(fields, 7);
            if (handleToImu[0] == 0 && handleToImu[1] == 0 && handleToImu[2] == 0 && handleToImu[3] == 0) {
                throw new IOException("Invalid hand extrinsic rotation");
            }
            double x = -handleToImu[0], y = -handleToImu[1], z = -handleToImu[2], w = handleToImu[3];
            double px = -handleToImu[4], py = -handleToImu[5], pz = -handleToImu[6];
            // SaveHand conjugates the unit quaternion and multiplies the negated translation by its rotation.
            double cx = 2 * (y * pz - z * py);
            double cy = 2 * (z * px - x * pz);
            double cz = 2 * (x * py - y * px);
            imuToHandle = new double[] {x, y, z, w, px + w * cx + y * cz - z * cy,
                    py + w * cy + z * cx - x * cz, pz + w * cz + x * cy - y * cx};

            fields.position(0x98);
            ledPositions = new double[16][];
            for (int i = 0; i < ledPositions.length; i++) {
                ledPositions[i] = readDoubles(fields, 3);
            }
            cameraToImuDelaySeconds = fields.getDouble();
            if (!Double.isFinite(cameraToImuDelaySeconds)) throw new IOException("Invalid hand camera to IMU delay");
        }
    }

    static CameraCalibration[] locatorCameras(byte[] data) throws IOException {
        // SaveHelmet skips a 0x40-byte header. GetCamPara advances by 0x130 bytes per camera.
        final int cameraStart = 0x40;
        final int cameraSize = 0x130;
        if (data.length < cameraStart + (2 * cameraSize)) throw new IOException("Truncated stereo calibration");
        ByteBuffer fields = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        fields.position(cameraStart);
        CameraCalibration first = new CameraCalibration(fields);
        fields.position(cameraStart + cameraSize);
        CameraCalibration second = new CameraCalibration(fields);
        if (!first.isUsable() || !second.isUsable()) throw new IOException("Invalid 640×480 stereo calibration");
        return new CameraCalibration[] {first, second};
    }

    static ImuCalibration locatorImu(byte[] data) throws IOException {
        return imu(data, LOCATOR_IMU);
    }

    static HandCalibration hand(byte[] data) throws IOException {
        ImuCalibration imu = imu(data, HAND_IMU);
        if (imu.gyroHz < 11 || imu.accelHz < 11) throw new IOException("Invalid hand IMU rate");
        return new HandCalibration(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN), imu);
    }

    private static ImuCalibration imu(byte[] data, int offset) throws IOException {
        if (data.length < offset + IMU_FIELDS_BYTES) throw new IOException("Truncated IMU calibration");
        ByteBuffer fields = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        fields.position(offset);
        ImuCalibration imu = new ImuCalibration(fields);
        if (!(Float.isFinite(imu.gyroHz) && imu.gyroHz > 0 && Float.isFinite(imu.accelHz) &&
                imu.accelHz > 0 && Double.isFinite(imu.gyroNoiseSigma) && Double.isFinite(imu.accelNoiseSigma) &&
                Double.isFinite(imu.gyroBiasSigma) && Double.isFinite(imu.accelBiasSigma))) {
            throw new IOException("Invalid IMU calibration fields");
        }
        return imu;
    }

    private static double[] readDoubles(ByteBuffer fields, int count) throws IOException {
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = fields.getDouble();
            if (!Double.isFinite(values[i])) throw new IOException("Invalid non-finite calibration coefficient");
        }
        return values;
    }

    static Calibration read(Link link, Component component) throws IOException, InterruptedException {
        int dialect = ANDROID;
        byte[] hash = exchange(link, component, dialect, HASH, 0);
        if (hash == null) {
            dialect = WINDOWS;
            hash = exchange(link, component, dialect, HASH, 0);
        }
        if (hash == null) return null;

        int hashLength = dialect == WINDOWS ? 33 : unsigned(hash[0x2f]);
        byte[] deviceHash = Arrays.copyOfRange(hash, 0x31, 0x31 + hashLength);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int chunkCount = 1;
        for (int index = 1; index <= chunkCount; index++) {
            byte[] reply = exchange(link, component, dialect, CHUNK, index);
            if (reply == null) {
                throw new IOException(component.filename + " calibration chunk " + index + " timed out");
            }
            if (index == 1) {
                chunkCount = dialect == WINDOWS ? component.windowsChunks : unsigned(reply[0x31]);
                if (chunkCount < 1 || chunkCount > MAX_CHUNKS) {
                    throw new IOException(component.filename + " calibration has an invalid chunk count: " + chunkCount);
                }
            }
            int length = payloadLength(reply, dialect) - 3;
            if (data.size() + length > MAX_CALIBRATION_BYTES) {
                throw new IOException(component.filename + " calibration exceeds its buffer");
            }
            data.write(reply, 0x33, length);
        }
        if (dialect == WINDOWS && data.size() != component.windowsBytes) {
            throw new IOException(component.filename + " calibration has an unexpected size: " + data.size());
        }
        return new Calibration(deviceHash, data.toByteArray(), dialect);
    }

    private static byte[] exchange(Link link, Component component, int dialect, int command, int sequence)
            throws IOException, InterruptedException {
        checkInterrupted();
        byte[] request = new byte[64];
        request[0] = 2;
        request[1] = (byte) component.selector;
        request[2] = (byte) 0x80;
        request[4] = (byte) dialect;
        request[5] = (byte) command;
        request[6] = (byte) sequence;
        link.write(request);
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REPLY_TIMEOUT_MS);
        for (long left = end - System.nanoTime(); left > 0; left = end - System.nanoTime()) {
            checkInterrupted();
            byte[] report = link.read((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
            if (report == null) return null;
            if (matches(report, component, dialect, command, sequence)) return report;
        }
        return null;
    }

    private static boolean matches(byte[] report, Component component, int dialect, int command, int sequence) {
        if (report.length < 0x31 || unsigned(report[0x30]) != command ||
                unsigned(report[0x2d]) != component.selector) return false;
        if (command == HASH) {
            int length = dialect == WINDOWS ? 33 : unsigned(report[0x2f]);
            if (length == 0 || length > report.length - 0x31) return false;
            for (int i = 0x31; i < 0x31 + length; i++) {
                if (report[i] != 0) return true;
            }
            return false;
        }
        if (report.length < 0x33 || unsigned(report[0x32]) != sequence ||
                (dialect == ANDROID && unsigned(report[0x2c]) != 0x80)) return false;
        int length = payloadLength(report, dialect);
        return length > 3 && length - 3 <= report.length - 0x33;
    }

    private static int payloadLength(byte[] report, int dialect) {
        return (dialect == WINDOWS ? unsigned(report[0x2e]) << 8 : 0) | unsigned(report[0x2f]);
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    private Cv10KitCalibration() {}
}
