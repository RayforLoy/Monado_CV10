// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import androidx.annotation.Keep;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Only the Fold7 owner's minimum refresh rate can be changed by this service. */
@Keep
public final class Cv10RefreshUserService extends ICv10Refresh.Stub {
    private static final String RATE = "min_refresh_rate";
    private static final String TARGET = "120.0";
    private IBinder owner;
    private String original;
    private final IBinder.DeathRecipient died = this::finish;

    @Keep public Cv10RefreshUserService() {}

    private String settings(String verb, String value) {
        try {
            if (Process.myUid() != 2000) throw new IllegalStateException("Wireless-debugging Shizuku required");
            ArrayList<String> args = new ArrayList<>(Arrays.asList(
                    "/system/bin/cmd", "settings", "--user", "0", verb, "system", RATE));
            if (value != null) args.add(value);
            java.lang.Process child = new ProcessBuilder(args).redirectErrorStream(true).start();
            try {
                if (!child.waitFor(2, TimeUnit.SECONDS)) throw new IllegalStateException("settings timed out");
                try (BufferedReader r = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                    String line = r.readLine();
                    if (child.exitValue() != 0 || (line != null && line.contains("Exception")))
                        throw new IllegalStateException("settings " + verb + " failed");
                    return line;
                }
            } finally {
                child.destroyForcibly();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Refresh setting unavailable", e);
        }
    }

    private String read() {
        String value = settings("get", null);
        return "null".equals(value) ? null : value;
    }

    private boolean valid(String value) {
        if (value == null) return true;
        if (value.length() > 32 || !value.matches("[+]?[0-9]+(\\.[0-9]+)?")) return false;
        double hz = Double.parseDouble(value);
        return hz >= 0 && hz <= 1000;
    }

    @Override public synchronized boolean acquire(String before, IBinder token) throws RemoteException {
        if (token == null || owner != null || !valid(before) || TARGET.equals(before)
                || !Objects.equals(before, read())) return false;
        token.linkToDeath(died, 0);
        owner = token;
        original = before;
        settings("put", TARGET);
        return TARGET.equals(read());
    }

    @Override public synchronized boolean restore(String journal) {
        if (!valid(journal) || (owner != null && !Objects.equals(journal, original))) return false;
        if (TARGET.equals(read())) {
            settings(journal == null ? "delete" : "put", journal);
            if (!Objects.equals(journal, read())) return false;
        }
        relinquish();
        return true;
    }

    @Override public synchronized boolean setPersistent(String expectedOriginal) {
        if (owner != null || !valid(expectedOriginal) || !Objects.equals(expectedOriginal, read())) return false;
        settings("put", TARGET);
        return TARGET.equals(read());
    }

    @Override public synchronized void relinquish() {
        if (owner != null) owner.unlinkToDeath(died, 0);
        owner = null;
        original = null;
    }

    private synchronized void finish() {
        try {
            if (owner != null) restore(original);
        } catch (RuntimeException e) {
            Log.w("Cv10RefreshLease", "UserService exit; runtime journal retained", e);
        }
    }

    @Override public void destroy() {
        finish();
        System.exit(0);
    }
}
