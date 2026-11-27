// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Encodes the head SLAM module's HSDK_CONFIG_MANAGER_IMPL_GET_SLAM_PARAMS payload. */
final class Cv10KitSlamParameters {
    static final int SIZE = 0x840;
    private static final int CAMERA_SIZE = 0x130;
    private static final int[] DISTORTION_ORDER = {0, 1, 2, 5, 6, 7, 3, 4};

    static byte[] encode(Cv10KitCalibration.CameraCalibration[] cameras, Cv10KitCalibration.ImuCalibration imu)
            throws IOException {
        ByteBuffer parameters = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        parameters.putInt(0, cameras.length);
        for (int i = 0; i < cameras.length; i++) {
            putCamera(parameters, 0x08 + i * CAMERA_SIZE, cameras[i]);
        }
        parameters.putInt(0x4c8, 1);
        // The Android wrapper submits a nominal 1000 Hz for both sensors.
        parameters.putFloat(0x4d4, 1000);
        parameters.putFloat(0x4d8, 1000);
        parameters.putDouble(0x4e0, cameras[0].cameraToImuDelaySeconds);
        parameters.putDouble(0x4e8, cameras[0].exposureShiftSeconds);
        for (int diagonal = 0; diagonal < 4; diagonal++) {
            parameters.putDouble(0x4f0 + diagonal * 0x28, 1);
        }

        parameters.putInt(0x570, imu.needsCompensation ? 1 : 0);
        double[] accel = scaleMatrix(imu.accelScale, imu.accelMisalignment);
        double[] accelToGyro = inverseRotation(imu.gyroToAccel);
        double[] gyro = scaleMatrix(imu.gyroScale, imu.gyroMisalignment);
        putFloats(parameters, 0x574, multiply(accelToGyro, accel));
        putFloats(parameters, 0x598, transformedBias(accel, imu.accelBias));
        putFloats(parameters, 0x5a4, gyro);
        putFloats(parameters, 0x5c8, transformedBias(gyro, imu.gyroBias));
        parameters.putDouble(0x5d8, imu.gyroNoiseSigma);
        parameters.putDouble(0x5e0, imu.gyroBiasSigma);
        parameters.putDouble(0x5e8, imu.accelNoiseSigma);
        parameters.putDouble(0x5f0, imu.accelBiasSigma);

        // Common settings for the stock two-camera CV10 profile.
        for (int i = 0; i < 5; i++) parameters.putInt(0x73c + i * 4, 1);
        parameters.putInt(0x760, 2);
        parameters.position(0x764);
        parameters.put("st".getBytes(StandardCharsets.US_ASCII));
        parameters.position(0x7f0);
        parameters.put("20".getBytes(StandardCharsets.US_ASCII));
        parameters.putInt(0x830, 3);
        return parameters.array();
    }

    private static void putCamera(ByteBuffer parameters, int start, Cv10KitCalibration.CameraCalibration camera)
            throws IOException {
        if (camera.distortionMode > 1 || quaternionNormSquared(camera.tbc) == 0) {
            throw new IOException("Unsupported SLAM camera model");
        }
        parameters.putInt(start, camera.id);
        parameters.putInt(start + 0x04, 1);
        parameters.putInt(start + 0x08, camera.width);
        parameters.putInt(start + 0x0c, camera.height);
        parameters.putInt(start + 0x10, camera.width);
        parameters.putFloat(start + 0x14, (int) camera.framesPerSecond);
        for (int i = 0; i < 4; i++) {
            if (!Double.isFinite(camera.autofocusCompensation[i])) {
                throw new IOException("Non-finite camera autofocus calibration");
            }
            parameters.putDouble(start + 0x18 + i * 8, camera.autofocusCompensation[i]);
            parameters.putDouble(start + 0x40 + i * 8, camera.intrinsics[i]);
        }
        parameters.putInt(start + 0x60, camera.needsUndistortion ? 1 : 0);
        parameters.putInt(start + 0x64, camera.distortionMode);
        for (int i = 0; i < DISTORTION_ORDER.length; i++) {
            parameters.putDouble(start + 0x68 + i * 8, camera.distortion[DISTORTION_ORDER[i]]);
        }
        double[] rotation = rotation(camera.tbc);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                parameters.putDouble(start + 0xa8 + row * 0x20 + column * 8, rotation[row * 3 + column]);
            }
            parameters.putDouble(start + 0xc0 + row * 0x20, camera.tbc[4 + row]);
        }
        parameters.putDouble(start + 0x120, 1);
        parameters.putDouble(start + 0x128, camera.readoutSeconds);
    }

    private static double quaternionNormSquared(double[] q) {
        return q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3];
    }

    private static double[] rotation(double[] q) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        return new double[] {
                w * w + x * x - y * y - z * z, 2 * (x * y - w * z), 2 * (x * z + w * y),
                2 * (x * y + w * z), w * w - x * x + y * y - z * z, 2 * (y * z - w * x),
                2 * (x * z - w * y), 2 * (y * z + w * x), w * w - x * x - y * y + z * z
        };
    }

    private static double[] inverseRotation(double[] q) throws IOException {
        double norm = quaternionNormSquared(q);
        if (norm == 0 || !Double.isFinite(norm)) throw new IOException("Invalid gyroscope to accelerometer rotation");
        return rotation(new double[] {-q[0] / norm, -q[1] / norm, -q[2] / norm, q[3] / norm});
    }

    private static double[] scaleMatrix(double[] scale, double[] misalignment) {
        return new double[] {
                scale[0], scale[0] * misalignment[0], scale[0] * misalignment[1],
                0, scale[1], scale[1] * misalignment[2],
                0, 0, scale[2]
        };
    }

    private static double[] multiply(double[] left, double[] right) {
        double[] product = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                for (int k = 0; k < 3; k++) {
                    product[row * 3 + column] += left[row * 3 + k] * right[k * 3 + column];
                }
            }
        }
        return product;
    }

    private static double[] transformedBias(double[] scale, double[] bias) throws IOException {
        if (scale[0] == 0 || scale[4] == 0 || scale[8] == 0) throw new IOException("Singular IMU scale calibration");
        // The wrapper applies the inverse scale matrix to the negated bias.
        double z = -bias[2] / scale[8];
        double y = (-bias[1] - scale[5] * z) / scale[4];
        double x = (-bias[0] - scale[1] * y - scale[2] * z) / scale[0];
        return new double[] {x, y, z};
    }

    private static void putFloats(ByteBuffer parameters, int start, double[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            float value = (float) values[i];
            if (!Float.isFinite(value)) throw new IOException("Non-finite SLAM IMU calibration");
            parameters.putFloat(start + 4 * i, value);
        }
    }

    private Cv10KitSlamParameters() {}
}
