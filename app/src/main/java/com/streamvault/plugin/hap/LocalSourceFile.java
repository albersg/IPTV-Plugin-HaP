package com.streamvault.plugin.hap;

import java.io.File;
import java.util.Locale;

/**
 * The {@code localfile:} source kind: an M3U list this device holds, instead of a URL to download.
 *
 * <p>Sources reach the native core as {@code name<TAB>url} lines and the core fetches every one of
 * them. A list the user imported from the device is not reachable that way, so it travels under this
 * prefix and the core reads it from disk instead. Both kinds then take the same path through parsing,
 * merging and EPG, which is why a local list behaves exactly like a remote one everywhere else.
 *
 * <p>Pure on purpose: no Android types and no I/O, so the rules are pinned by plain JUnit tests.
 */
public final class LocalSourceFile {

    /** Marks a value as a local file. Matched case-insensitively, like the native side does. */
    public static final String SCHEME = "localfile:";

    /** Same ceiling as the remote path and the native reader, so both ends reject the same files. */
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private LocalSourceFile() {
    }

    /** True when {@code value} names a file on this device rather than something to download. */
    public static boolean isLocalValue(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (trimmed.length() <= SCHEME.length()) return false;
        return trimmed.regionMatches(true, 0, SCHEME, 0, SCHEME.length());
    }

    /** The absolute path of a local value, or an empty string when it is not one. */
    public static String pathOf(String value) {
        if (!isLocalValue(value)) return "";
        return value.trim().substring(SCHEME.length());
    }

    /** The value to store for {@code file}. */
    public static String valueFor(File file) {
        return SCHEME + file.getAbsolutePath();
    }

    /**
     * A name for the source row. The file name without its extension, which is what the user
     * recognises; a nameless path falls back to the file name or the whole value.
     */
    public static String displayName(String value) {
        String path = pathOf(value);
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.trim();
        return name.isEmpty() ? path.trim() : name;
    }

    /** The extension of a local value, lower-cased, without the dot. */
    public static String extensionOf(String value) {
        String name = new File(pathOf(value)).getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** True when the file name looks like a playlist this app can read. */
    public static boolean hasPlaylistExtension(String fileName) {
        if (fileName == null) return false;
        String name = fileName.trim().toLowerCase(Locale.ROOT);
        return name.endsWith(".m3u") || name.endsWith(".m3u8");
    }
}
