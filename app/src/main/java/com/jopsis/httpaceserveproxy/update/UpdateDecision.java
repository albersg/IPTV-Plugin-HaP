package com.jopsis.httpaceserveproxy.update;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a release list offers an installable update for this device.
 *
 * <p>Pure Java: this is the version-comparison core, unit testable on the JVM.
 *
 * <p>Rules it enforces:
 *
 * <ul>
 *   <li>Drafts are ignored; pre-releases only count when the user opted in.
 *   <li>{@code versionCode} is authoritative when both sides have one; the version name decides
 *       only when the release does not state a code.
 *   <li>A downgrade is never offered: when the version code and the version name disagree about
 *       the direction, the offer is refused instead of guessed.
 *   <li>The release must carry an asset this device can run.
 * </ul>
 */
public final class UpdateDecision {
    public enum Status {
        /** The repository has no published release. */
        NO_RELEASES,
        /** Only pre-releases exist and the user has not opted in. */
        ONLY_PRERELEASES,
        /** The newest applicable release is not newer than the installed app. */
        UP_TO_DATE,
        /** The release looks newer by one signal but older by the other: never offer it. */
        DOWNGRADE_REFUSED,
        /** The newest applicable release publishes no APK this device can run. */
        NO_COMPATIBLE_ASSET,
        /** A newer release with a runnable asset is available. */
        UPDATE_AVAILABLE
    }

    private final Status status;
    private final ReleaseInfo release;
    private final ReleaseAsset asset;

    private UpdateDecision(Status status, ReleaseInfo release, ReleaseAsset asset) {
        this.status = status;
        this.release = release;
        this.asset = asset;
    }

    public Status status() {
        return status;
    }

    /** The release the decision refers to, or {@code null} for the empty cases. */
    public ReleaseInfo release() {
        return release;
    }

    /** The asset to download, or {@code null} unless {@link #status()} is UPDATE_AVAILABLE. */
    public ReleaseAsset asset() {
        return asset;
    }

    public boolean hasUpdate() {
        return status == Status.UPDATE_AVAILABLE;
    }

    /**
     * @param releases releases as published by the API
     * @param installed the installed app version
     * @param deviceAbis the device ABI list in priority order
     * @param includePrereleases whether the user opted in to pre-releases
     */
    public static UpdateDecision decide(
            List<ReleaseInfo> releases,
            AppVersion installed,
            List<String> deviceAbis,
            boolean includePrereleases
    ) {
        if (installed == null) throw new IllegalArgumentException("The installed version is required");

        List<ReleaseInfo> applicable = new ArrayList<>();
        boolean sawPrerelease = false;
        if (releases != null) {
            for (ReleaseInfo release : releases) {
                if (release == null || release.isDraft()) continue;
                if (release.isPrerelease()) {
                    sawPrerelease = true;
                    if (!includePrereleases) continue;
                }
                applicable.add(release);
            }
        }

        if (applicable.isEmpty()) {
            return new UpdateDecision(sawPrerelease ? Status.ONLY_PRERELEASES : Status.NO_RELEASES, null, null);
        }

        ReleaseInfo best = applicable.get(0);
        for (ReleaseInfo candidate : applicable) {
            if (compareReleases(candidate, best) > 0) best = candidate;
        }

        int nameComparison = AppVersion.compareNames(best.versionName(), installed.versionName());
        boolean newer;
        if (best.announcedVersionCode() > 0 && installed.versionCode() > 0) {
            newer = best.announcedVersionCode() > installed.versionCode();
        } else {
            newer = nameComparison > 0;
        }
        if (!newer) return new UpdateDecision(Status.UP_TO_DATE, best, null);
        if (nameComparison < 0) return new UpdateDecision(Status.DOWNGRADE_REFUSED, best, null);

        ReleaseAsset asset = AssetSelector.select(best.assets(), deviceAbis);
        if (asset == null) return new UpdateDecision(Status.NO_COMPATIBLE_ASSET, best, null);
        return new UpdateDecision(Status.UPDATE_AVAILABLE, best, asset);
    }

    /** Ordering between two releases: version code first when both state one, then the name. */
    public static int compareReleases(ReleaseInfo left, ReleaseInfo right) {
        if (left == null || right == null) throw new IllegalArgumentException("Both releases are required");
        if (left.announcedVersionCode() > 0 && right.announcedVersionCode() > 0) {
            int byCode = Integer.compare(left.announcedVersionCode(), right.announcedVersionCode());
            if (byCode != 0) return byCode;
        }
        return AppVersion.compareNames(left.versionName(), right.versionName());
    }
}
