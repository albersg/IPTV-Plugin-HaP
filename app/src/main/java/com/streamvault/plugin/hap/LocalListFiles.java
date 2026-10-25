package com.streamvault.plugin.hap;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The folder this app owns for M3U lists held on the device.
 *
 * <p>It is the app-specific external directory, so {@code adb push} reaches it at
 * {@code /sdcard/Android/data/<package>/files/lists/} and no storage permission is needed to read it.
 * That matters on a television: the box has no document picker, and the file managers that answer
 * {@code GET_CONTENT} are optional installs. This folder is the path that always works.
 *
 * <p>The pure parts take a {@link File} instead of a {@link Context}, so they are pinned by plain
 * JUnit tests.
 */
public final class LocalListFiles {

    /** Directory name under the app's external files directory. */
    public static final String FOLDER = "lists";

    private LocalListFiles() {
    }

    /** The folder, created if needed; null when external storage is not available. */
    public static File folder(Context context) {
        File dir = context.getExternalFilesDir(FOLDER);
        if (dir == null) return null;
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        return dir;
    }

    /** The playlist files in {@code dir}, by name. A missing directory is not an error. */
    public static List<File> scan(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File file : files) {
            if (file.isFile() && LocalSourceFile.hasPlaylistExtension(file.getName())) out.add(file);
        }
        return out;
    }

    /** A file name with anything that would confuse a path or a shell removed. */
    public static String sanitizeName(String name) {
        String cleaned = name == null ? "" : name.trim();
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) cleaned = "lista.m3u";
        if (!LocalSourceFile.hasPlaylistExtension(cleaned)) cleaned = cleaned + ".m3u";
        // A name the filesystem can hold, extension included.
        if (cleaned.length() > 96) {
            String extension = cleaned.substring(cleaned.lastIndexOf('.'));
            cleaned = cleaned.substring(0, 96 - extension.length()) + extension;
        }
        return cleaned;
    }

    /**
     * A name inside {@code dir} that does not overwrite an existing file, by appending " (2)", " (3)"
     * and so on before the extension.
     */
    public static String uniqueName(File dir, String candidate) {
        String name = sanitizeName(candidate);
        if (dir == null) return name;
        if (!new File(dir, name).exists()) return name;

        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            String next = stem + " (" + i + ")" + extension;
            if (!new File(dir, next).exists()) return next;
        }
        return stem + System.currentTimeMillis() + extension;
    }

    /** Human-readable size for a row's third line. */
    public static String describeSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
