package com.jopsis.httpaceserveproxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Pins the contract between the native {@code /stat?action=get_clients} answer and the app.
 *
 * <p>The proxy adds a {@code streams} array next to {@code clients_data}. The screen paints both from
 * one request, so this test reads one literal answer that mirrors the native shape (see
 * {@code Proxy::streams_json}) and checks that every counter survives the trip. It also fixes the
 * two rules a reader would otherwise have to guess: a missing array is an empty list, and the rate is
 * converted from bits to megabits.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TransportStatusTest {

    private static final String ANSWER = "{"
            + "\"status\":\"success\","
            + "\"total_clients\":1,"
            + "\"active_broadcasts\":1,"
            + "\"clients_data\":[{"
            + "\"sessionID\":\"42\","
            + "\"channelName\":\"Canal Uno\","
            + "\"clientIP\":\"192.168.1.20\","
            + "\"userAgent\":\"TiviMate/4.7.0\","
            + "\"bytesSent\":1048576,"
            + "\"megabytesSent\":1.0"
            + "}],"
            + "\"streams\":[{"
            + "\"channel\":\"Canal Uno\","
            + "\"clients\":1,"
            + "\"bytesSent\":1048576,"
            + "\"packets\":5580,"
            + "\"payloadBytes\":1022976,"
            + "\"bitrateBps\":1504000.0,"
            + "\"continuityErrors\":2,"
            + "\"syncErrors\":0,"
            + "\"nullPackets\":12,"
            + "\"pcrDiscontinuities\":1,"
            + "\"lastPayloadAgeMs\":240,"
            + "\"stalled\":false,"
            + "\"synchronized\":true"
            + "}]"
            + "}";

    @Test
    public void readsClientsAndTransportFromOneAnswer() throws Exception {
        ConnectedClientsClient.Snapshot snapshot = ConnectedClientsClient.parse(ANSWER);

        assertEquals(1, snapshot.clients.size());
        assertEquals("192.168.1.20", snapshot.clients.get(0).ip);
        assertEquals("TiviMate", snapshot.clients.get(0).device);
        assertEquals("Canal Uno", snapshot.clients.get(0).channel);

        assertEquals(1, snapshot.transports.size());
        ConnectedClientsClient.Transport transport = snapshot.transports.get(0);
        assertEquals("Canal Uno", transport.channel);
        assertEquals(1, transport.clients);
        assertEquals(2, transport.continuityErrors);
        assertEquals(0, transport.syncErrors);
        assertEquals(240, transport.lastPayloadAgeMs);
        assertFalse(transport.stalled);
        assertTrue(transport.synced);
    }

    @Test
    public void bitrateIsConvertedFromBitsToMegabits() throws Exception {
        ConnectedClientsClient.Transport transport =
                ConnectedClientsClient.parse(ANSWER).transports.get(0);
        // 1,504,000 bit/s is 1.504 Mbit/s, not 1.504 MB/s.
        assertEquals(1.504, transport.megabitsPerSecond(), 0.0001);
    }

    @Test
    public void anAnswerWithoutStreamsIsAnEmptyList() throws Exception {
        ConnectedClientsClient.Snapshot snapshot = ConnectedClientsClient.parse(
                "{\"status\":\"success\",\"clients_data\":[]}");
        assertEquals(0, snapshot.clients.size());
        assertEquals(0, snapshot.transports.size());
    }

    @Test
    public void anUnsynchronisedStreamKeepsItsFlagsAndNegativeAge() throws Exception {
        ConnectedClientsClient.Snapshot snapshot = ConnectedClientsClient.parse(
                "{\"streams\":[{\"channel\":\"\",\"clients\":0,\"bitrateBps\":0,"
                        + "\"continuityErrors\":0,\"syncErrors\":512,\"lastPayloadAgeMs\":-1,"
                        + "\"stalled\":false,\"synchronized\":false}]}");
        ConnectedClientsClient.Transport transport = snapshot.transports.get(0);
        assertEquals("", transport.channel);
        assertEquals(-1, transport.lastPayloadAgeMs);
        assertEquals(512, transport.syncErrors);
        assertFalse(transport.stalled);
        assertFalse(transport.synced);
    }

    @Test
    public void negativeCountersAreClampedInsteadOfPainted() throws Exception {
        ConnectedClientsClient.Snapshot snapshot = ConnectedClientsClient.parse(
                "{\"streams\":[{\"channel\":\"X\",\"bitrateBps\":-5,"
                        + "\"continuityErrors\":-2,\"syncErrors\":-3}]}");
        ConnectedClientsClient.Transport transport = snapshot.transports.get(0);
        assertEquals(0.0, transport.bitrateBps, 0.0);
        assertEquals(0, transport.continuityErrors);
        assertEquals(0, transport.syncErrors);
    }
}
