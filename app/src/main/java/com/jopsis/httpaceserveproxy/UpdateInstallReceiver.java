package com.jopsis.httpaceserveproxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/**
 * Receives the {@link PackageInstaller} status for an update session and stores it.
 *
 * <p>The system installer shows its own UI, so the settings activity can be paused, recreated or
 * its process reclaimed while the user decides. Persisting the status means the update screen can
 * report the real outcome when it comes back, instead of losing it.
 *
 * <p>Declared non-exported in the manifest: the installer delivers the status through the
 * {@link android.app.PendingIntent} the app itself created, so no other app can post results here.
 */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        UpdateSettings.setInstallStatus(context, status);

        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return;

        Intent confirmation = pendingUserAction(intent);
        if (confirmation == null) return;
        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(confirmation);
        } catch (RuntimeException ignored) {
            // If the system refuses the hand-off, the stored status still explains what happened.
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent pendingUserAction(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }
}
