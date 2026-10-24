package com.jopsis.httpaceserveproxy;

import android.content.Context;
import android.content.SharedPreferences;

import com.jopsis.httpaceserveproxy.update.UpdateConfig;

/**
 * Persisted updater state: the source repository, the check cadence, the GitHub ETag cache, the
 * postponed offer and the last install result.
 *
 * <p>Stored in the same preferences file as the rest of HaP's settings
 * ({@link PlaylistCatalog#PREFS_NAME}) so the app keeps a single preferences surface.
 */
public final class UpdateSettings {
    /** Marker for "no install result recorded". */
    public static final int NO_INSTALL_STATUS = Integer.MIN_VALUE;

    private static final String KEY_REPOSITORY = "update_repository";
    private static final String KEY_AUTOMATIC = "update_automatic_enabled";
    private static final String KEY_INTERVAL_MS = "update_interval_millis";
    private static final String KEY_INCLUDE_PRERELEASE = "update_include_prerelease";
    private static final String KEY_LAST_CHECK = "update_last_check_millis";
    private static final String KEY_ETAG = "update_release_etag";
    private static final String KEY_CACHED_RELEASES = "update_cached_releases";
    private static final String KEY_POSTPONED_VERSION = "update_postponed_version";
    private static final String KEY_POSTPONED_CODE = "update_postponed_version_code";
    private static final String KEY_INSTALL_STATUS = "update_install_status";

    private UpdateSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PlaylistCatalog.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The {@code owner/name} the updater queries; the app default unless overridden. */
    public static String repository(Context context) {
        String stored = prefs(context).getString(KEY_REPOSITORY, "");
        if (stored != null && UpdateConfig.isValidRepository(stored)) return stored.trim();
        return UpdateConfig.DEFAULT_REPOSITORY;
    }

    /** @return false when the value is not a valid {@code owner/name}. */
    public static boolean setRepository(Context context, String repository) {
        String value = repository == null ? "" : repository.trim();
        if (value.isEmpty()) {
            prefs(context).edit().remove(KEY_REPOSITORY).commit();
            return true;
        }
        if (!UpdateConfig.isValidRepository(value)) return false;
        prefs(context).edit().putString(KEY_REPOSITORY, value).commit();
        return true;
    }

    public static boolean isAutomaticCheckEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTOMATIC, true);
    }

    public static void setAutomaticCheckEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTOMATIC, enabled).commit();
    }

    public static long checkIntervalMillis(Context context) {
        long stored = prefs(context).getLong(KEY_INTERVAL_MS, UpdateConfig.DEFAULT_INTERVAL_MS);
        return Math.max(UpdateConfig.MIN_INTERVAL_MS, Math.min(UpdateConfig.MAX_INTERVAL_MS, stored));
    }

    public static void setCheckIntervalMillis(Context context, long intervalMillis) {
        long clamped = Math.max(UpdateConfig.MIN_INTERVAL_MS, Math.min(UpdateConfig.MAX_INTERVAL_MS, intervalMillis));
        prefs(context).edit().putLong(KEY_INTERVAL_MS, clamped).commit();
    }

    public static boolean includePrereleases(Context context) {
        return prefs(context).getBoolean(KEY_INCLUDE_PRERELEASE, false);
    }

    public static void setIncludePrereleases(Context context, boolean include) {
        prefs(context).edit().putBoolean(KEY_INCLUDE_PRERELEASE, include).commit();
    }

    public static long lastCheckMillis(Context context) {
        return prefs(context).getLong(KEY_LAST_CHECK, 0L);
    }

    public static void setLastCheckMillis(Context context, long millis) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, Math.max(0L, millis)).commit();
    }

    /** True when an automatic check is allowed to run right now. */
    public static boolean isAutomaticCheckDue(Context context, long nowMillis) {
        return isAutomaticCheckEnabled(context)
                && UpdateConfig.isDue(lastCheckMillis(context), checkIntervalMillis(context), nowMillis);
    }

    static String etag(Context context) {
        String value = prefs(context).getString(KEY_ETAG, "");
        return value == null ? "" : value;
    }

    static void setEtag(Context context, String etag) {
        if (etag == null || etag.isEmpty()) prefs(context).edit().remove(KEY_ETAG).commit();
        else prefs(context).edit().putString(KEY_ETAG, etag).commit();
    }

    static String cachedReleases(Context context) {
        String value = prefs(context).getString(KEY_CACHED_RELEASES, "");
        return value == null ? "" : value;
    }

    static void setCachedReleases(Context context, String body) {
        if (body == null || body.isEmpty()) prefs(context).edit().remove(KEY_CACHED_RELEASES).commit();
        else prefs(context).edit().putString(KEY_CACHED_RELEASES, body).commit();
    }

    /** Records that the user postponed the offered version. */
    public static void setPostponed(Context context, String versionName, int versionCode) {
        prefs(context).edit()
                .putString(KEY_POSTPONED_VERSION, versionName == null ? "" : versionName)
                .putInt(KEY_POSTPONED_CODE, Math.max(0, versionCode))
                .commit();
    }

    /**
     * True when this exact version was postponed: a new, explicitly higher version is offered
     * again, and the automatic check interval still bounds the re-prompt of the same one.
     */
    public static boolean isPostponed(Context context, String versionName, int versionCode) {
        SharedPreferences preferences = prefs(context);
        String postponedVersion = preferences.getString(KEY_POSTPONED_VERSION, "");
        int postponedCode = preferences.getInt(KEY_POSTPONED_CODE, 0);
        if (postponedVersion == null || postponedVersion.isEmpty()) return false;
        return postponedVersion.equals(versionName) && postponedCode == Math.max(0, versionCode);
    }

    public static void setInstallStatus(Context context, int status) {
        prefs(context).edit().putInt(KEY_INSTALL_STATUS, status).commit();
    }

    public static int installStatus(Context context) {
        return prefs(context).getInt(KEY_INSTALL_STATUS, NO_INSTALL_STATUS);
    }

    public static void clearInstallStatus(Context context) {
        prefs(context).edit().remove(KEY_INSTALL_STATUS).commit();
    }
}
