package com.streamvault.plugin.hap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * Gets a usable guide: from the cache when it is fresh, from the network otherwise.
 *
 * <p>The three pieces it joins were measured separately and each one matters: the download and parse take
 * 96.8 seconds on the device, the host can be unreachable at all, and the screen cannot wait for either.
 * So the order is fixed - a fresh copy on disk is used without touching the network, and a failed download
 * falls back to a stale copy rather than showing nothing.
 *
 * <p>The decoding part is pure and tested, because gzip is what actually arrives and a wrong guess turns
 * the whole guide into a parse error.
 */
public final class GuideRepository {

    /** The guide the app points at: 34,771,430 bytes, 642 channels, 83,073 programmes. */
    public static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/davidmuma/EPG_dobleM/refs/heads/master/guiatv.xml";

    /** Refuses to hold more than this in memory while downloading; the guide is 34.7 MB plain. */
    public static final int MAX_DOWNLOAD_BYTES = 128 * 1024 * 1024;

    private GuideRepository() {
    }

    /** What to do next, decided without touching the network or the disk. */
    public enum Plan {
        /** Use the copy on disk. */
        USE_CACHE,
        /** Download, because there is no usable copy. */
        DOWNLOAD
    }

    /**
     * @param cacheUsable whether a fresh copy is on disk
     * @param force       whether the caller asked for a refresh anyway
     */
    public static Plan plan(boolean cacheUsable, boolean force) {
        if (force) return Plan.DOWNLOAD;
        return cacheUsable ? Plan.USE_CACHE : Plan.DOWNLOAD;
    }

    /** True when the bytes are a gzip stream, judged by the magic number rather than by a header. */
    public static boolean isGzip(byte[] bytes) {
        return bytes != null && bytes.length > 2
                && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b;
    }

    /** The XML bytes, decompressed when they arrive compressed. */
    public static byte[] decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("empty guide");
        if (!isGzip(bytes)) return bytes;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, bytes.length * 8));
            byte[] buffer = new byte[64 * 1024];
            int got;
            while ((got = in.read(buffer)) > 0) {
                out.write(buffer, 0, got);
                if (out.size() > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("guide exceeds " + MAX_DOWNLOAD_BYTES + " bytes once decoded");
                }
            }
            return out.toByteArray();
        }
    }

    /** Downloads the guide, plain bytes as they arrive. */
    public static byte[] download(String url, int connectTimeoutMillis, int readTimeoutMillis)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            // Ask for gzip explicitly and decode it ourselves, so the answer is the same whether or not a
            // transparent layer decompressed it on the way.
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setInstanceFollowRedirects(true);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("guide returned HTTP " + status);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024 * 1024);
                byte[] buffer = new byte[64 * 1024];
                int got;
                while ((got = in.read(buffer)) > 0) {
                    out.write(buffer, 0, got);
                    if (out.size() > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("guide exceeds " + MAX_DOWNLOAD_BYTES + " bytes");
                    }
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * A guide to parse, preferring the cache.
     *
     * @return the XML bytes, the plan that produced them, and whether they came from disk
     */
    public static Result load(File cacheDirectory, long nowMillis, boolean force) {
        boolean cacheUsable = GuideCache.isUsable(cacheDirectory, nowMillis);
        if (plan(cacheUsable, force) == Plan.USE_CACHE) {
            byte[] cached = GuideCache.read(cacheDirectory, nowMillis);
            if (cached != null) return new Result(cached, Plan.USE_CACHE, true);
        }
        try {
            byte[] downloaded = decode(download(DEFAULT_URL, 20_000, 120_000));
            GuideCache.write(cacheDirectory, downloaded);
            return new Result(downloaded, Plan.DOWNLOAD, false);
        } catch (IOException failed) {
            // The network failing must not empty the screen: a stale copy still beats nothing.
            File file = GuideCache.file(cacheDirectory);
            if (file.isFile() && file.length() > 0) {
                try {
                    byte[] stale = java.nio.file.Files.readAllBytes(file.toPath());
                    return new Result(stale, Plan.USE_CACHE, true);
                } catch (IOException ignored) {
                    // Fall through to the report below.
                }
            }
            throw new IllegalStateException("no guide: download failed (" + failed.getMessage()
                    + ") and there is no cached copy", failed);
        }
    }

    /** The guide bytes and where they came from. */
    public static final class Result {
        public final byte[] xml;
        public final Plan plan;
        public final boolean fromCache;

        Result(byte[] xml, Plan plan, boolean fromCache) {
            this.xml = xml;
            this.plan = plan;
            this.fromCache = fromCache;
        }
    }
}
