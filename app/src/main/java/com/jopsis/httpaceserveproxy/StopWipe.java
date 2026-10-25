package com.jopsis.httpaceserveproxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;

/**
 * P3 — the opt-in wipe of the streaming cache and the engine log on an explicit stop.
 *
 * <p>It is deliberately narrow. The engine keeps its live cache, its log and its configuration in
 * the one directory {@link AceServeRuntime} passes to it as its cache dir, so the wipe deletes the
 * contents of that directory except {@code acestream.conf}: the engine reads that file at startup
 * ({@code core_params()} in {@code assets/aceserve/main_android.py}), and deleting it would change
 * how the next start behaves. M3U sources, custom channels, preferences and downloaded update files
 * live outside that directory and are never touched.
 *
 * <p>The wipe runs from the explicit stop control and nowhere else: backgrounding, process death, a
 * crash, a restart and the stop that precedes an update install never reach it. Failure to delete
 * anything can never break the stop path — every error is caught and logged without the path.
 */
public final class StopWipe {

    private static final String TAG = "HaP-StopWipe";
    private static final String KEY_WIPE_ON_STOP = "wipe_on_stop_enabled";
    /** The engine's cache directory: what {@link AceServeRuntime} passes as its cache dir. */
    static final String ENGINE_CACHE_DIR = "aceserve";
    /** Configuration the engine reads at startup, so it survives every wipe. */
    static final String ENGINE_CONF_FILE = "acestream.conf";
    /** How long the stop may take before the wipe runs anyway; the engine is down by then. */
    private static final long STOP_WAIT_MILLIS = 10_000L;
    private static final long STOP_POLL_MILLIS = 100L;

    private StopWipe() {
    }

    /** Off by default: an unchanged install never deletes anything on stop. */
    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_WIPE_ON_STOP, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_WIPE_ON_STOP, enabled).commit();
    }

    /**
     * Wipes after the user asked to stop: waits, bounded, for the supervisor to report the engine
     * down so nothing is written again afterwards, then removes the cache and the engine log.
     *
     * <p>Never throws: a wipe that cannot finish is a log line, not a failed stop.
     *
     * @return the number of entries removed, or 0 when the setting is off
     */
    public static int wipeAfterExplicitStop(Context context) {
        try {
            if (!isEnabled(context)) return 0;
            awaitStopped(STOP_WAIT_MILLIS);
            File engineCacheDir = new File(context.getCacheDir(), ENGINE_CACHE_DIR);
            int removed = wipeCacheDirectory(engineCacheDir);
            // The log the app shows is the in-memory copy of the same engine output.
            ServiceState.clearLog();
            return removed;
        } catch (Throwable error) {
            // Deliberately no path and no message: both can name a source or a user directory.
            Log.w(TAG, "wipe on stop failed: " + error.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * Deletes the streaming cache and the engine log under {@code engineCacheDir}, keeping
     * {@code acestream.conf}. A missing or empty directory is not an error.
     *
     * @return how many entries were removed
     */
    public static int wipeCacheDirectory(File engineCacheDir) {
        if (engineCacheDir == null) return 0;
        File[] entries = engineCacheDir.listFiles();
        if (entries == null) return 0;
        int removed = 0;
        for (File entry : entries) {
            if (ENGINE_CONF_FILE.equals(entry.getName())) continue;
            removed += deleteRecursively(entry);
        }
        return removed;
    }

    private static int deleteRecursively(File file) {
        int removed = 0;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) removed += deleteRecursively(child);
            }
        }
        return file.delete() ? removed + 1 : removed;
    }

    private static void awaitStopped(long maxWaitMillis) {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        while (stillRunning() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(STOP_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean stillRunning() {
        return ServiceState.desiredRunning || ServiceState.aceRunning || ServiceState.proxyRunning;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PlaylistCatalog.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
