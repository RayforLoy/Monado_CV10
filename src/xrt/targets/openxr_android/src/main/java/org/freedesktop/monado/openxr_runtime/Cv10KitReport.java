// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** OV580 records accepted by the Huawei runtime. The physical USB framing is device-dependent. */
final class Cv10KitReport {
    static final int VENDOR_ID = 0x12d1;
    static final int PRODUCT_ID = 0x3a09;
    static final int HID_INTERFACE = 2;
    static final int HID_REPORT_SIZE = 128;
    static final int FRAME_WIDTH = 1280;
    static final int FRAME_HEIGHT = 481;
    static final int FRAME_FORMAT = 0x20203859; // Y8
    static final int CAMERA_WIDTH = FRAME_WIDTH / 2;
    static final int CAMERA_HEIGHT = FRAME_HEIGHT - 1;
    static final int HAND_POSE_BYTES = 0x54;
    private static final int HAND_RECORD = 0x31;
    private static final int HAND_STRIDE = 0x24;
    private static final float HAND_ACCEL = 0x1.39d016p-8f;
    private static final float HAND_GYRO = 0x1.1740a8p-9f;

    static final class Head {
        final long deviceNs;
        final float ax, ay, az, gx, gy, gz;

        private Head(ByteBuffer data) {
            deviceNs = data.getLong(12);
            ax = data.getShort(23) * 0.000244f * -9.8f;
            ay = data.getShort(21) * 0.000244f * 9.8f;
            az = data.getShort(25) * 0.000244f * 9.8f;
            gx = data.getShort(30) * 0.07f * (float) -Math.PI / 180f;
            gy = data.getShort(28) * 0.07f * (float) Math.PI / 180f;
            gz = data.getShort(32) * 0.07f * (float) Math.PI / 180f;
        }
    }

    static final class Hand {
        static final int CONFIRM = 0x20;
        static final int BACK = 0x10;
        static final int HOME = 0x02;
        static final int STICK_CLICK = 0x08;
        final long clockTicks;
        final long deviceNs;
        final int clockMultiplier, buttons, touches, gripTouch, batteryField;
        final float ax, ay, az, gx, gy, gz, stickX, stickY, trigger, grip;
        final int mx, my, mz;

        private Hand(ByteBuffer data, int start) {
            buttons = u8(data, start);
            clockTicks = Integer.toUnsignedLong(Integer.reverseBytes(data.getInt(start + 1)));
            clockMultiplier = u8(data, start + 0x17);
            float clockSeconds = clockTicks / 1_000_000f;
            if (clockMultiplier != 0) clockSeconds *= clockMultiplier;
            deviceNs = (long) ((double) clockSeconds * 1_000_000_000d);
            ax = data.getShort(start + 5) * HAND_ACCEL;
            ay = data.getShort(start + 7) * HAND_ACCEL;
            az = data.getShort(start + 9) * HAND_ACCEL;
            gx = data.getShort(start + 0x0b) * HAND_GYRO;
            gy = data.getShort(start + 0x0d) * HAND_GYRO;
            gz = data.getShort(start + 0x0f) * HAND_GYRO;
            mx = Short.toUnsignedInt(data.getShort(start + 0x11));
            my = Short.toUnsignedInt(data.getShort(start + 0x13));
            mz = Short.toUnsignedInt(data.getShort(start + 0x15));
            batteryField = u8(data, start + 0x18);
            trigger = 25f * data.getShort(start + 0x19);
            grip = 25f * data.getShort(start + 0x1b);
            stickX = (data.getShort(start + 0x1d) + 1499) / 3046f;
            stickY = 1f - (data.getShort(start + 0x1f) + 1642) / 3035f;
            touches = u8(data, start + 0x21);
            gripTouch = u8(data, start + 0x22) & 1;
        }
    }

    static final class Frame {
        final long deviceNs;
        final long frameNumber;
        final int exposureUs;
        final int frameMarker;

        private Frame(ByteBuffer metadata) {
            deviceNs = metadata.getLong(0);
            frameNumber = Integer.toUnsignedLong(metadata.getInt(0x12));
            exposureUs = Short.toUnsignedInt(metadata.getShort(0x16));
            frameMarker = u8(metadata, 0x1c);
        }
    }

    static final class StereoFrame {
        final Frame frame;
        final byte[] camera0 = new byte[CAMERA_WIDTH * CAMERA_HEIGHT];
        final byte[] camera1 = new byte[CAMERA_WIDTH * CAMERA_HEIGHT];
        final byte[] trailer;

        private StereoFrame(Frame frame, ByteBuffer plane, int rowStride) {
            this.frame = frame;
            ByteBuffer pixels = plane.duplicate();
            int start = plane.position();
            for (int row = 0; row < CAMERA_HEIGHT; row++) {
                pixels.position(start + row * rowStride);
                pixels.get(camera0, row * CAMERA_WIDTH, CAMERA_WIDTH);
                pixels.get(camera1, row * CAMERA_WIDTH, CAMERA_WIDTH);
            }
            pixels.position(start + CAMERA_HEIGHT * rowStride);
            trailer = new byte[Math.min(FRAME_WIDTH, pixels.remaining())];
            pixels.get(trailer);
        }
    }

    static final class HeadPose {
        static final int BYTES = 0x38;
        final double deviceSeconds;
        final int state0, state1;
        final float x, y, z, qw, qx, qy, qz;

        private HeadPose(ByteBuffer data) {
            deviceSeconds = data.getDouble(0);
            state0 = data.getInt(8);
            state1 = data.getInt(12);
            x = data.getFloat(0x10);
            y = data.getFloat(0x14);
            z = data.getFloat(0x18);
            qw = data.getFloat(0x1c);
            qx = data.getFloat(0x20);
            qy = data.getFloat(0x24);
            qz = data.getFloat(0x28);
        }

        boolean valid() {
            if (state0 == 0 || state1 == 0 || deviceSeconds <= 0 || !Double.isFinite(deviceSeconds)
                    || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) return false;
            float normSquared = qw * qw + qx * qx + qy * qy + qz * qz;
            return normSquared > 0.9f && normSquared < 1.1f;
        }

        HmdPose toHmd() {
            if (!valid()) return null;
            // Factory alignment from TransSlamCoord2Hmd. Apply the rotated lever arm in SLAM space first.
            float[] aligned = multiply(qx, qy, qz, qw, 0.211309f, 0.211309f, 0.0468461f, 0.953154f);
            float ax = aligned[0], ay = aligned[1], az = aligned[2], aw = aligned[3];
            float[] lever = multiply(ax, ay, az, aw, -0.059f, -0.029f, 0.018f, 0);
            float[] rotated = multiply(lever[0], lever[1], lever[2], lever[3], -ax, -ay, -az, aw);
            // coordToHMD maps (x, y, z) to (x, z, -y), then applies a local +90° rotation about X.
            float s = (float) Math.sqrt(0.5);
            float[] orientation = multiply(ax, az, -ay, aw, s, 0, 0, s);
            return new HmdPose(x - rotated[0], z - rotated[2], -y + rotated[1], orientation);
        }

        private static float[] multiply(float x, float y, float z, float w, float u, float v, float s, float t) {
            return new float[] {w * u + x * t + y * s - z * v, w * v - x * s + y * t + z * u,
                    w * s + x * v - y * u + z * t, w * t - x * u - y * v - z * s};
        }
    }

    static final class HmdPose {
        final float x, y, z, qx, qy, qz, qw;

        private HmdPose(float x, float y, float z, float[] q) {
            this.x = x;
            this.y = y;
            this.z = z;
            float inverseNorm = (float) (1 / Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]));
            qx = q[0] * inverseNorm;
            qy = q[1] * inverseNorm;
            qz = q[2] * inverseNorm;
            qw = q[3] * inverseNorm;
        }
    }

    static HeadPose headPose(byte[] data) {
        return data.length == HeadPose.BYTES ? new HeadPose(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)) : null;
    }

    // The OEM hand module reports HMD axes. The SDK only applies the user's yaw recenter.
    static HmdPose handPose(ByteBuffer data, int offset, boolean right) {
        if (offset < 0 || offset > data.limit() - HAND_POSE_BYTES ||
                data.get(offset) != (right ? '1' : '0') || data.getInt(offset + 4) == 255) return null;
        float x = data.getFloat(offset + 8), y = data.getFloat(offset + 12), z = data.getFloat(offset + 16);
        float qw = data.getFloat(offset + 20), qx = data.getFloat(offset + 24);
        float qy = data.getFloat(offset + 28), qz = data.getFloat(offset + 32);
        float norm = qw * qw + qx * qx + qy * qy + qz * qz;
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !(norm > 0.9f && norm < 1.1f)) {
            return null;
        }
        return new HmdPose(x, y, z, new float[] {qx, qy, qz, qw});
    }

    static Head head(byte[] report) {
        if (report.length < 34 || report[20] != 0 || report[27] != 1) return null;
        Head head = new Head(ByteBuffer.wrap(report).order(ByteOrder.BIG_ENDIAN));
        return head.deviceNs > 0 ? head : null;
    }

    static Hand hand(byte[] report, boolean right) {
        int start = HAND_RECORD + (right ? HAND_STRIDE : 0);
        if (report.length < start + 0x23 || report[0x30] != 0x20) return null;
        Hand hand = new Hand(ByteBuffer.wrap(report).order(ByteOrder.LITTLE_ENDIAN), start);
        return hand.clockTicks != 0 ? hand : null;
    }

    static Frame frame(ByteBuffer plane, int rowStride, int pixelStride) {
        if (rowStride < FRAME_WIDTH || pixelStride != 1 ||
                (long) rowStride * CAMERA_HEIGHT + 0x1d > plane.remaining()) return null;
        int metadataStart = plane.position() + CAMERA_HEIGHT * rowStride;
        ByteBuffer metadata = plane.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        metadata.position(metadataStart);
        Frame frame = new Frame(metadata.slice().order(ByteOrder.LITTLE_ENDIAN));
        return frame.deviceNs > 0 ? frame : null;
    }

    static StereoFrame stereo(ByteBuffer plane, int rowStride, int pixelStride) {
        Frame metadata = frame(plane, rowStride, pixelStride);
        return metadata == null ? null : new StereoFrame(metadata, plane, rowStride);
    }

    private static int u8(ByteBuffer buffer, int at) {
        return buffer.get(at) & 0xff;
    }

    private Cv10KitReport() {}
}
