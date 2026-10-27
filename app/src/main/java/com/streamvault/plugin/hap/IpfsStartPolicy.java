package com.streamvault.plugin.hap;

import java.util.Locale;

/**
 * When IPFS is on, the panel and the app must not disagree with the process.
 *
 * <p>Observed on the box: after a cold start with the server stopped, the switch read on, no
 * {@code libipfs.so} was running, and the panel said "starting" forever because the badge only looked at
 * the preference. The preference is the intent, so the app starts it at launch; and while that happens
 * the badge only says "starting" when the supervisor is actually starting it.
 */
public final class IpfsStartPolicy {

    private IpfsStartPolicy() {
    }

    /**
     * True when the supervisor is in the middle of starting the gateway.
     *
     * <p>A stale phase from a previous process is impossible: the phase lives in a static that a cold
     * start clears, which is exactly the case that showed "starting" with nothing running.
     */
    public static boolean isStarting(boolean enabled, boolean running, String phase) {
        if (!enabled || running) return false;
        String value = phase == null ? "" : phase.trim().toLowerCase(Locale.ROOT);
        return value.equals("preparing") || value.equals("starting");
    }

    /**
     * True when the app should ask the supervisor to start IPFS right now.
     *
     * <p>Only when the preference says on, nothing is running, no start is in progress and there is no
     * error to retry: a gateway that failed is not restarted on every status refresh.
     */
    public static boolean shouldStartOnLaunch(boolean enabled, boolean running, String phase, String error) {
        if (!enabled || running) return false;
        if (error != null && !error.trim().isEmpty()) return false;
        return !isStarting(enabled, running, phase);
    }
}
