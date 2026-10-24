package com.jopsis.httpaceserveproxy;

import android.content.Context;

import com.jopsis.httpaceserveproxy.update.ReleaseInfo;
import com.jopsis.httpaceserveproxy.update.ReleaseParser;
import com.jopsis.httpaceserveproxy.update.UpdateConfig;
import com.streamvault.plugin.hap.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Reads GitHub Releases over HTTPS, anonymously and with an ETag cache.
 *
 * <p>The updater has no token: it lives inside the anonymous GitHub API limit (60 requests per
 * hour per IP). It therefore sends {@code If-None-Match} with the stored ETag so an unchanged
 * release list costs one conditional request and a {@code 304}, and it reports the rate-limit
 * reset time instead of retrying when the limit is exhausted. The payload is cached in
 * {@link UpdateSettings} so a temporary network failure can still show the last known release.
 *
 * <p>The JSON parsing itself happens in {@link ReleaseParser}, which is pure Java.
 */
public final class UpdateClient {
    public enum Outcome {
        /** The release list was read (fresh, unchanged, or from the local cache). */
        OK,
        /** The anonymous GitHub limit is exhausted; try again after the reported reset. */
        RATE_LIMITED,
        /** The repository does not exist (or is private) to anonymous callers. */
        NOT_FOUND,
        /** No usable network connection. */
        NETWORK_ERROR,
        /** Any other HTTP response. */
        HTTP_ERROR,
        /** The response was not a release list. */
        PARSE_ERROR
    }

    public static final class Result {
        public final Outcome outcome;
        public final List<ReleaseInfo> releases;
        public final String message;
        public final boolean notModified;
        public final boolean fromCache;
        public final long rateLimitResetMillis;

        private Result(
                Outcome outcome,
                List<ReleaseInfo> releases,
                String message,
                boolean notModified,
                boolean fromCache,
                long rateLimitResetMillis
        ) {
            this.outcome = outcome;
            this.releases = releases == null ? Collections.<ReleaseInfo>emptyList() : releases;
            this.message = message == null ? "" : message;
            this.notModified = notModified;
            this.fromCache = fromCache;
            this.rateLimitResetMillis = rateLimitResetMillis;
        }

        private static Result error(Outcome outcome, String message) {
            return new Result(outcome, null, message, false, false, 0L);
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_RELEASES_PER_PAGE = 30;

    private UpdateClient() {
    }

    /** Reads the release list. Blocking: call from a background thread. */
    public static Result check(Context context) {
        String repository = UpdateSettings.repository(context);
        String cachedBody = UpdateSettings.cachedReleases(context);
        String etag = UpdateSettings.etag(context);

        HttpURLConnection connection = null;
        try {
            URL url = new URL(UpdateConfig.API_BASE_URL + "/repos/" + repository + "/releases"
                    + "?per_page=" + MAX_RELEASES_PER_PAGE);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", UpdateConfig.USER_AGENT);
            if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);

            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                if (cachedBody.isEmpty()) {
                    return Result.error(Outcome.HTTP_ERROR, context.getString(R.string.update_error_http, status));
                }
                return new Result(Outcome.OK, parse(cachedBody), "", true, false, 0L);
            }

            if (status == HttpURLConnection.HTTP_OK) {
                String body = readBody(connection.getInputStream());
                List<ReleaseInfo> releases;
                try {
                    releases = ReleaseParser.parseReleases(body);
                } catch (RuntimeException parseError) {
                    return Result.error(Outcome.PARSE_ERROR, context.getString(R.string.update_error_parse));
                }
                UpdateSettings.setEtag(context, connection.getHeaderField("ETag"));
                UpdateSettings.setCachedReleases(context, body);
                return new Result(Outcome.OK, releases, "", false, false, 0L);
            }

            if (status == 403 || status == 429) {
                long resetMillis = rateLimitResetMillis(connection);
                String remaining = connection.getHeaderField("X-RateLimit-Remaining");
                if ("0".equals(remaining) || resetMillis > 0) {
                    return new Result(
                            Outcome.RATE_LIMITED,
                            null,
                            rateLimitMessage(context, resetMillis),
                            false,
                            false,
                            resetMillis
                    );
                }
            }

            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                return Result.error(Outcome.NOT_FOUND, context.getString(R.string.update_error_not_found, repository));
            }
            return Result.error(Outcome.HTTP_ERROR, context.getString(R.string.update_error_http, status));
        } catch (IOException networkError) {
            List<ReleaseInfo> cached = parseCached(cachedBody);
            if (!cached.isEmpty()) {
                return new Result(Outcome.OK, cached, context.getString(R.string.update_message_cached), false, true, 0L);
            }
            return Result.error(Outcome.NETWORK_ERROR, context.getString(R.string.update_error_network));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static List<ReleaseInfo> parse(String body) {
        try {
            return ReleaseParser.parseReleases(body);
        } catch (RuntimeException ignored) {
            return Collections.emptyList();
        }
    }

    private static List<ReleaseInfo> parseCached(String cachedBody) {
        if (cachedBody == null || cachedBody.isEmpty()) return Collections.emptyList();
        try {
            return ReleaseParser.parseReleases(cachedBody);
        } catch (RuntimeException ignored) {
            return Collections.emptyList();
        }
    }

    private static String rateLimitMessage(Context context, long resetMillis) {
        if (resetMillis <= 0L) return context.getString(R.string.update_error_rate_limit_short);
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(resetMillis));
        return context.getString(R.string.update_error_rate_limit, when);
    }

    /** {@code X-RateLimit-Reset} is epoch seconds; 0 when absent or unparsable. */
    private static long rateLimitResetMillis(HttpURLConnection connection) {
        String header = connection.getHeaderField("X-RateLimit-Reset");
        if (header == null || header.trim().isEmpty()) return 0L;
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds <= 0L ? 0L : seconds * 1000L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        try (InputStream input = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) throw new IOException("Response larger than " + MAX_BODY_BYTES + " bytes");
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

}
