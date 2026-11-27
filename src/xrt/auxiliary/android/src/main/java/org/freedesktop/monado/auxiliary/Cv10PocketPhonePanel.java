// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Temporarily turns off the default panel without putting the Pocket XR display group to sleep. */
final class Cv10PocketPhonePanel implements AutoCloseable {
    private static final String TAG = "Cv10PocketPhonePanel";
    private static final String SCRIPT = "trap 'cmd display power-reset 0 >/dev/null' EXIT\n"
            + "cmd display power-off 0 >/dev/null || { trap - EXIT; exit 1; }\n"
            + "echo ready\n"
            // EOF restores power if the Shizuku Java process exits, even after an unclean exit.
            + "toybox timeout 10m cat >/dev/null\n"
            + "cmd display power-reset 0 >/dev/null\n"
            + "status=$?; trap - EXIT; exit $status\n";
    private final java.lang.Process monitor;

    private Cv10PocketPhonePanel(java.lang.Process monitor) {
        this.monitor = monitor;
    }

    static Cv10PocketPhonePanel turnOff() {
        Cv10PocketPhonePanel lease = null;
        boolean acquired = false;
        try {
            lease = new Cv10PocketPhonePanel(new ProcessBuilder("sh", "-c", SCRIPT)
                    .redirectErrorStream(true).start());
            try (BufferedReader result = new BufferedReader(new InputStreamReader(
                    lease.monitor.getInputStream(), StandardCharsets.UTF_8))) {
                long deadline = SystemClock.elapsedRealtime() + 1500;
                while (!result.ready() && lease.monitor.isAlive() && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(20);
                }
                acquired = result.ready() && "ready".equals(result.readLine()) && lease.monitor.isAlive();
                if (acquired) Log.i(TAG, "Phone panel off for CV10 display clock");
                return acquired ? lease : null;
            }
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "Phone panel override unavailable", e);
            return null;
        } finally {
            if (!acquired && lease != null) lease.close();
        }
    }

    @Override
    public void close() {
        try {
            monitor.getOutputStream().close();
            if (monitor.waitFor(2, TimeUnit.SECONDS) && monitor.exitValue() == 0) {
                Log.i(TAG, "Phone panel returned to Android power control");
                return;
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not close the phone panel lease pipe", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            new ProcessBuilder("cmd", "display", "power-reset", "0").start();
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "Could not request phone panel reset", e);
        }
    }
}
