package com.jopsis.httpaceserveproxy.update;

/**
 * One downloadable file attached to a release.
 *
 * <p>Pure Java: parsed and selected without Android so the ABI selection rules can be unit
 * tested on the JVM.
 */
public final class ReleaseAsset {
    private final String name;
    private final long sizeBytes;
    private final String downloadUrl;

    public ReleaseAsset(String name, long sizeBytes, String downloadUrl) {
        this.name = name == null ? "" : name.trim();
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.downloadUrl = downloadUrl == null ? "" : downloadUrl.trim();
    }

    public String name() {
        return name;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public String downloadUrl() {
        return downloadUrl;
    }

    public boolean isApk() {
        return name.toLowerCase(java.util.Locale.US).endsWith(".apk");
    }

    public boolean isUsable() {
        return isApk() && !downloadUrl.isEmpty();
    }

    @Override
    public String toString() {
        return name + " (" + sizeBytes + " bytes)";
    }
}
