package com.jopsis.httpaceserveproxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;

/** Pins the check that turns a 31-second timeout into a sentence. */
public class EnginePortGuardTest {

    @Test
    public void aPortSomethingIsListeningOnIsReportedAsHeld() throws IOException {
        try (ServerSocket listener = new ServerSocket(0)) {
            assertTrue(EnginePortGuard.isHeld(listener.getLocalPort()));
        }
    }

    @Test
    public void aClosedPortIsNotReportedAsHeld() throws IOException {
        int freePort;
        try (ServerSocket listener = new ServerSocket(0)) {
            freePort = listener.getLocalPort();
        }
        // The socket is closed now, so nothing accepts connections on it.
        assertFalse(EnginePortGuard.isHeld(freePort, 200));
    }

    @Test
    public void theMessageIsEmptyWhenThereIsNothingToReport() {
        assertTrue(EnginePortGuard.describeOccupiedPort(false, false).isEmpty());
        assertTrue(EnginePortGuard.describeOccupiedPort(true, true).isEmpty());
    }

    @Test
    public void theMessageNamesThePortAndWhatToDo() {
        String message = EnginePortGuard.describeOccupiedPort(true, false);
        assertTrue(message.contains("6878"));
        assertTrue(message.contains("AceStream Pro"));
        assertTrue(message.contains("activa"));
    }
}
