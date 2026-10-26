package com.jopsis.httpaceserveproxy;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted switch for the experimental same-channel retry.
 *
 * <p>Off by default. When on, an upstream whose reader ends while clients are still waiting is
 * reopened a bounded number of times with backoff before the clients are closed
 * ({@code odd/tasks/hap-stream-recovery.md}). It is read when the native proxy starts, so a change
 * applies to the next server start, not to a session already running.
 */
public final class RecoverySettings {
    private static final String KEY_ENABLED = "stream_recovery_enabled";

    /** Retries per upstream when the switch is on: enough to ride out a re-announce, few enough to give up. */
    public static final int ATTEMPTS_WHEN_ENABLED = 3;

    private RecoverySettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PlaylistCatalog.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit();
    }

    /** What the native config receives: 0 disables recovery. */
    public static int attempts(Context context) {
        return isEnabled(context) ? ATTEMPTS_WHEN_ENABLED : 0;
    }
}
