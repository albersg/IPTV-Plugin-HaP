package com.streamvault.plugin.hap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a playlist without the proxy.
 *
 * <p>The channel list normally comes from the local HTTP proxy, which means it only exists while the server
 * runs. That is a poor deal for browsing: to see what channels you have, and to know what is on, you would
 * have to start the whole engine first. This reads the same files directly, so the list and the events
 * screen work before the server does.
 *
 * <p>Only AceStream entries are kept: the rest of the app opens channels as {@code acestream://<hash>}, and
 * an HTTP entry has no hash to open.
 */
public final class M3uParser {

    /** One channel of a playlist. */
    public static final class Entry {
        public final String name;
        public final String contentId;

        Entry(String name, String contentId) {
            this.name = name;
            this.contentId = contentId;
        }
    }

    private static final String ACESTREAM_PREFIX = "acestream://";

    private M3uParser() {
    }

    /** Reads a playlist file. A file that cannot be read yields no entries rather than an error. */
    public static List<Entry> parse(File file) {
        if (file == null || !file.isFile()) return new ArrayList<>();
        try (InputStream in = new FileInputStream(file)) {
            return parse(in);
        } catch (IOException unreadable) {
            return new ArrayList<>();
        }
    }

    /** Reads a playlist from a stream. */
    public static List<Entry> parse(InputStream in) throws IOException {
        List<Entry> out = new ArrayList<>();
        if (in == null) return out;
        String pendingName = null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("#EXTINF")) {
                pendingName = nameOf(trimmed);
                continue;
            }
            if (trimmed.startsWith("#")) continue;
            if (pendingName == null) continue;
            String contentId = contentIdOf(trimmed);
            if (contentId != null) out.add(new Entry(pendingName, contentId));
            pendingName = null;
        }
        return out;
    }

    /**
     * The display name: everything after the first comma that is not inside a quoted attribute.
     *
     * <p>Not the last comma: names carry commas ("Canal, con coma"), and the attributes before them may
     * contain them inside quotes too.
     */
    static String nameOf(String extinfLine) {
        boolean inQuotes = false;
        for (int i = 0; i < extinfLine.length(); i++) {
            char c = extinfLine.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                return extinfLine.substring(i + 1).trim();
            }
        }
        return "";
    }

    /** The AceStream hash of a URL, or null when the entry is not an AceStream one. */
    static String contentIdOf(String url) {
        if (url == null) return null;
        String trimmed = url.trim();
        if (!trimmed.startsWith(ACESTREAM_PREFIX)) return null;
        String id = trimmed.substring(ACESTREAM_PREFIX.length());
        // Some playlists carry a query string or a trailing slash after the hash.
        int cut = id.length();
        int query = id.indexOf('?');
        if (query >= 0) cut = query;
        int slash = id.indexOf('/');
        if (slash >= 0 && slash < cut) cut = slash;
        id = id.substring(0, cut).trim();
        return id.isEmpty() ? null : id;
    }
}
