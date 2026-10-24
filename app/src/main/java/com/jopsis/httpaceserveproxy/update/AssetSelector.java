package com.jopsis.httpaceserveproxy.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picks the release asset this device can actually run.
 *
 * <p>Pure Java so the ABI rules can be unit tested on the JVM. The rules:
 *
 * <ul>
 *   <li>Walk the device's own ABI list in order (Android's priority order) and take the first
 *       exact per-ABI asset, e.g. {@code IPTV-Plugin-HaP-1.3.4-armeabi-v7a.apk}.
 *   <li>Only when there is no exact match, fall back to the universal APK
 *       ({@code IPTV-Plugin-HaP.apk}, else {@code IPTV-Plugin-HaP-<version>.apk}).
 *   <li>Never return an asset whose ABI suffix the device cannot run.
 * </ul>
 */
public final class AssetSelector {
    /** Stable universal asset name published by the release workflow. */
    public static final String UNIVERSAL_ASSET_NAME = "IPTV-Plugin-HaP.apk";
    private static final String APK_SUFFIX = ".apk";

    /**
     * ABI tokens Android devices report, longest first. ABI names contain dashes themselves
     * ({@code arm64-v8a}, {@code armeabi-v7a}), so they are matched as whole suffixes instead of
     * splitting the asset name on {@code -}.
     */
    private static final String[] KNOWN_ABIS = {
            "arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "riscv64", "mips64", "mips", "x86"
    };

    private AssetSelector() {
    }

    /**
     * @param assets release assets, in published order
     * @param deviceAbis the device ABI list in priority order, e.g. {@code Build.SUPPORTED_ABIS}
     * @return the asset to download, or {@code null} when the device can run none of them
     */
    public static ReleaseAsset select(List<ReleaseAsset> assets, List<String> deviceAbis) {
        if (assets == null || assets.isEmpty()) return null;
        List<String> abis = normalizeAbis(deviceAbis);

        ReleaseAsset exact = selectExactAbi(assets, abis);
        if (exact != null) return exact;
        return selectUniversal(assets);
    }

    /** The exact per-ABI asset, honouring the device's ABI priority order, or {@code null}. */
    public static ReleaseAsset selectExactAbi(List<ReleaseAsset> assets, List<String> deviceAbis) {
        if (assets == null) return null;
        List<String> abis = normalizeAbis(deviceAbis);
        for (String abi : abis) {
            for (ReleaseAsset asset : assets) {
                if (!asset.isUsable()) continue;
                if (abi.equals(abiToken(asset.name()))) return asset;
            }
        }
        return null;
    }

    /** The universal APK, or {@code null} when the release does not publish one. */
    public static ReleaseAsset selectUniversal(List<ReleaseAsset> assets) {
        if (assets == null) return null;
        for (ReleaseAsset asset : assets) {
            if (asset.isUsable() && UNIVERSAL_ASSET_NAME.equalsIgnoreCase(asset.name())) return asset;
        }
        for (ReleaseAsset asset : assets) {
            if (!asset.isUsable()) continue;
            if (abiToken(asset.name()).isEmpty() && looksLikeUpdaterAsset(asset.name())) return asset;
        }
        return null;
    }

    /**
     * True when the asset name carries no ABI the device cannot run. The universal APK is always
     * runnable; a per-ABI asset is runnable only when its ABI is in the device list.
     */
    public static boolean isRunnableOn(String assetName, List<String> deviceAbis) {
        String token = abiToken(assetName);
        if (token.isEmpty()) return true;
        return normalizeAbis(deviceAbis).contains(token);
    }

    /**
     * The ABI token in an asset name, or the empty string for a universal name.
     *
     * <p>The token is the ABI suffix of the file name after {@code .apk} is removed
     * ({@code IPTV-Plugin-HaP-1.3.4-armeabi-v7a.apk} gives {@code armeabi-v7a}). Matching whole
     * suffixes is what keeps {@code armeabi} from matching {@code armeabi-v7a}.
     */
    public static String abiToken(String assetName) {
        if (assetName == null) return "";
        String name = assetName.trim().toLowerCase(Locale.US);
        if (!name.endsWith(APK_SUFFIX)) return "";
        String stem = name.substring(0, name.length() - APK_SUFFIX.length());

        for (String abi : KNOWN_ABIS) {
            if (stem.endsWith("-" + abi)) return abi;
        }

        // Unknown but ABI-shaped token (for example -armv7): treat it as an ABI so it can never be
        // mistaken for the universal APK.
        int dash = stem.lastIndexOf('-');
        if (dash < 0 || dash == stem.length() - 1) return "";
        String token = stem.substring(dash + 1);
        return looksLikeAbi(token) ? token : "";
    }

    private static boolean looksLikeUpdaterAsset(String assetName) {
        return assetName.toLowerCase(Locale.US).startsWith("iptv-plugin-hap");
    }

    private static boolean looksLikeAbi(String token) {
        if (token == null || token.isEmpty()) return false;
        String value = token.toLowerCase(Locale.US);
        if ("arm64-v8a".equals(value) || "armeabi-v7a".equals(value) || "armeabi".equals(value)) return true;
        return value.startsWith("arm") || value.startsWith("x86") || value.startsWith("mips") || value.startsWith("riscv");
    }

    private static List<String> normalizeAbis(List<String> deviceAbis) {
        List<String> out = new ArrayList<>();
        if (deviceAbis == null) return out;
        for (String abi : deviceAbis) {
            if (abi == null) continue;
            String value = abi.trim().toLowerCase(Locale.US);
            if (!value.isEmpty() && !out.contains(value)) out.add(value);
        }
        return out;
    }
}
