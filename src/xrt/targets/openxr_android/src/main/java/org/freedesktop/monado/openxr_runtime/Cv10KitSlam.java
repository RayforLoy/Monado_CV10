// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

/** Headset SLAM ABI for the original ARM64 SDK module. */
final class Cv10KitSlam implements AutoCloseable {
    private static final int PARAMETER_BYTES = 0x840;
    private static final long SDK_MODULE_LIMIT = 16L * 1024 * 1024;
    private static final String SDK_MODULE = "libarvr_slam.so";
    private static final String SDK_SHA256 =
            "2268ff5e2b26e67da395af6c57980f15f23e6199c4cb942d5fb959b3eb9b46a9";

    private long session;

    static File module(Context context) {
        return new File(context.getFilesDir(), SDK_MODULE);
    }

    static void importModule(Context context, Uri source) throws IOException {
        File destination = module(context);
        File staging = File.createTempFile("cv10-slam-", ".tmp", context.getFilesDir());
        try {
            MessageDigest sha = sha256();
            try (InputStream input = context.getContentResolver().openInputStream(source);
                    FileOutputStream output = new FileOutputStream(staging)) {
                if (input == null) throw new IOException("Cannot read the selected file.");
                if (!staging.setReadOnly()) throw new IOException("Could not mark the temporary module read-only.");
                updateDigest(input, output, sha);
                output.getFD().sync();
            }
            if (!SDK_SHA256.equals(hex(sha.digest()))) {
                throw new IOException("The selected file does not match the supported Huawei ARM64 module.");
            }
            // Staging and destination share a directory. renameTo replaces the old file atomically on Android.
            if (!staging.renameTo(destination)) throw new IOException("Could not store the verified Huawei module.");
        } finally {
            staging.delete();
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static void updateDigest(InputStream input, FileOutputStream output, MessageDigest sha)
            throws IOException {
        byte[] buffer = new byte[65536];
        long size = 0;
        int length;
        while ((length = input.read(buffer)) != -1) {
            size += length;
            if (size > SDK_MODULE_LIMIT) throw new IOException("The Huawei module exceeds 16 MiB.");
            if (output != null) output.write(buffer, 0, length);
            sha.update(buffer, 0, length);
        }
    }

    static byte[] readParameters(InputStream input) throws IOException {
        if (input == null) throw new IOException("Cannot open the SLAM parameters.");
        byte[] data = new byte[PARAMETER_BYTES];
        int used = 0;
        while (used < data.length) {
            int read = input.read(data, used, data.length - used);
            if (read <= 0) break;
            used += read;
        }
        if (used != data.length || input.read() != -1) {
            throw new IOException("slam-params.bin must be 2112 bytes.");
        }
        return data;
    }

    Cv10KitSlam(File module, byte[] parameters) throws IOException {
        if (!Process.is64Bit() || !Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a")) {
            throw new IOException("An arm64-v8a process is required for the Huawei SLAM module.");
        }
        if (!module.isFile()) throw new IOException("Import the original ARM64 SLAM module first.");
        MessageDigest sha = sha256();
        try (InputStream input = new FileInputStream(module)) {
            updateDigest(input, null, sha);
        }
        if (!SDK_SHA256.equals(hex(sha.digest()))) throw new IOException("The stored Huawei module failed verification.");
        try {
            System.loadLibrary("cv10-kit-slam");
            session = nativeOpen(module.getAbsolutePath(), parameters);
        } catch (UnsatisfiedLinkError unavailable) {
            throw new IOException(unavailable.getMessage(), unavailable);
        }
    }

    static String replayBlankFrames(Context context, byte[] parameters) throws IOException, InterruptedException {
        try (Cv10KitSlam slam = new Cv10KitSlam(module(context), parameters)) {
            long first = 1_000_000_000L;
            for (int sample = 0; sample < 3; sample++) {
                check("Head IMU", nativeImu(slam.session, first + sample * 5_000_000L, 0, 0, 9.8f, 0, 0, 0));
            }
            byte[] blank = new byte[Cv10KitReport.CAMERA_WIDTH * Cv10KitReport.CAMERA_HEIGHT];
            for (int frame = 0; frame < 2; frame++) {
                check("Y8 stereo", nativeStereo(slam.session, first + 10_000_000L + frame * 66_666_666L,
                        frame + 1, 4000, true, blank, blank));
            }
            Thread.sleep(100);
            return "SLAM accepted 6 IMU records and 2 blank stereo frames.\n" + slam.poseStatus();
        }
    }

    void push(Cv10KitReport.Head head) throws IOException {
        check("Head IMU", nativeImu(session, head.deviceNs, head.ax, head.ay, head.az, head.gx, head.gy, head.gz));
    }

    void push(Cv10KitReport.StereoFrame stereo) throws IOException {
        Cv10KitReport.Frame frame = stereo.frame;
        check("Y8 stereo", nativeStereo(session, frame.deviceNs, (int) frame.frameNumber, frame.exposureUs,
                frame.frameMarker == 0, stereo.camera0, stereo.camera1));
    }

    Cv10KitReport.HeadPose readPose(byte[] pose) {
        if (nativePose(session, pose) != 0) return null;
        Cv10KitReport.HeadPose head = Cv10KitReport.headPose(pose);
        return head != null && head.valid() ? head : null;
    }

    String poseStatus() {
        byte[] pose = new byte[Cv10KitReport.HeadPose.BYTES];
        int status = nativePose(session, pose);
        if (status == -1 && Arrays.equals(pose, new byte[pose.length])) {
            return "No valid visual pose (query returned -1).";
        }
        Cv10KitReport.HeadPose head = Cv10KitReport.headPose(pose);
        Cv10KitReport.HmdPose hmd = status == 0 ? head.toHmd() : null;
        if (hmd == null) return "Pose query returned " + status + " · " + hex(pose);
        return String.format(Locale.ROOT,
                "SLAM pose at %.6f s · state %d/%d\n"
                + "Position (%.4f, %.4f, %.4f) · wxyz (%.4f, %.4f, %.4f, %.4f)\n"
                + "HMD position (%.4f, %.4f, %.4f) · xyzw (%.4f, %.4f, %.4f, %.4f)",
                head.deviceSeconds, head.state0, head.state1, head.x, head.y, head.z, head.qw, head.qx, head.qy,
                head.qz, hmd.x, hmd.y, hmd.z, hmd.qx, hmd.qy, hmd.qz, hmd.qw);
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return text.toString();
    }

    private static void check(String operation, int result) throws IOException {
        if (result != 0) throw new IOException(operation + " returned " + result);
    }

    @Override
    public void close() throws IOException {
        long active = session;
        session = 0;
        if (active != 0) check("SLAM stop/deinit", nativeClose(active));
    }

    private static native long nativeOpen(String library, byte[] parameters) throws IOException;
    private static native int nativeImu(long session, long ns, float ax, float ay, float az, float gx, float gy,
            float gz);
    private static native int nativeStereo(long session, long ns, int frame, int exposureUs, boolean aFrame,
            byte[] left, byte[] right);
    private static native int nativePose(long session, byte[] destination);
    private static native int nativeClose(long session);
}
