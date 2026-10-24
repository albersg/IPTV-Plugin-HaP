package com.jopsis.httpaceserveproxy.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the GitHub Releases API payload into {@link ReleaseInfo} values.
 *
 * <p>Pure Java and kept out of the Android classes on purpose: JSON parsing is where the release
 * and asset shapes are decided, so it must be unit testable on the JVM.
 */
public final class ReleaseParser {
    private ReleaseParser() {
    }

    /**
     * Parses the JSON array returned by {@code GET /repos/{owner}/{repo}/releases}.
     *
     * @throws IllegalArgumentException when the payload is not a JSON array of releases.
     */
    public static List<ReleaseInfo> parseReleases(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof List)) throw new IllegalArgumentException("Expected a JSON array of releases");

        List<ReleaseInfo> releases = new ArrayList<>();
        for (Object entry : Json.asArray(root)) {
            if (!(entry instanceof Map)) continue;
            ReleaseInfo release = parseRelease(Json.asObject(entry));
            if (release != null) releases.add(release);
        }
        return releases;
    }

    /** Parses one release object; returns {@code null} when it has no usable tag. */
    public static ReleaseInfo parseRelease(Map<String, Object> object) {
        if (object == null) return null;
        String tagName = Json.string(object, "tag_name").trim();
        String name = Json.string(object, "name").trim();
        String notes = Json.string(object, "body");

        String versionCandidate = versionNameCandidate(tagName, name);
        if (versionCandidate.isEmpty()) return null;

        int announcedCode = ReleaseInfo.parseAnnouncedVersionCode(notes);
        if (announcedCode <= 0) announcedCode = ReleaseInfo.parseAnnouncedVersionCode(name);

        List<ReleaseAsset> assets = new ArrayList<>();
        for (Object entry : Json.asArray(object.get("assets"))) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> asset = Json.asObject(entry);
            String fileName = Json.string(asset, "name").trim();
            String downloadUrl = Json.string(asset, "browser_download_url").trim();
            if (fileName.isEmpty() || downloadUrl.isEmpty()) continue;
            ReleaseAsset parsed = new ReleaseAsset(fileName, Json.number(asset, "size", 0L), downloadUrl);
            if (parsed.isApk()) assets.add(parsed);
        }

        return new ReleaseInfo(
                tagName,
                versionCandidate,
                announcedCode,
                Json.bool(object, "prerelease"),
                Json.bool(object, "draft"),
                notes,
                assets
        );
    }

    /** The tag is the release version; a semver-looking release name is the fallback. */
    private static String versionNameCandidate(String tagName, String name) {
        String fromTag = AppVersion.normalizeName(tagName);
        if (looksLikeVersion(fromTag)) return fromTag;
        String fromName = AppVersion.normalizeName(name);
        if (looksLikeVersion(fromName)) return fromName;
        return fromTag;
    }

    /** A version name starts with a digit once the optional {@code v} is gone. */
    private static boolean looksLikeVersion(String value) {
        if (value == null || value.isEmpty()) return false;
        char first = value.toLowerCase(Locale.US).charAt(0);
        return first >= '0' && first <= '9';
    }
}
