package com.jopsis.httpaceserveproxy.update;

/**
 * Fixed updater configuration: the distribution repository and the check cadence.
 *
 * <p>Pure Java so the values and the interval arithmetic are unit testable on the JVM.
 */
public final class UpdateConfig {
    /**
     * The single place that decides where updates come from: this fork's own releases.
     *
     * <p>It must stay a repository whose releases are signed with the same key as the installed
     * build; the signature check before install enforces that at runtime. The value is
     * overridable from the app at runtime (stored as {@code owner/name}), so no rebuild is needed
     * to point a build at another distribution repository.
     */
    public static final String DEFAULT_REPOSITORY = "albersg/IPTV-Plugin-HaP";

    public static final String API_BASE_URL = "https://api.github.com";
    public static final String USER_AGENT = "HaP-Updater";

    /** Default automatic check cadence: once per day. */
    public static final long DEFAULT_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    public static final long MIN_INTERVAL_MS = 60L * 60L * 1000L;
    public static final long MAX_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L;
    public static final int DEFAULT_INTERVAL_HOURS = 24;

    private UpdateConfig() {
    }

    public static String defaultOwner() {
        return ownerOf(DEFAULT_REPOSITORY);
    }

    public static String defaultName() {
        return nameOf(DEFAULT_REPOSITORY);
    }

    public static String ownerOf(String repository) {
        if (repository == null) return "";
        int slash = repository.indexOf('/');
        return slash < 0 ? repository.trim() : repository.substring(0, slash).trim();
    }

    public static String nameOf(String repository) {
        if (repository == null) return "";
        int slash = repository.indexOf('/');
        return slash < 0 ? "" : repository.substring(slash + 1).trim();
    }

    public static boolean isValidRepository(String repository) {
        if (repository == null) return false;
        String value = repository.trim();
        String owner = ownerOf(value);
        String name = nameOf(value);
        return !owner.isEmpty() && !name.isEmpty()
                && isRepositorySegment(owner)
                && isRepositorySegment(name)
                && value.indexOf('/') == value.lastIndexOf('/');
    }

    /** Clamps an interval in hours to the accepted range and returns it in milliseconds. */
    public static long intervalMillis(int hours) {
        int clamped = Math.max(hoursInRangeMin(), Math.min(hoursInRangeMax(), hours));
        return clamped * 60L * 60L * 1000L;
    }

    /** Rounds a stored interval down to whole hours, clamped to the accepted range. */
    public static int hours(long intervalMillis) {
        int hours = (int) Math.round(intervalMillis / (60.0 * 60.0 * 1000.0));
        return Math.max(hoursInRangeMin(), Math.min(hoursInRangeMax(), hours));
    }

    /** True when enough time passed since the last check to run another automatic one. */
    public static boolean isDue(long lastCheckMillis, long intervalMillis, long nowMillis) {
        if (lastCheckMillis <= 0) return true;
        long effectiveInterval = intervalMillis > 0 ? intervalMillis : DEFAULT_INTERVAL_MS;
        if (lastCheckMillis > nowMillis) return true;
        return nowMillis - lastCheckMillis >= effectiveInterval;
    }

    private static int hoursInRangeMin() {
        return (int) (MIN_INTERVAL_MS / (60L * 60L * 1000L));
    }

    private static int hoursInRangeMax() {
        return (int) (MAX_INTERVAL_MS / (60L * 60L * 1000L));
    }

    private static boolean isRepositorySegment(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            boolean allowed = (current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '-' || current == '_' || current == '.';
            if (!allowed) return false;
        }
        return true;
    }
}
