package com.jopsis.httpaceserveproxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Tells whether the AceStream engine port is already taken before we try to start our own.
 *
 * <p>Measured on the device: with AceStream Pro running, HaP's own engine never gets port 6878, every
 * START waits 31 seconds and then times out, and the app looks broken without saying why. The port is
 * the whole story, and asking about it before starting is a three-line question that saves the wait.
 */
public final class EnginePortGuard {

    /** The port the engine listens on; the same one AceStream Pro takes. */
    public static final int ENGINE_PORT = 6878;

    private EnginePortGuard() {
    }

    /** True when something already accepts connections on {@code port} of this device. */
    public static boolean isHeld(int port) {
        return isHeld(port, 400);
    }

    static boolean isHeld(int port, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), timeoutMillis);
            return true;
        } catch (IOException | SecurityException notListening) {
            return false;
        }
    }

    /**
     * The message to show when the port is held by something we did not start, or an empty string when
     * there is nothing to report.
     *
     * <p>Pure, so the wording decision is pinned by a test rather than trusted.
     */
    public static String describeOccupiedPort(boolean held, boolean ourEngineRunning) {
        if (!held || ourEngineRunning) return "";
        // Deliberately does not accuse AceStream only: the port can also still be held by a previous
        // engine that is closing, and sending the user to close an app that is not running is worse than
        // naming both possibilities.
        return "El puerto " + ENGINE_PORT + " ya está en uso: otra aplicación (AceStream Pro) o el motor "
                + "anterior todavía cerrándose. Ciérrala, espera unos segundos y vuelve a activar HaP.";
    }
}
