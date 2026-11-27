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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** App-private copy of the optional Huawei optical hand tracker and its ABI. */
final class Cv10KitHands implements AutoCloseable {
    private static final String CONFIG = "hand-config.bin";
    private static final String CONFIG_HASH = "75de7b935b0ac46b9cd32542aaaf62df806ef129b3146356cce11617ae8bdec3";
    private static final String MODEL = "SDmmm1_fp16_480_640.om";
    private static final long APK_LIMIT = 512L * 1024 * 1024;
    private static final String[][] ASSETS = {
        {"libc++_shared.so", "d5d0588bbc3c1c8f25b890ba6a97694dc8fe3d4dce7fbc5204107c1342034282"},
        {"libhiai_ir.so", "7210c9702755cc31f4c37b565915ba27838b7afbf2ea8c051a144bb45d50ba37"},
        {"libhiai.so", "5596e3b365a92bc0d837b33cb95a18a9487106b5056c784f77209e6917ab6d25"},
        {"libhcl.so", "0a1c66da42f48bbede3a5f63f4db5c89687dec9916199e088ef405fd77d1a5dc"},
        {"libhiai_hcl_model_runtime.so", "3adb6242e85ab575fe3bf91e0284b1f705af099e6963d8e64a2a015667cb8103"},
        {"libVR6Dof.so", "92020423ca33b2bf7aa147db64dfc0449aaac926d8273c6f7d595440fea49aee"},
        {MODEL, "46977ccfdc5ad935e30d42962b79cbd252b76bd3856dfd9c6151ff062f7f7aeb"},
    };

    private long session;

    private static File directory(Context context) { return new File(context.getFilesDir(), "cv10-6dof-hand-sdk"); }

    static boolean configured(Context context) {
        File folder = directory(context);
        if (!new File(folder, CONFIG).isFile()) return false;
        for (String[] asset : ASSETS) {
            if (!new File(folder, asset[0]).isFile()) return false;
        }
        return true;
    }

    static void importSdk(Context context, Uri source) throws IOException {
        File folder = directory(context);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not create the SDK directory.");
        File stagedApk = File.createTempFile("cv10-hand-sdk-", ".apk", context.getCacheDir());
        try {
            try (InputStream input = context.getContentResolver().openInputStream(source);
                    FileOutputStream output = new FileOutputStream(stagedApk)) {
                if (input == null) throw new IOException("Cannot open the original Huawei APK.");
                byte[] buffer = new byte[65536];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > APK_LIMIT) throw new IOException("Huawei APK exceeds 512 MiB.");
                    output.write(buffer, 0, count);
                }
            }
            try (ZipFile apk = new ZipFile(stagedApk)) {
                for (String[] asset : ASSETS) {
                    String entry = (asset[0].equals(MODEL) ? "assets/" : "assets/arm64-v8a/") + asset[0];
                    ZipEntry file = apk.getEntry(entry);
                    if (file == null || file.isDirectory()) throw new IOException("Original APK is missing " + entry);
                    try (InputStream input = apk.getInputStream(file)) {
                        install(input, folder, asset[0], asset[1]);
                    }
                }
            }
        } finally {
            stagedApk.delete();
        }
    }

    static void importConfig(Context context, Uri source) throws IOException {
        File folder = directory(context);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not create the SDK directory.");
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IOException("Cannot read the hand tracking configuration.");
            install(input, folder, CONFIG, CONFIG_HASH);
        }
    }

    private static void install(InputStream input, File folder, String name, String expected) throws IOException {
        File staged = File.createTempFile("cv10-import-", ".tmp", folder);
        try {
            MessageDigest sha = Cv10KitSlam.sha256();
            try (FileOutputStream output = new FileOutputStream(staged)) {
                if (!staged.setReadOnly()) throw new IOException("Could not mark " + name + " read-only.");
                Cv10KitSlam.updateDigest(input, output, sha);
                output.getFD().sync();
            }
            if (!expected.equalsIgnoreCase(Cv10KitSlam.hex(sha.digest()))) {
                throw new IOException("Unsupported Huawei data for " + name + ".");
            }
            if (!staged.renameTo(new File(folder, name))) throw new IOException("Could not store " + name + ".");
        } finally {
            staged.delete();
        }
    }

    private static void verify(File file, String expected) throws IOException {
        MessageDigest sha = Cv10KitSlam.sha256();
        try (FileInputStream input = new FileInputStream(file)) {
            Cv10KitSlam.updateDigest(input, null, sha);
        }
        if (!expected.equalsIgnoreCase(Cv10KitSlam.hex(sha.digest()))) {
            throw new IOException("Stored Huawei data failed verification for " + file.getName() + ".");
        }
    }

    static byte[] template(Context context) throws IOException {
        File config = new File(directory(context), CONFIG);
        verify(config, CONFIG_HASH);
        return Files.readAllBytes(config.toPath());
    }

    Cv10KitHands(Context context, byte[] parameters) throws IOException {
        if (!Process.is64Bit() || !Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a")) {
            throw new IOException("An arm64-v8a process is required for Huawei hand tracking.");
        }
        File folder = directory(context);
        for (String[] asset : ASSETS) verify(new File(folder, asset[0]), asset[1]);
        if (parameters.length != Cv10KitHandParameters.SIZE) throw new IOException("Hand configuration must be 7224 bytes.");
        try {
            System.loadLibrary("cv10-kit-slam");
            session = nativeOpen(folder.getAbsolutePath(), new File(folder, MODEL).getAbsolutePath(), parameters);
        } catch (UnsatisfiedLinkError failure) {
            throw new IOException(failure.getMessage(), failure);
        }
    }

    void push(Cv10KitReport.Hand hand, boolean right) throws IOException {
        check("Hand IMU", nativeImu(session, right, hand.deviceNs, hand.ax, hand.ay, hand.az,
                hand.gx, hand.gy, hand.gz));
    }

    boolean push(Cv10KitReport.StereoFrame image) throws IOException {
        Cv10KitReport.Frame frame = image.frame;
        int result = nativeStereo(session, frame.deviceNs, (int) frame.frameNumber, frame.exposureUs,
                frame.frameMarker == 0, image.camera0, image.camera1, null);
        if (result == 1) return false;
        check("Hand stereo", result);
        return true;
    }

    int poses(long deviceNs, byte[] out) { return nativePoses(session, deviceNs, out); }

    static String replay(Context context) throws IOException, InterruptedException {
        try (Cv10KitHands hands = new Cv10KitHands(context, template(context))) {
            for (long ns = 1_000_000_000L; ns < 1_176_000_000L; ns += 4_000_000L) {
                check("Left hand IMU", nativeImu(hands.session, false, ns, 0, 0, 9.8f, 0, 0, 0));
                check("Right hand IMU", nativeImu(hands.session, true, ns, 0, 0, 9.8f, 0, 0, 0));
            }
            byte[] blank = new byte[Cv10KitReport.CAMERA_WIDTH * Cv10KitReport.CAMERA_HEIGHT];
            long ns = 1_086_666_666L;
            for (int index = 1; index <= 2; index++, ns += 66_666_666L) {
                ByteBuffer head = ByteBuffer.allocate(Cv10KitReport.HeadPose.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                head.putDouble(ns / 1e9).putInt(1).putInt(1).putFloat(0.125f).putFloat(1.25f).putFloat(-0.25f)
                        .putFloat(1).put(0x30, (byte) 1);
                check("Hand stereo", nativeStereo(hands.session, ns, index, 4000, true, blank, blank, head.array()));
            }
            byte[] out = new byte[2 * Cv10KitReport.HAND_POSE_BYTES];
            long deadline = System.nanoTime() + 1_000_000_000L;
            int status;
            do {
                Thread.sleep(25);
                status = hands.poses(ns - 66_666_666L, out);
            } while (status == -1 && System.nanoTime() < deadline);
            ByteBuffer states = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
            return "Sent 88 hand IMU records and two blank stereo frames.\nHand pose query " + status
                    + (status == 0 ? " · left state " + states.getInt(4) + " · right state "
                    + states.getInt(Cv10KitReport.HAND_POSE_BYTES + 4) : "");
        }
    }

    private static void check(String operation, int result) throws IOException {
        if (result != 0) throw new IOException(operation + " returned " + result);
    }

    @Override
    public void close() throws IOException {
        long handle = session;
        session = 0;
        if (handle != 0) check("Hand tracking stop/deinit", nativeClose(handle));
    }

    private static native long nativeOpen(String directory, String model, byte[] parameters) throws IOException;
    private static native int nativeImu(long session, boolean right, long ns, float ax, float ay, float az,
            float gx, float gy, float gz);
    private static native int nativeStereo(long session, long ns, int frame, int exposureUs, boolean aFrame,
            byte[] left, byte[] right, byte[] suppliedPose);
    private static native int nativePoses(long session, long deviceNs, byte[] out);
    private static native int nativeClose(long session);
}
