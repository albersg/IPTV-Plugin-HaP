package com.streamvault.plugin.hap;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Keeps the downloaded guide on disk so the events screen does not have to fetch 34.7 MB to open.
 *
 * <p>Measured on the device: fetching and parsing the guide took 96.8 seconds. A screen cannot wait for
 * that, and the device also failed to reach the guide host at all on a later run - IPv6 connect timeout.
 * With a copy on disk, both cases stop mattering: the events screen reads the file, and the download
 * happens in the background at most twice a day.
 *
 * <p>The freshness rule is pure and tested, so "when do we download again" is not a guess spread through
 * the code.
 */
public final class GuideCache {

    /** How long a downloaded guide stays good enough to show. */
    public static final long FRESH_MILLIS = 6L * 60 * 60 * 1000;

    /** The file name inside the app's cache folder. */
    public static final String FILE_NAME = "guide.xml";

    private GuideCache() {
    }

    /** Where the guide lives. */
    public static File file(File directory) {
        return new File(directory, FILE_NAME);
    }

    /** True when a copy saved at {@code savedAtMillis} may still be shown at {@code nowMillis}. */
    public static boolean isFresh(long savedAtMillis, long nowMillis) {
        if (savedAtMillis <= 0) return false;
        long age = nowMillis - savedAtMillis;
        // A clock that moved backwards is not evidence of freshness, so a negative age is not fresh.
        return age >= 0 && age < FRESH_MILLIS;
    }

    /** True when the cache holds a guide young enough to show. */
    public static boolean isUsable(File directory, long nowMillis) {
        File file = file(directory);
        return file.isFile() && file.length() > 0 && isFresh(file.lastModified(), nowMillis);
    }

    /** Saves the guide, replacing any previous copy. */
    public static void write(File directory, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("refusing to cache an empty guide");
        File target = file(directory);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
        File temporary = new File(target.getPath() + ".part");
        Files.write(temporary.toPath(), bytes);
        // A guide half written must never be read as if it were whole, so the swap is atomic.
        if (!temporary.renameTo(target)) {
            Files.deleteIfExists(temporary.toPath());
            throw new IOException("could not replace " + target);
        }
    }

    /** The cached guide, or null when there is none or it is stale. */
    public static byte[] read(File directory, long nowMillis) {
        File file = file(directory);
        if (!isUsable(directory, nowMillis)) return null;
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** How old the cached copy is, or -1 when there is none. For the screen to say "actualizado hace…". */
    public static long ageMillis(File directory, long nowMillis) {
        File file = file(directory);
        if (!file.isFile() || file.length() == 0) return -1;
        return nowMillis - file.lastModified();
    }
}
