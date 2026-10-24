package com.jopsis.httpaceserveproxy;

import android.content.Context;
import android.os.StatFs;

import com.jopsis.httpaceserveproxy.update.ReleaseAsset;
import com.streamvault.plugin.hap.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads a release asset into the app cache, with progress, cancellation and a storage check.
 *
 * <p>The APK is written to {@code cacheDir/updates} so Android may reclaim it, and every failure
 * path deletes the partial file instead of leaving a truncated APK behind for the installer.
 */
public final class UpdateDownloader {
    /** Progress callback; always invoked on the downloading thread. */
    public interface Listener {
        void onProgress(int percent, long receivedBytes, long totalBytes);
    }

    public static final class Result {
        public final boolean success;
        public final boolean cancelled;
        public final File file;
        public final String message;

        private Result(boolean success, boolean cancelled, File file, String message) {
            this.success = success;
            this.cancelled = cancelled;
            this.file = file;
            this.message = message == null ? "" : message;
        }

        static Result success(File file) {
            return new Result(true, false, file, "");
        }

        static Result cancelled(String message) {
            return new Result(false, true, null, message);
        }

        static Result error(String message) {
            return new Result(false, false, null, message);
        }
    }

    /**
     * Headroom on top of the asset size: the installer needs the APK copied into its own session
     * directory, so checking only the asset size would still fail at the last moment.
     */
    public static final long SPACE_MARGIN_BYTES = 32L * 1024L * 1024L;

    private static final String CACHE_DIR_NAME = "updates";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int BUFFER_BYTES = 64 * 1024;

    private UpdateDownloader() {
    }

    /** The directory the updater owns inside the app cache; created when missing. */
    public static File cacheDir(Context context) {
        File directory = new File(context.getCacheDir(), CACHE_DIR_NAME);
        if (!directory.isDirectory()) directory.mkdirs();
        return directory;
    }

    /** Free bytes on the filesystem that holds {@code directory}, or 0 when unknown. */
    public static long freeBytes(File directory) {
        if (directory == null) return 0L;
        try {
            return new StatFs(directory.getAbsolutePath()).getAvailableBytes();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    public static boolean hasSpaceFor(File directory, long requiredBytes) {
        long free = freeBytes(directory);
        if (free <= 0L) return true; // Unknown: let the download try instead of blocking the user.
        return free >= Math.max(0L, requiredBytes) + SPACE_MARGIN_BYTES;
    }

    /** Human-readable size for the update screen, e.g. {@code 70.4 MB}. */
    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kilobytes = bytes / 1024.0;
        if (kilobytes < 1024.0) return String.format(Locale.US, "%.0f KB", kilobytes);
        double megabytes = kilobytes / 1024.0;
        if (megabytes < 1024.0) return String.format(Locale.US, "%.1f MB", megabytes);
        return String.format(Locale.US, "%.2f GB", megabytes / 1024.0);
    }

    /**
     * Downloads {@code asset}. Blocking: call from a background thread.
     *
     * @param cancel observed between chunks; setting it aborts and deletes the partial file
     */
    public static Result download(Context context, ReleaseAsset asset, AtomicBoolean cancel, Listener listener) {
        if (asset == null || !asset.isUsable()) {
            return Result.error(context.getString(R.string.update_error_asset_unavailable));
        }

        File directory = cacheDir(context);
        long required = asset.sizeBytes();
        if (!hasSpaceFor(directory, required)) {
            return Result.error(context.getString(
                    R.string.update_error_storage_insufficient,
                    formatBytes(freeBytes(directory)),
                    formatBytes(required + SPACE_MARGIN_BYTES)
            ));
        }

        clearDownloadedFiles(directory);
        File target = new File(directory, sanitizeFileName(asset.name()));

        HttpURLConnection connection = null;
        long received = 0L;
        try {
            URL url = new URL(asset.downloadUrl());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("User-Agent", "HaP-Updater");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                return Result.error(context.getString(R.string.update_error_download_http, status));
            }

            long contentLength = connection.getContentLengthLong();
            long total = contentLength > 0 ? contentLength : required;
            if (total > 0L && !hasSpaceFor(directory, total)) {
                return Result.error(context.getString(
                        R.string.update_error_storage_insufficient,
                        formatBytes(freeBytes(directory)),
                        formatBytes(total + SPACE_MARGIN_BYTES)
                ));
            }

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                int read;
                int lastPercent = -1;
                while ((read = input.read(buffer)) != -1) {
                    if (cancel != null && cancel.get()) {
                        output.close();
                        delete(target);
                        return Result.cancelled(context.getString(R.string.update_error_download_cancelled));
                    }
                    output.write(buffer, 0, read);
                    received += read;
                    if (total > 0L) {
                        int percent = (int) Math.min(100L, (received * 100L) / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            if (listener != null) listener.onProgress(percent, received, total);
                        }
                    }
                }
            }

            if (cancel != null && cancel.get()) {
                delete(target);
                return Result.cancelled(context.getString(R.string.update_error_download_cancelled));
            }
            if (total > 0L && received < total) {
                delete(target);
                return Result.error(context.getString(R.string.update_error_download_incomplete));
            }
            if (listener != null) listener.onProgress(100, received, total);
            return Result.success(target);
        } catch (IOException networkError) {
            delete(target);
            if (cancel != null && cancel.get()) {
                return Result.cancelled(context.getString(R.string.update_error_download_cancelled));
            }
            return Result.error(context.getString(R.string.update_error_network));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Deletes one downloaded file, ignoring failures. */
    public static void delete(File file) {
        if (file == null) return;
        try {
            if (file.isFile()) file.delete();
        } catch (RuntimeException ignored) {
        }
    }

    /** Deletes every APK the updater downloaded earlier. */
    public static void clearDownloadedFiles(File directory) {
        if (directory == null) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file != null && file.isFile()) delete(file);
        }
    }

    /** Only the published asset name survives; the path always stays inside the cache dir. */
    static String sanitizeFileName(String name) {
        String value = name == null ? "" : name.trim();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            boolean allowed = (current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '.' || current == '-' || current == '_';
            out.append(allowed ? current : '_');
        }
        String sanitized = out.toString();
        if (!sanitized.toLowerCase(Locale.US).endsWith(".apk")) sanitized = sanitized + ".apk";
        return sanitized.isEmpty() || ".apk".equals(sanitized) ? "hap-update.apk" : sanitized;
    }
}
