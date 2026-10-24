package com.jopsis.httpaceserveproxy.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A published GitHub release, reduced to what the updater needs.
 *
 * <p>Pure Java, like the rest of this package: the release shape and the version-code extraction
 * are unit testable on the JVM.
 */
public final class ReleaseInfo {
    /**
     * The release workflow writes "Signed APK for version 1.3.4 (versionCode 15)." into the release
     * notes, which is the only published place that carries the authoritative version code.
     */
    private static final Pattern ANNOUNCED_VERSION_CODE =
            Pattern.compile("versioncode[^0-9]{0,8}([0-9]{1,9})", Pattern.CASE_INSENSITIVE);

    private final String tagName;
    private final String versionName;
    private final int announcedVersionCode;
    private final boolean prerelease;
    private final boolean draft;
    private final String notes;
    private final List<ReleaseAsset> assets;

    public ReleaseInfo(
            String tagName,
            String announcedVersionName,
            int announcedVersionCode,
            boolean prerelease,
            boolean draft,
            String notes,
            List<ReleaseAsset> assets
    ) {
        String rawName = announcedVersionName == null || announcedVersionName.trim().isEmpty()
                ? AppVersion.normalizeName(tagName)
                : AppVersion.normalizeName(announcedVersionName);
        this.tagName = tagName == null ? "" : tagName.trim();
        this.versionName = rawName;
        this.announcedVersionCode = Math.max(0, announcedVersionCode);
        this.prerelease = prerelease || AppVersion.isPrereleaseName(rawName);
        this.draft = draft;
        this.notes = notes == null ? "" : notes;
        this.assets = assets == null
                ? Collections.<ReleaseAsset>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(assets));
    }

    /** Tag as published, e.g. {@code v1.3.4}. */
    public String tagName() {
        return tagName;
    }

    /** Version name without a leading {@code v}, e.g. {@code 1.3.4}. */
    public String versionName() {
        return versionName;
    }

    /** Version code stated by the release, or {@code 0} when it does not state one. */
    public int announcedVersionCode() {
        return announcedVersionCode;
    }

    /** True for a pre-release by GitHub flag or by a semver pre-release suffix. */
    public boolean isPrerelease() {
        return prerelease;
    }

    public boolean isDraft() {
        return draft;
    }

    public String notes() {
        return notes;
    }

    public List<ReleaseAsset> assets() {
        return assets;
    }

    /** The APK this device can actually run, or {@code null} when the release has none. */
    public ReleaseAsset assetForDevice(List<String> deviceAbis) {
        return AssetSelector.select(assets, deviceAbis);
    }

    /** Extracts the version code stated in release notes, or {@code 0} when absent. */
    public static int parseAnnouncedVersionCode(String notes) {
        if (notes == null || notes.isEmpty()) return 0;
        Matcher matcher = ANNOUNCED_VERSION_CODE.matcher(notes.toLowerCase(Locale.US));
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    @Override
    public String toString() {
        return versionName + (prerelease ? " (pre-release)" : "") + " assets=" + assets.size();
    }
}
