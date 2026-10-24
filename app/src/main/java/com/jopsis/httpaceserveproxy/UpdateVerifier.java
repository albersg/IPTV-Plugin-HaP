package com.jopsis.httpaceserveproxy;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import com.streamvault.plugin.hap.R;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Verifies a downloaded APK before it is handed to the system installer.
 *
 * <p>This is the updater's single most important safety check. A file that looks like a newer
 * release is not necessarily installable: Android identifies an app by package name <em>and</em>
 * signing certificate, so an APK signed with a different key can never replace the installed app.
 * Handing one to the installer either fails with {@code INSTALL_FAILED_UPDATE_INCOMPATIBLE} or, if
 * the user is talked into uninstalling first, silently deletes every source, custom channel and
 * setting. So the certificate is compared before install, and a mismatch stops the flow with an
 * explicit explanation instead of an installer error code.
 *
 * <p>Checks, in order: the file parses as an APK; it is the same package as the running app; its
 * {@code versionCode} is at least the advertised one (which also rules out a downgrade); every
 * signer certificate matches the installed app's signers exactly.
 */
public final class UpdateVerifier {
    public static final class Result {
        public final boolean ok;
        public final boolean signatureMismatch;
        public final String message;
        public final int versionCode;
        public final String versionName;

        private Result(boolean ok, boolean signatureMismatch, String message, int versionCode, String versionName) {
            this.ok = ok;
            this.signatureMismatch = signatureMismatch;
            this.message = message == null ? "" : message;
            this.versionCode = versionCode;
            this.versionName = versionName == null ? "" : versionName;
        }

        static Result ok(int versionCode, String versionName) {
            return new Result(true, false, "", versionCode, versionName);
        }

        static Result error(String message) {
            return new Result(false, false, message, 0, "");
        }

        static Result signatureMismatch(String message, int versionCode, String versionName) {
            return new Result(false, true, message, versionCode, versionName);
        }
    }

    private UpdateVerifier() {
    }

    /**
     * Verifies {@code apk}. Blocking (it reads and parses the package): call from a background
     * thread.
     *
     * @param minimumVersionCode the lowest acceptable {@code versionCode}; the advertised code, or
     *     the installed one when the release does not state it
     */
    public static Result verify(Context context, File apk, int minimumVersionCode) {
        if (apk == null || !apk.isFile() || apk.length() <= 0L) {
            return Result.error(context.getString(R.string.update_error_verify_parse));
        }

        PackageManager packageManager = context.getPackageManager();
        PackageInfo archive;
        try {
            archive = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(), signingFlags());
        } catch (RuntimeException parseError) {
            archive = null;
        }
        if (archive == null) return Result.error(context.getString(R.string.update_error_verify_parse));

        String packageName = archive.packageName == null ? "" : archive.packageName;
        if (!context.getPackageName().equals(packageName)) {
            return Result.error(context.getString(R.string.update_error_verify_package, packageName));
        }

        long archiveVersionCode = versionCode(archive);
        if (archiveVersionCode < Math.max(0, minimumVersionCode)) {
            return Result.error(context.getString(
                    R.string.update_error_verify_version,
                    archiveVersionCode,
                    Math.max(0, minimumVersionCode)
            ));
        }

        Set<String> archiveSigners = digestSignatures(signersOf(archive));
        Set<String> installedSigners = installedSignerDigests(packageManager, context.getPackageName());
        if (archiveSigners.isEmpty() || installedSigners.isEmpty() || !archiveSigners.equals(installedSigners)) {
            return Result.signatureMismatch(
                    context.getString(R.string.update_error_signature_mismatch),
                    (int) archiveVersionCode,
                    archive.versionName == null ? "" : archive.versionName
            );
        }

        return Result.ok(
                (int) archiveVersionCode,
                archive.versionName == null ? "" : archive.versionName.trim()
        );
    }

    /**
     * True when the installed app and this APK share exactly the same signer certificates.
     * Exposed for the update screen's detail line and for tests that do not go through
     * {@link #verify}.
     */
    public static boolean sameSigners(Context context, File apk) {
        PackageManager packageManager = context.getPackageManager();
        PackageInfo archive;
        try {
            archive = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(), signingFlags());
        } catch (RuntimeException parseError) {
            return false;
        }
        if (archive == null) return false;
        Set<String> archiveSigners = digestSignatures(signersOf(archive));
        Set<String> installedSigners = installedSignerDigests(packageManager, context.getPackageName());
        return !archiveSigners.isEmpty() && archiveSigners.equals(installedSigners);
    }

    private static int signingFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signersOf(PackageInfo info) {
        if (info == null) return new Signature[0];
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signingInfo = info.signingInfo;
            if (signingInfo == null) return new Signature[0];
            return signingInfo.getApkContentsSigners();
        }
        return info.signatures == null ? new Signature[0] : info.signatures;
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return info.getLongVersionCode();
        return info.versionCode;
    }

    private static Set<String> installedSignerDigests(PackageManager packageManager, String packageName) {
        try {
            PackageInfo installed = packageManager.getPackageInfo(packageName, signingFlags());
            return digestSignatures(signersOf(installed));
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return new LinkedHashSet<>();
        }
    }

    /** SHA-256 of each signer certificate, so certificates are compared without keeping them. */
    private static Set<String> digestSignatures(Signature[] signatures) {
        Set<String> digests = new LinkedHashSet<>();
        if (signatures == null || signatures.length == 0) return digests;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Signature signature : signatures) {
                if (signature == null) continue;
                byte[] hash = digest.digest(signature.toByteArray());
                digests.add(toHex(hash));
            }
        } catch (NoSuchAlgorithmException ignored) {
            return new LinkedHashSet<>();
        }
        return digests;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16));
            out.append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }
}
