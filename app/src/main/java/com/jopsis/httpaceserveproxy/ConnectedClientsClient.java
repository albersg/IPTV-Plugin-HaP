package com.jopsis.httpaceserveproxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ConnectedClientsClient {
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 3500;

    private ConnectedClientsClient() {
    }

    static Snapshot load() throws Exception {
        return parse(get(PlaylistCatalog.BASE_URL + "/stat?action=get_clients"));
    }

    /**
     * Reads one proxy answer: the client rows and the transport of each upstream.
     *
     * <p>Both live in the same response on purpose. Two requests would paint a client list and a
     * transport list that were measured at different instants, and the panel would have to explain
     * a mismatch nobody can act on.
     */
    static Snapshot parse(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray clients = root.optJSONArray("clients_data");
        List<Client> out = new ArrayList<>();
        if (clients != null) {
            for (int i = 0; i < clients.length(); i++) {
                JSONObject item = clients.optJSONObject(i);
                if (item == null) continue;
                String ip = item.optString("clientIP", "");
                String userAgent = item.optString("userAgent", "");
                String channel = item.optString("channelName", "");
                double megabytes = item.optDouble("megabytesSent", item.optDouble("bytesSent", 0) / (1024.0 * 1024.0));
                out.add(new Client(
                        ip.isEmpty() ? "-" : ip,
                        deviceName(userAgent),
                        channel.isEmpty() ? "-" : channel,
                        Math.max(0, megabytes)));
            }
        }
        List<Transport> transports = new ArrayList<>();
        JSONArray streams = root.optJSONArray("streams");
        if (streams != null) {
            for (int i = 0; i < streams.length(); i++) {
                JSONObject item = streams.optJSONObject(i);
                if (item == null) continue;
                transports.add(new Transport(
                        item.optString("channel", ""),
                        item.optInt("clients", 0),
                        Math.max(0.0, item.optDouble("bitrateBps", 0.0)),
                        Math.max(0L, item.optLong("continuityErrors", 0)),
                        Math.max(0L, item.optLong("syncErrors", 0)),
                        item.optLong("lastPayloadAgeMs", -1),
                        item.optBoolean("stalled", false),
                        item.optBoolean("synchronized", false)));
            }
        }
        return new Snapshot(out, transports);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = readAll(input);
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IOException("HTTP " + status + ": " + body);
        return body;
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

    private static String deviceName(String userAgent) {
        if (userAgent == null || userAgent.trim().isEmpty()) return "Desconocido";
        String value = userAgent.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("tivimate")) return "TiviMate";
        if (lower.contains("ott navigator")) return "OTT Navigator";
        if (lower.contains("vlc")) return "VLC";
        if (lower.contains("kodi")) return "Kodi";
        if (lower.contains("exoplayer")) return "ExoPlayer";
        if (lower.contains("lavf")) return "FFmpeg";
        if (lower.contains("gstreamer")) return "GStreamer";
        if (lower.contains("okhttp")) return "OkHttp";
        return value.length() <= 28 ? value : value.substring(0, 25) + "...";
    }

    /** One proxy answer: what the clients read, and what the transport of each upstream did. */
    static final class Snapshot {
        final List<Client> clients;
        final List<Transport> transports;

        Snapshot(List<Client> clients, List<Transport> transports) {
            this.clients = clients;
            this.transports = transports;
        }
    }

    /**
     * Transport counters of one upstream, exactly as the proxy measured them.
     *
     * <p>These are transport numbers, not playback numbers: a clean stream can still freeze in the
     * player, and {@code stalled} means the proxy stopped receiving payload, not that the screen is
     * black (S4 of odd/tasks/hap-transport-health.md).
     */
    static final class Transport {
        final String channel;
        final int clients;
        final double bitrateBps;
        final long continuityErrors;
        final long syncErrors;
        final long lastPayloadAgeMs;
        final boolean stalled;
        final boolean synced;

        Transport(String channel, int clients, double bitrateBps, long continuityErrors,
                  long syncErrors, long lastPayloadAgeMs, boolean stalled, boolean synced) {
            this.channel = channel;
            this.clients = clients;
            this.bitrateBps = bitrateBps;
            this.continuityErrors = continuityErrors;
            this.syncErrors = syncErrors;
            this.lastPayloadAgeMs = lastPayloadAgeMs;
            this.stalled = stalled;
            this.synced = synced;
        }

        /** Arrival rate in megabits per second, the unit an operator reads. */
        double megabitsPerSecond() {
            return bitrateBps / 1_000_000.0;
        }
    }

    static final class Client {
        final String ip;
        final String device;
        final String channel;
        final double megabytes;

        Client(String ip, String device, String channel, double megabytes) {
            this.ip = ip;
            this.device = device;
            this.channel = channel;
            this.megabytes = megabytes;
        }
    }
}
