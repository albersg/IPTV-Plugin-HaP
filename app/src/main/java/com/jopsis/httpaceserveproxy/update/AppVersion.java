package com.jopsis.httpaceserveproxy.update;

import java.util.ArrayList;
import java.util.List;

/**
 * An app version: the numeric {@code versionCode} plus the human-facing {@code versionName}.
 *
 * <p>Pure Java on purpose: the update ordering rules are the part of the updater that must be
 * unit testable on the JVM, without an emulator or the Android framework.
 *
 * <p>{@code versionCode} is the authoritative ordering signal when both sides have one.
 * {@code versionName} is the human-facing one, and is only used for ordering when a version code
 * is missing. Names are compared semver-ish: a leading {@code v} is ignored, dot-separated
 * numeric parts are compared numerically, build metadata ({@code +...}) is ignored, and a
 * pre-release suffix ({@code -beta.1}) sorts lower than the same stable version.
 */
public final class AppVersion {
    private final int versionCode;
    private final String versionName;

    public AppVersion(int versionCode, String versionName) {
        this.versionCode = Math.max(0, versionCode);
        this.versionName = normalizeName(versionName);
    }

    public int versionCode() {
        return versionCode;
    }

    /** Normalized version name: trimmed, without a leading {@code v}. */
    public String versionName() {
        return versionName;
    }

    public boolean isPrerelease() {
        return isPrereleaseName(versionName);
    }

    /** Strips surrounding whitespace and one leading {@code v}/{@code V}. */
    public static String normalizeName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.isEmpty() && (value.charAt(0) == 'v' || value.charAt(0) == 'V')) {
            value = value.substring(1).trim();
        }
        return value;
    }

    public static boolean isPrereleaseName(String rawName) {
        return !prerelease(normalizeName(rawName)).isEmpty();
    }

    /**
     * Compares two version names semver-ish.
     *
     * @return a negative number when {@code left} is older, zero when equivalent, positive when
     *     {@code left} is newer.
     */
    public static int compareNames(String left, String right) {
        String leftName = normalizeName(left);
        String rightName = normalizeName(right);

        int core = compareNumbers(coreNumbers(leftName), coreNumbers(rightName));
        if (core != 0) return core;
        return comparePrerelease(prerelease(leftName), prerelease(rightName));
    }

    /**
     * Compares two versions. The version code wins when both are known; otherwise the version
     * name decides.
     */
    public static int compare(AppVersion left, AppVersion right) {
        if (left == null || right == null) throw new IllegalArgumentException("Both versions are required");
        if (left.versionCode > 0 && right.versionCode > 0) {
            int byCode = Integer.compare(left.versionCode, right.versionCode);
            if (byCode != 0) return byCode;
        }
        return compareNames(left.versionName, right.versionName);
    }

    @Override
    public String toString() {
        return versionName + " (" + versionCode + ")";
    }

    private static String stripBuildMetadata(String name) {
        int plus = name.indexOf('+');
        return plus < 0 ? name : name.substring(0, plus);
    }

    private static String prerelease(String normalizedName) {
        String withoutBuild = stripBuildMetadata(normalizedName);
        int dash = withoutBuild.indexOf('-');
        return dash < 0 ? "" : withoutBuild.substring(dash + 1);
    }

    private static List<Integer> coreNumbers(String normalizedName) {
        String core = stripBuildMetadata(normalizedName);
        int dash = core.indexOf('-');
        if (dash >= 0) core = core.substring(0, dash);

        List<Integer> numbers = new ArrayList<>();
        if (core.isEmpty()) return numbers;
        for (String part : core.split("\\.", -1)) {
            numbers.add(leadingInt(part));
        }
        return numbers;
    }

    private static int leadingInt(String part) {
        int end = 0;
        while (end < part.length() && part.charAt(end) >= '0' && part.charAt(end) <= '9') end++;
        if (end == 0) return 0;
        try {
            return Integer.parseInt(part.substring(0, end));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static int compareNumbers(List<Integer> left, List<Integer> right) {
        int size = Math.max(left.size(), right.size());
        for (int i = 0; i < size; i++) {
            int leftPart = i < left.size() ? left.get(i) : 0;
            int rightPart = i < right.size() ? right.get(i) : 0;
            int comparison = Integer.compare(leftPart, rightPart);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    /** Semver rule: a stable version sorts above its own pre-releases. */
    private static int comparePrerelease(String left, String right) {
        boolean leftStable = left.isEmpty();
        boolean rightStable = right.isEmpty();
        if (leftStable && rightStable) return 0;
        if (leftStable) return 1;
        if (rightStable) return -1;

        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int size = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < size; i++) {
            if (i >= leftParts.length) return -1;
            if (i >= rightParts.length) return 1;
            int comparison = comparePrereleaseIdentifier(leftParts[i], rightParts[i]);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static int comparePrereleaseIdentifier(String left, String right) {
        boolean leftNumeric = isNumeric(left);
        boolean rightNumeric = isNumeric(right);
        if (leftNumeric && rightNumeric) {
            return Long.compare(parseLong(left), parseLong(right));
        }
        if (leftNumeric) return -1;
        if (rightNumeric) return 1;
        return left.compareTo(right);
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current < '0' || current > '9') return false;
        }
        return true;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
