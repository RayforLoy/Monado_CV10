// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Applies the locator and controller calibrations to the OEM hand model. */
final class Cv10KitHandParameters {
    static final int SIZE = 0x1c38;
    private static final int HAND_STRIDE = 0xc18;
    private static final int CAMERA_BLOCK = 0x1868;
    private static final int HEAD_IMU = 0x1bb0;

    static byte[] encode(byte[] template, byte[] headParameters, Cv10KitCalibration.CameraCalibration firstCamera,
            Cv10KitCalibration.ImuCalibration head, Cv10KitCalibration.HandCalibration left,
            Cv10KitCalibration.HandCalibration right) throws IOException {
        if (template.length != SIZE || headParameters.length != Cv10KitSlamParameters.SIZE) {
            throw new IOException("Invalid hand or head configuration size.");
        }
        ByteBuffer params = ByteBuffer.wrap(template.clone()).order(ByteOrder.LITTLE_ENDIAN);
        putHand(params, 0, left);
        putHand(params, HAND_STRIDE, right);
        // The stereo and locator IMU calibration layouts match the head SLAM module.
        System.arraycopy(headParameters, 0x08, params.array(), CAMERA_BLOCK, 0x260);
        System.arraycopy(headParameters, 0x570, params.array(), HEAD_IMU, 0x88);
        params.putFloat(0x1b14, (int) head.accelHz);
        params.putFloat(0x1b18, (int) head.gyroHz);
        params.putDouble(0x1b20, firstCamera.cameraToImuDelaySeconds);
        params.putDouble(0x1b28, firstCamera.exposureShiftSeconds);
        return params.array();
    }

    private static void putHand(ByteBuffer params, int base, Cv10KitCalibration.HandCalibration hand)
            throws IOException {
        Cv10KitCalibration.ImuCalibration imu = hand.imu;
        params.putFloat(base + 0x7c, (int) imu.accelHz);
        params.putFloat(base + 0x80, (int) imu.gyroHz);
        params.putDouble(base + 0x88, hand.cameraToImuDelaySeconds);
        for (int i = 0; i < imu.gyroToAccel.length; i++) {
            params.putDouble(base + 0x98 + i * Double.BYTES, imu.gyroToAccel[i]);
        }
        params.putInt(base + 0x118, imu.needsCompensation ? 1 : 0);
        putSensor(params, base + 0x11c, imu.accelScale, imu.accelMisalignment, imu.accelBias);
        putSensor(params, base + 0x14c, imu.gyroScale, imu.gyroMisalignment, imu.gyroBias);
        params.putDouble(base + 0x180, imu.gyroNoiseSigma);
        params.putDouble(base + 0x188, imu.gyroBiasSigma);
        params.putDouble(base + 0x190, imu.accelNoiseSigma);
        params.putDouble(base + 0x198, imu.accelBiasSigma);
        for (int i = 0; i < hand.ledPositions.length; i++) {
            // Preserve the OEM model's LED indices, normals and bounding box.
            putFloats(params, base + 0x1a8 + i * 0x1c, hand.ledPositions[i]);
        }
        for (int i = 0; i < hand.imuToHandle.length; i++) {
            params.putDouble(base + 0x810 + i * Double.BYTES, hand.imuToHandle[i]);
        }
    }

    private static void putSensor(ByteBuffer params, int offset, double[] scale, double[] misalignment, double[] bias)
            throws IOException {
        // The hand IMU storage holds the unscaled misalignment and the raw bias.
        putFloats(params, offset, new double[] {scale[0], misalignment[0], misalignment[1],
                0, scale[1], misalignment[2], 0, 0, scale[2], bias[0], bias[1], bias[2]});
    }

    private static void putFloats(ByteBuffer params, int offset, double[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            float value = (float) values[i];
            if (!Float.isFinite(value)) throw new IOException("Non-finite hand calibration coefficient.");
            params.putFloat(offset + i * Float.BYTES, value);
        }
    }

    private Cv10KitHandParameters() {}
}
