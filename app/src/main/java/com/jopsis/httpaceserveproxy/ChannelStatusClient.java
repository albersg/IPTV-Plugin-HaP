package com.jopsis.httpaceserveproxy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

final class ChannelStatusClient {
    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final int READ_TIMEOUT_MS = 20000;

    private ChannelStatusClient() {
    }

    static List<PluginChannels> loadPlugins(Context context) throws Exception {
        String body = get(PlaylistCatalog.BASE_URL + "/statplugin?action=get_plugins", READ_TIMEOUT_MS);
        JSONObject root = new JSONObject(body);
        JSONArray plugins = root.optJSONArray("plugins");
        List<PluginChannels> out = new ArrayList<>();
        if (plugins == null) return out;

        for (int i = 0; i < plugins.length(); i++) {
            JSONObject plugin = plugins.getJSONObject(i);
            String id = plugin.optString("name", "");
            if (PlaylistCatalog.sourceFor(id) == null) continue;

            PluginChannels entry = new PluginChannels(id, PlaylistCatalog.labelFor(context, id));
            JSONArray channels = plugin.optJSONArray("channels");
            if (channels != null) {
                for (int c = 0; c < channels.length(); c++) {
                    JSONObject channel = channels.getJSONObject(c);
                    String name = channel.optString("name", "");
                    String contentId = channel.optString("content_id", "");
                    if (!name.isEmpty() && !contentId.isEmpty()) {
                        entry.channels.add(new Channel(name, contentId));
                    }
                }
            }
            out.add(entry);
        }
        out.sort((left, right) -> Integer.compare(sourceOrder(left.id), sourceOrder(right.id)));
        return out;
    }

    static PeerResult checkPeers(String contentId, int maxWaitSeconds) throws Exception {
        return checkPeers(contentId, maxWaitSeconds, null);
    }

    static PeerResult checkPeers(String contentId, int maxWaitSeconds, Cancellation cancellation) throws Exception {
        String url = PlaylistCatalog.BASE_URL
                + "/statplugin?action=check_peers&content_id="
                + encode(contentId)
                + "&max_wait="
                + Math.max(5, Math.min(30, maxWaitSeconds));
        JSONObject data = new JSONObject(get(url, Math.max(8000, (maxWaitSeconds + 5) * 1000), cancellation));

        PeerResult result = new PeerResult();
        result.success = "success".equals(data.optString("status"));
        result.available = data.optBoolean("available", false);
        result.peers = data.optInt("peers", 0);
        result.httpPeers = data.optInt("http_peers", 0);
        result.totalPeers = data.optInt("total_peers", result.peers + result.httpPeers);
        result.statusText = data.optString("status_text", "unknown");
        result.error = data.optString("error", "");
        return result;
    }

    private static String get(String url, int readTimeoutMs) throws IOException {
        return get(url, readTimeoutMs, null);
    }

    /**
     * Performs the GET, registering the live connection with {@code cancellation} (when given) so
     * that a cancel from another thread closes the socket and makes the blocking read return at
     * once. This is the only way to abort a blocking socket read: an interrupt does not reach it.
     */
    private static String get(String url, int readTimeoutMs, Cancellation cancellation) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod("GET");
        if (cancellation != null) cancellation.register(connection);
        try {
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readAll(input);
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status + ": " + body);
            return body;
        } catch (IOException error) {
            // A cancel closes the socket under the reader, which surfaces as a broken pipe/reset.
            // Report it as a cancellation so callers do not treat it as a real channel failure.
            if (cancellation != null && cancellation.isCancelled()) throw new CancelledException();
            throw error;
        } finally {
            if (cancellation != null) cancellation.unregister(connection);
            connection.disconnect();
        }
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        StringBuilder out = new StringBuilder();
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, decoder))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static int sourceOrder(String id) {
        if (PlaylistCatalog.USER_M3U_PLUGIN_ID.equals(id)) return 0;
        if (PlaylistCatalog.CUSTOM_ID.equals(id)) return 1;
        return 2;
    }

    /**
     * Handle for aborting an in-flight status request.
     *
     * <p>Closing the {@link HttpURLConnection} shuts the socket down and wakes the thread blocked
     * in the read. {@code Future.cancel(true)} cannot do that, so the reference to the live
     * connection has to be kept here. Cancelling also aborts the matching native peer session,
     * because the local proxy observes the client disconnect.
     */
    static final class Cancellation {
        private final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
        private volatile boolean cancelled;

        boolean isCancelled() {
            return cancelled;
        }

        void cancel() {
            cancelled = true;
            HttpURLConnection active = connection.getAndSet(null);
            if (active != null) active.disconnect();
        }

        void register(HttpURLConnection target) throws IOException {
            connection.set(target);
            if (cancelled) {
                connection.compareAndSet(target, null);
                target.disconnect();
                throw new CancelledException();
            }
        }

        void unregister(HttpURLConnection target) {
            connection.compareAndSet(target, null);
        }
    }

    /** Raised when a request was aborted on purpose instead of failing. */
    static final class CancelledException extends IOException {
        CancelledException() {
            super("channel test cancelled");
        }
    }

    static final class PluginChannels {
        final String id;
        final String label;
        final List<Channel> channels = new ArrayList<>();

        PluginChannels(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label + " (" + channels.size() + ")";
        }
    }

    static final class Channel {
        final String name;
        final String contentId;

        Channel(String name, String contentId) {
            this.name = name;
            this.contentId = contentId;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final class PeerResult {
        boolean success;
        boolean available;
        int peers;
        int httpPeers;
        int totalPeers;
        String statusText;
        String error;
    }
}
