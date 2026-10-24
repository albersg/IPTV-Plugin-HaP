package com.jopsis.httpaceserveproxy.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * JVM tests for the updater's decision core.
 *
 * <p>These are the rules that decide whether the app offers, downloads and hands
 * an APK to the installer, so they are pinned here rather than on a device. The
 * classes under test deliberately have no android.* imports.
 *
 * <p>The reference device reports {@code armeabi-v7a}, so most cases use a
 * 32-bit device ABI list.
 */
public final class UpdateCoreTest {

    private static final List<String> V7 = Collections.singletonList("armeabi-v7a");
    private static final List<String> V8 = Collections.singletonList("arm64-v8a");

    private static ReleaseAsset apk(String name) {
        return new ReleaseAsset(name, 70L * 1024L * 1024L, "https://example.invalid/" + name);
    }

    private static ReleaseInfo release(String tag, int versionCode, boolean prerelease,
                                       ReleaseAsset... assets) {
        return new ReleaseInfo(tag, tag, versionCode, prerelease, false, "",
                Arrays.asList(assets));
    }

    // ---------------------------------------------------------------- version names

    @Test
    public void normalizeNameStripsLeadingV() {
        assertEquals("1.3.4", AppVersion.normalizeName("v1.3.4"));
        assertEquals("1.3.4", AppVersion.normalizeName("1.3.4"));
        assertEquals("1.3.4", AppVersion.normalizeName("  V1.3.4 "));
    }

    @Test
    public void compareNamesOrdersByNumericSegment() {
        // A plain string compare would put "1.3.10" before "1.3.9".
        assertTrue(AppVersion.compareNames("1.3.10", "1.3.9") > 0);
        assertTrue(AppVersion.compareNames("1.4.0", "1.3.3") > 0);
        assertTrue(AppVersion.compareNames("1.3.3", "1.4.0") < 0);
        assertEquals(0, AppVersion.compareNames("1.3.3", "v1.3.3"));
    }

    @Test
    public void prereleaseNameIsLowerThanStable() {
        assertTrue(AppVersion.isPrereleaseName("1.4.0-beta1"));
        assertFalse(AppVersion.isPrereleaseName("1.4.0"));
        assertTrue(AppVersion.compareNames("1.4.0", "1.4.0-beta1") > 0);
    }

    // ---------------------------------------------------------------- ABI tokens

    @Test
    public void abiTokenReadsTheSuffix() {
        assertEquals("armeabi-v7a", AssetSelector.abiToken("IPTV-Plugin-HaP-1.3.4-armeabi-v7a.apk"));
        assertEquals("arm64-v8a", AssetSelector.abiToken("IPTV-Plugin-HaP-1.3.4-arm64-v8a.apk"));
        assertEquals("", AssetSelector.abiToken("IPTV-Plugin-HaP.apk"));
        assertEquals("", AssetSelector.abiToken("not-an-apk.txt"));
    }

    @Test
    public void abiTokenDoesNotConfuseArmeabiWithArmeabiV7a() {
        // The bug this pins: splitting asset names on '-' makes "armeabi" match the
        // armv7 asset, so a 32-bit-only device could be handed an arm64 build.
        assertFalse("armeabi".equals(AssetSelector.abiToken("IPTV-Plugin-HaP-1.3.4-armeabi-v7a.apk")));
        assertFalse(AssetSelector.isRunnableOn("IPTV-Plugin-HaP-1.3.4-armeabi-v7a.apk",
                Collections.singletonList("armeabi")));
    }

    @Test
    public void isRunnableOnRejectsForeignAbi() {
        assertTrue(AssetSelector.isRunnableOn("IPTV-Plugin-HaP-1.3.4-armeabi-v7a.apk", V7));
        assertFalse(AssetSelector.isRunnableOn("IPTV-Plugin-HaP-1.3.4-arm64-v8a.apk", V7));
        assertTrue(AssetSelector.isRunnableOn("IPTV-Plugin-HaP.apk", V7));
    }

    // ---------------------------------------------------------------- asset selection

    @Test
    public void selectPicksTheDeviceAbiNotTheWrongOne() {
        List<ReleaseAsset> assets = Arrays.asList(
                apk("IPTV-Plugin-HaP-1.3.4-arm64-v8a.apk"),
                apk("IPTV-Plugin-HaP-1.3.4-armeabi-v7a.apk"));
        ReleaseAsset chosen = AssetSelector.select(assets, V7);
        assertNotNull(chosen);
        assertEquals("IPTV-Plugin-HaP-1.3.4-armeabi-v7a.apk", chosen.name());
    }

    @Test
    public void selectFallsBackToUniversal() {
        List<ReleaseAsset> assets = Arrays.asList(
                apk("IPTV-Plugin-HaP-1.3.4-arm64-v8a.apk"),
                apk("IPTV-Plugin-HaP.apk"));
        ReleaseAsset chosen = AssetSelector.select(assets, V7);
        assertNotNull(chosen);
        assertEquals("IPTV-Plugin-HaP.apk", chosen.name());
    }

    @Test
    public void selectReturnsNullWhenNoAssetCanRun() {
        List<ReleaseAsset> assets = Collections.singletonList(
                apk("IPTV-Plugin-HaP-1.3.4-arm64-v8a.apk"));
        assertNull(AssetSelector.select(assets, V7));
    }

    // ---------------------------------------------------------------- decisions

    @Test
    public void offersNewerReleaseWithRunnableAsset() {
        UpdateDecision decision = UpdateDecision.decide(
                Collections.singletonList(release("v1.3.4", 15, false,
                        apk("IPTV-Plugin-HaP-1.3.4-armeabi-v7a.apk"))),
                new AppVersion(14, "1.3.3"), V7, false);
        assertEquals(UpdateDecision.Status.UPDATE_AVAILABLE, decision.status());
        assertTrue(decision.hasUpdate());
        assertEquals("IPTV-Plugin-HaP-1.3.4-armeabi-v7a.apk", decision.asset().name());
    }

    @Test
    public void sameOrOlderVersionIsUpToDate() {
        UpdateDecision same = UpdateDecision.decide(
                Collections.singletonList(release("v1.3.3", 14, false,
                        apk("IPTV-Plugin-HaP-1.3.3-armeabi-v7a.apk"))),
                new AppVersion(14, "1.3.3"), V7, false);
        assertEquals(UpdateDecision.Status.UP_TO_DATE, same.status());
    }

    @Test
    public void prereleasesAreHiddenUntilOptedIn() {
        List<ReleaseInfo> releases = Collections.singletonList(release("v1.4.0-beta1", 15, true,
                apk("IPTV-Plugin-HaP-1.4.0-beta1-armeabi-v7a.apk")));
        assertEquals(UpdateDecision.Status.ONLY_PRERELEASES,
                UpdateDecision.decide(releases, new AppVersion(14, "1.3.3"), V7, false).status());
        assertEquals(UpdateDecision.Status.UPDATE_AVAILABLE,
                UpdateDecision.decide(releases, new AppVersion(14, "1.3.3"), V7, true).status());
    }

    @Test
    public void releaseWithNoRunnableAssetIsNotOffered() {
        UpdateDecision decision = UpdateDecision.decide(
                Collections.singletonList(release("v1.3.4", 15, false,
                        apk("IPTV-Plugin-HaP-1.3.4-arm64-v8a.apk"))),
                new AppVersion(14, "1.3.3"), V7, false);
        assertEquals(UpdateDecision.Status.NO_COMPATIBLE_ASSET, decision.status());
        assertFalse(decision.hasUpdate());
    }

    @Test
    public void lowerVersionCodeIsUpToDateEvenWithANewerLookingName() {
        // versionCode is authoritative when both sides state one, so a newer-looking
        // name cannot promote a build whose code is not newer.
        UpdateDecision decision = UpdateDecision.decide(
                Collections.singletonList(release("v1.3.5", 13, false,
                        apk("IPTV-Plugin-HaP-1.3.5-armeabi-v7a.apk"))),
                new AppVersion(14, "1.3.3"), V7, false);
        assertEquals(UpdateDecision.Status.UP_TO_DATE, decision.status());
        assertFalse(decision.hasUpdate());
    }

    @Test
    public void higherCodeWithOlderNameIsRefusedRatherThanGuessed() {
        // Contradictory signals: the code is newer but the name is older. Refuse
        // instead of picking whichever direction is convenient.
        UpdateDecision decision = UpdateDecision.decide(
                Collections.singletonList(release("v1.3.2", 15, false,
                        apk("IPTV-Plugin-HaP-1.3.2-armeabi-v7a.apk"))),
                new AppVersion(14, "1.3.3"), V7, false);
        assertEquals(UpdateDecision.Status.DOWNGRADE_REFUSED, decision.status());
        assertFalse(decision.hasUpdate());
    }

    @Test
    public void draftsAreIgnored() {
        ReleaseInfo draft = new ReleaseInfo("v9.9.9", "9.9.9", 999, false, true, "",
                Collections.singletonList(apk("IPTV-Plugin-HaP-9.9.9-armeabi-v7a.apk")));
        assertEquals(UpdateDecision.Status.NO_RELEASES,
                UpdateDecision.decide(Collections.singletonList(draft),
                        new AppVersion(14, "1.3.3"), V7, false).status());
    }

    @Test
    public void emptyReleaseListReportsNoReleases() {
        assertEquals(UpdateDecision.Status.NO_RELEASES,
                UpdateDecision.decide(new ArrayList<ReleaseInfo>(), new AppVersion(14, "1.3.3"), V7, false)
                        .status());
        assertEquals(UpdateDecision.Status.NO_RELEASES,
                UpdateDecision.decide(null, new AppVersion(14, "1.3.3"), V7, false).status());
    }

    // ---------------------------------------------------------------- parsing

    @Test
    public void parsesRealisticGitHubPayload() {
        String json = "[{"
                + "\"tag_name\":\"v1.3.4\","
                + "\"name\":\"v1.3.4\","
                + "\"prerelease\":false,"
                + "\"draft\":false,"
                + "\"body\":\"Signed APK for version 1.3.4 (versionCode 15).\","
                + "\"assets\":["
                + "{\"name\":\"IPTV-Plugin-HaP-1.3.4-armeabi-v7a.apk\",\"size\":70797349,"
                + "\"browser_download_url\":\"https://github.com/albersg/IPTV-Plugin-HaP/releases/download/v1.3.4/a.apk\"},"
                + "{\"name\":\"IPTV-Plugin-HaP-1.3.4-arm64-v8a.apk\",\"size\":71000000,"
                + "\"browser_download_url\":\"https://example.invalid/b.apk\"}"
                + "]}]";

        List<ReleaseInfo> releases = ReleaseParser.parseReleases(json);
        assertEquals(1, releases.size());
        ReleaseInfo first = releases.get(0);
        assertEquals("1.3.4", first.versionName());
        assertFalse(first.isPrerelease());
        assertEquals(2, first.assets().size());
        assertEquals(15, first.announcedVersionCode());
        assertNotNull(first.assetForDevice(V7));
        assertEquals("IPTV-Plugin-HaP-1.3.4-armeabi-v7a.apk", first.assetForDevice(V7).name());
    }

    @Test
    public void announcedVersionCodeIsReadFromReleaseNotes() {
        assertEquals(15, ReleaseInfo.parseAnnouncedVersionCode(
                "Signed APK for version 1.3.4 (versionCode 15)."));
        assertEquals(0, ReleaseInfo.parseAnnouncedVersionCode("no code here"));
        assertEquals(0, ReleaseInfo.parseAnnouncedVersionCode(null));
    }

    // ---------------------------------------------------------------- config

    @Test
    public void defaultRepositoryIsTheDistributionFork() {
        assertEquals("albersg/IPTV-Plugin-HaP", UpdateConfig.DEFAULT_REPOSITORY);
        assertEquals("albersg", UpdateConfig.defaultOwner());
        assertEquals("IPTV-Plugin-HaP", UpdateConfig.defaultName());
    }

    @Test
    public void repositoryValidationRejectsMalformedValues() {
        assertTrue(UpdateConfig.isValidRepository("albersg/IPTV-Plugin-HaP"));
        assertFalse(UpdateConfig.isValidRepository(""));
        assertFalse(UpdateConfig.isValidRepository("no-slash"));
        assertFalse(UpdateConfig.isValidRepository("a/b/c"));
        assertFalse(UpdateConfig.isValidRepository("/name"));
        assertFalse(UpdateConfig.isValidRepository("owner/"));
    }

    @Test
    public void intervalIsClampedToAcceptedRange() {
        assertEquals(UpdateConfig.MIN_INTERVAL_MS, UpdateConfig.intervalMillis(0));
        assertEquals(UpdateConfig.MAX_INTERVAL_MS, UpdateConfig.intervalMillis(100000));
        assertEquals(24L * 60L * 60L * 1000L, UpdateConfig.intervalMillis(24));
        assertEquals(24, UpdateConfig.hours(UpdateConfig.intervalMillis(24)));
    }

    @Test
    public void checkIsDueOnlyAfterTheInterval() {
        long day = UpdateConfig.DEFAULT_INTERVAL_MS;
        long now = 10L * day;
        assertTrue("never checked", UpdateConfig.isDue(0, day, now));
        assertTrue("clock moved backwards", UpdateConfig.isDue(now + day, day, now));
        assertFalse("just checked", UpdateConfig.isDue(now - 1000, day, now));
        assertTrue("interval elapsed", UpdateConfig.isDue(now - day, day, now));
    }
}
