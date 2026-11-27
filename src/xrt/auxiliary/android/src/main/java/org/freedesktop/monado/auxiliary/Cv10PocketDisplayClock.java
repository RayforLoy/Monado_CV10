// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Holds the SurfaceFlinger frame clock on the physical CV10 while rendering its optical view. */
final class Cv10PocketDisplayClock implements AutoCloseable {
    private static final String TAG = "Cv10PocketDisplayClock";
    private static final Pattern CV10 = Pattern.compile("(?m)^Display (\\d+) [^\\r\\n]*displayName=\"HUAWEIAV03\"\\s*$");
    private static final Pattern CURRENT = Pattern.compile("(?m)^\\s*pacesetterDisplayId=(\\d+)\\s*$");
    private static final Pattern FORCED = Pattern.compile("(?m)^\\s*forcedPacesetterDisplayId=(\\d+|nullopt)\\s*$");

    private final String glasses;
    private final String previous;

    private Cv10PocketDisplayClock(String glasses, String previous) {
        this.glasses = glasses;
        this.previous = previous;
    }

    static Cv10PocketDisplayClock acquire() {
        String glasses = read(CV10, run("dumpsys", "-t", "1", "SurfaceFlinger", "--display-id"));
        if (glasses == null) return null;
        String before = scheduler();
        String previous = read(CURRENT, before);
        if (previous == null || !"nullopt".equals(read(FORCED, before))) return null;
        if (run("sfdo", "force-pacesetter", glasses) == null) return null;
        SystemClock.sleep(300);
        Log.i(TAG, "CV10 frame clock requested for " + glasses + ", active=" + read(CURRENT, scheduler()));
        return new Cv10PocketDisplayClock(glasses, previous);
    }

    @Override
    public void close() {
        if (!glasses.equals(read(FORCED, scheduler()))) return;
        if (run("sfdo", "reset-forced-pacesetter") == null) return;
        SystemClock.sleep(300);
        String after = scheduler();
        // Samsung can retain the last pacesetter after clearing the override.
        if ("nullopt".equals(read(FORCED, after)) && glasses.equals(read(CURRENT, after))
                && !glasses.equals(previous)) {
            if (run("sfdo", "force-pacesetter", previous) == null) return;
            SystemClock.sleep(300);
            run("sfdo", "reset-forced-pacesetter");
        }
    }

    boolean hasActiveCv10Clock() {
        String state = scheduler();
        return glasses.equals(read(FORCED, state)) && glasses.equals(read(CURRENT, state));
    }

    private static String scheduler() {
        return run("dumpsys", "-t", "1", "SurfaceFlinger", "--scheduler");
    }

    private static String read(Pattern pattern, String output) {
        if (output == null) return null;
        Matcher match = pattern.matcher(output);
        return match.find() ? match.group(1) : null;
    }

    private static String run(String... command) {
        java.lang.Process child = null;
        try {
            child = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            if ("sfdo".equals(command[0]) && !child.waitFor(1, TimeUnit.SECONDS)) {
                Log.w(TAG, "SurfaceFlinger clock command timed out");
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            if (child.waitFor(1, TimeUnit.SECONDS) && child.exitValue() == 0) return output.toString();
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "SurfaceFlinger clock command unavailable", e);
            return null;
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
        }
    }
}
