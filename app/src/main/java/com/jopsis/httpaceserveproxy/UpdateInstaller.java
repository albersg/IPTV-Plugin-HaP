package com.jopsis.httpaceserveproxy;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.streamvault.plugin.hap.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hands a verified APK to the system {@link PackageInstaller}.
 *
 * <p>Nothing here installs silently: the session is committed to the system installer, which asks
 * the user to confirm on the TV with the remote. Android 8+ also requires the user to grant
 * "install unknown apps" for HaP; when that grant is missing the caller is told so it can open the
 * matching settings screen instead of failing with an installer error code.
 */
public final class UpdateInstaller {
    /** Broadcast action carrying the installer status back to the app. */
    public static final String ACTION_INSTALL_STATUS = "com.jopsis.httpaceserveproxy.action.UPDATE_INSTALL_STATUS";

    private static final int REQUEST_CODE = 4101;
    private static final String SESSION_NAME = "hap-update.apk";
    private static final int BUFFER_BYTES = 64 * 1024;

    /** Result callbacks, invoked on the thread that runs {@link #install}. */
    public interface Callback {
        void onSessionOpened();

        void onSessionFailed(String message);
    }

    private UpdateInstaller() {
    }

    /** True when the user has allowed HaP to install packages (Android 8+ gate). */
    public static boolean canInstallPackages(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        return context.getPackageManager().canRequestPackageInstalls();
    }

    /** The per-app "install unknown apps" settings screen. */
    public static Intent unknownSourcesSettingsIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Streams {@code apk} into a full-install session and commits it to the system installer.
     *
     * <p>Blocking: call from a background thread. The status comes back through
     * {@link UpdateInstallReceiver}.
     */
    public static void install(Context context, File apk, Callback callback) {
        PackageInstaller.Session session = null;
        try {
            if (apk == null || !apk.isFile() || apk.length() <= 0L) {
                if (callback != null) callback.onSessionFailed(context.getString(R.string.update_error_verify_parse));
                return;
            }

            PackageManager packageManager = context.getPackageManager();
            PackageInstaller installer = packageManager.getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setInstallReason(PackageManager.INSTALL_REASON_USER);

            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (OutputStream output = session.openWrite(SESSION_NAME, 0L, apk.length());
                 InputStream input = new FileInputStream(apk)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                session.fsync(output);
            }

            session.commit(statusIntentSender(context));
            if (callback != null) callback.onSessionOpened();
        } catch (Exception installError) {
            if (callback != null) {
                String reason = installError.getMessage();
                callback.onSessionFailed(context.getString(
                        R.string.update_error_install_session,
                        reason == null || reason.isEmpty() ? installError.toString() : reason
                ));
            }
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /**
     * The installer answers through this {@link PendingIntent}.
     *
     * <p>It targets an explicit, non-exported receiver in this app, so the status still arrives
     * when the settings activity is not in front or its process was reclaimed while the system
     * installer was showing its UI. The pending intent must be mutable: the installer fills the
     * result extras into it.
     */
    private static IntentSender statusIntentSender(Context context) {
        Intent statusIntent = new Intent(context, UpdateInstallReceiver.class)
                .setAction(ACTION_INSTALL_STATUS)
                .setPackage(context.getPackageName());
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );
        return pendingIntent.getIntentSender();
    }
}
