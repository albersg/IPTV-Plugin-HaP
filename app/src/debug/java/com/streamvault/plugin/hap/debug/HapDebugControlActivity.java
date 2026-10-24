package com.streamvault.plugin.hap.debug;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import com.jopsis.httpaceserveproxy.HapBridge;

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
