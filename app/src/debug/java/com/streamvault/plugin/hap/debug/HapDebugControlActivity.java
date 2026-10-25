package com.streamvault.plugin.hap.debug;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import com.jopsis.httpaceserveproxy.HapBridge;
import com.streamvault.plugin.hap.ChannelGuideMatcher;
import com.streamvault.plugin.hap.EpgWindow;
import com.streamvault.plugin.hap.EventCatalog;
import com.streamvault.plugin.hap.LocalListFiles;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Debug-only, ADB-drivable control surface for HaP.
 *
 * <p>This exists so the runtime can be started, stopped and inspected from a
 * script without walking the TV UI with a D-pad. It is declared in
 * {@code src/debug/AndroidManifest.xml}, so it is not part of a release APK:
 * release builds contain neither this class' manifest entry nor an exported
 * control surface.
 *
 * <p>It is an Activity rather than a receiver on purpose: starting an Activity
 * puts the app in the foreground, which keeps the subsequent foreground-service
 * start clear of the Android 12+ background-start restrictions.
 *
 * <p>Usage:
 * <pre>
 * adb shell am start -W -n com.streamvault.plugin.hap/.debug.HapDebugControlActivity --es op start
 * adb shell am start -W -n com.streamvault.plugin.hap/.debug.HapDebugControlActivity --es op status
 * </pre>
 * Supported {@code op} values: {@code start}, {@code stop}, {@code restart},
 * {@code restart_proxy}, {@code status}, {@code ipfs_on}, {@code ipfs_off},
 * {@code server_mode_on}, {@code server_mode_off}, {@code wait_ready}.
 */
public final class HapDebugControlActivity extends Activity {
    private static final String TAG = "HapDebugControl";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String op = getIntent() == null ? null : getIntent().getStringExtra("op");
        // Every op runs off the main thread: HapBridge.status() enumerates network
        // interfaces and waitForProxyReady() opens sockets, so calling them from the
        // UI thread throws NetworkOnMainThreadException.
        new Thread(() -> execute(op), "hap-debug-control").start();
    }

    private void execute(String op) {
        String result;
        try {
            result = run(op);
        } catch (Throwable error) {
            Log.e(TAG, "op failed: " + op, error);
            result = "ERROR " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        // A single line per invocation keeps adb-driven runs easy to grep.
        Log.i(TAG, "op=" + op + " -> " + result);
        runOnUiThread(this::finish);
    }

    /** The guide the app points at, the one measured at 34.7 MB and 83,073 programmes. */
    private static final String GUIDE_URL =
            "https://raw.githubusercontent.com/davidmuma/EPG_dobleM/refs/heads/master/guiatv.xml";

    /**
     * Runs the real guide through the real chain on this device and reports numbers.
     *
     * <p>This is the measurement the events feature was built on but never had: the parser was proven
     * against fixtures and a synthetic 30,000-programme guide, never against the 34.7 MB file the app
     * actually reads, on the 2 GB box that has to survive it.
     */
    private String measureGuide() {
        long began = System.currentTimeMillis();
        Runtime runtime = Runtime.getRuntime();

        List<String> names = new ArrayList<>();
        for (File file : LocalListFiles.scan(LocalListFiles.folder(this))) {
            try (InputStream in = new java.io.FileInputStream(file)) {
                byte[] raw = new byte[(int) Math.min(file.length(), 4L * 1024 * 1024)];
                int got = in.read(raw);
                for (String line : new String(raw, 0, Math.max(got, 0), StandardCharsets.UTF_8).split("\n")) {
                    if (line.startsWith("#EXTINF")) {
                        int comma = line.lastIndexOf(',');
                        if (comma > 0) names.add(line.substring(comma + 1).trim());
                    }
                }
            } catch (Exception ignored) {
                // A file we cannot read simply contributes no names.
            }
        }
        long readPlaylistMs = System.currentTimeMillis() - began;

        long parsedAt = System.currentTimeMillis();
        long bytes = 0;
        EpgWindow window;
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(GUIDE_URL).openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(120_000);
            connection.setRequestProperty("Accept-Encoding", "gzip");
            bytes = connection.getContentLength();
            InputStream in = connection.getInputStream();
            if ("gzip".equals(connection.getContentEncoding())) in = new GZIPInputStream(in);
            window = EpgWindow.parse(in, parsedAt, 3_600_000L, 6 * 3_600_000L);
        } catch (Exception error) {
            return "ERROR guide download failed: " + error;
        }
        long parseMs = System.currentTimeMillis() - parsedAt;

        long matchAt = System.currentTimeMillis();
        int matched = ChannelGuideMatcher
                .matchAll(names, name -> name, name -> name, window.channels(), null).size();
        long matchMs = System.currentTimeMillis() - matchAt;

        long now = System.currentTimeMillis();
        List<EventCatalog.Event> events = EventCatalog.onNow(window, new java.util.LinkedHashMap<>(), now);

        return "guide bytes=" + bytes
                + " channels=" + window.channels().size()
                + " programmes=" + window.programmes().size()
                + " playlistNames=" + names.size()
                + " matched=" + matched
                + " events=" + events.size()
                + " parseMs=" + parseMs
                + " matchMs=" + matchMs
                + " playlistMs=" + readPlaylistMs
                + " heapUsedMb=" + ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
                + " totalMs=" + (System.currentTimeMillis() - began);
    }

    private String run(String op) {
        if (op == null) return "ERROR missing --es op";

        switch (op) {
            case "start":
                HapBridge.start(this);
                return "started";
            case "stop":
                HapBridge.stop(this);
                return "stopped";
            case "restart":
                HapBridge.restart(this);
                return "restarted";
            case "restart_proxy":
                HapBridge.restartProxy(this);
                return "proxy_restarted";
            case "ipfs_on":
                HapBridge.setIpfsEnabled(this, true);
                return "ipfs_on";
            case "ipfs_off":
                HapBridge.setIpfsEnabled(this, false);
                return "ipfs_off";
            case "server_mode_on":
                HapBridge.setServerModeEnabled(this, true);
                return "server_mode_on";
            case "server_mode_off":
                HapBridge.setServerModeEnabled(this, false);
                return "server_mode_off";
            case "guide":
                return measureGuide();
            case "wait_ready": {
                // Measures how long the proxy takes to answer, which is the
                // number that matters for channel-change responsiveness.
                long began = System.currentTimeMillis();
                boolean ready = HapBridge.waitForProxyReady(30_000);
                long elapsed = System.currentTimeMillis() - began;
                return "ready=" + ready + " afterMs=" + elapsed;
            }
            case "status":
                return describeStatus();
            default:
                return "ERROR unknown op: " + op;
        }
    }

    private String describeStatus() {
        HapBridge.HapStatus status = HapBridge.status(this);
        return "{desiredRunning=" + status.desiredRunning
                + ", aceRunning=" + status.aceRunning
                + ", proxyRunning=" + status.proxyRunning
                + ", ipfsEnabled=" + status.ipfsEnabled
                + ", ipfsRunning=" + status.ipfsRunning
                + ", ipfsPhase=" + status.ipfsPhase
                + ", serverModeEnabled=" + status.serverModeEnabled
                + ", externalPlayerServerEnabled=" + status.externalPlayerServerEnabled
                + ", phase=" + status.phase
                + ", lastError=" + (status.lastError.isEmpty() ? "none" : status.lastError)
                + ", ipfsError=" + (status.ipfsError.isEmpty() ? "none" : status.ipfsError)
                + ", endpoints=" + (status.endpoints == null ? 0 : status.endpoints.size())
                + "}";
    }
}
