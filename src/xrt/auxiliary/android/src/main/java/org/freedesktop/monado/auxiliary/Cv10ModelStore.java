// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.AtomicFile;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Private copy of the locally extracted, grip-aligned CF20 asset. */
public final class Cv10ModelStore {
    private static final String SHA256 = "76e976932c96b7ebc3f844b773e8476dd16777d29e8a5d2a447f332a1a141087";
    private static final int LENGTH = 182180;

    private static File file(Context context) {
        return new File(context.getNoBackupFilesDir(), "cf20-aligned.glb");
    }

    public static synchronized void importModel(Context context, Uri uri) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        AtomicFile target = new AtomicFile(file(context));
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法打开模型文件");
            FileOutputStream out = target.startWrite();
            try {
                byte[] buffer = new byte[8192];
                int total = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > LENGTH) throw new IOException("模型长度与已校准的 CF20 不符");
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                if (total != LENGTH || !SHA256.contentEquals(hex)) throw new IOException("模型与已校准版本的 SHA-256 不符");
                target.finishWrite(out);
            } catch (IOException | RuntimeException e) {
                target.failWrite(out);
                throw e;
            }
        }
    }

    public static synchronized ParcelFileDescriptor openReadOnly(Context context) {
        File model = file(context);
        if (model.length() != LENGTH) return null;
        try {
            return ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    private Cv10ModelStore() {}
}
