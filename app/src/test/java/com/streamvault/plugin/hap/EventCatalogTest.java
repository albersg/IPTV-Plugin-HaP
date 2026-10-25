package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pins how the guide becomes a list of events with playable channels. */
public class EventCatalogTest {

    private static final long NOW = 1792968000000L; // 2026-10-25 22:40 UTC

    private static final String GUIDE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>"
            + "<channel id=\"DAZN LaLiga HD\"><display-name>DAZN LaLiga</display-name></channel>"
            + "<channel id=\"DAZN 1 HD\"><display-name>DAZN 1</display-name></channel>"
            + "<channel id=\"La 1 HD\"><display-name>La 1</display-name></channel>"
            + "<channel id=\"Sin Uso\"><display-name>Sin Uso</display-name></channel>"
            + "<programme start=\"20261025210000 +0000\" stop=\"20261025230000 +0000\""
            + "    channel=\"DAZN LaLiga HD\"><title>Jornada 10: Barcelona - Real Madrid</title></programme>"
            + "<programme start=\"20261025210000 +0000\" stop=\"20261025230000 +0000\""
            + "    channel=\"DAZN 1 HD\"><title>Jornada 10: Barcelona - Real Madrid</title></programme>"
            + "<programme start=\"20261025200000 +0000\" stop=\"20261025230000 +0000\""
            + "    channel=\"La 1 HD\"><title>Noticias 24H</title></programme>"
            + "<programme start=\"20261025200000 +0000\" stop=\"20261025230000 +0000\""
            + "    channel=\"Sin Uso\"><title>Programa sin canal nuestro</title></programme>"
            + "</tv>";

    private static EpgWindow guide() throws IOException {
        return EpgWindow.parse(new ByteArrayInputStream(GUIDE.getBytes(StandardCharsets.UTF_8)),
                NOW, 3_600_000L, 3_600_000L);
    }

    private static Map<String, String> ourChannels() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("ch_laliga_1080", "DAZN LaLiga HD");
        map.put("ch_dazn1_1080", "DAZN 1 HD");
        map.put("ch_la1", "La 1 HD");
        return map;
    }

    @Test
    public void oneEventPerProgrammeWithTheChannelsThatCarryIt() throws IOException {
        List<EventCatalog.Event> events = EventCatalog.onNow(guide(), ourChannels(), NOW);
        assertEquals(2, events.size());

        EventCatalog.Event clasic = events.get(0);
        assertEquals("Jornada 10: Barcelona - Real Madrid", clasic.title);
        assertEquals(2, clasic.channelCount());
        assertEquals(Arrays.asList("ch_laliga_1080", "ch_dazn1_1080"), clasic.channelIds);

        assertEquals("Noticias 24H", events.get(1).title);
        assertEquals(1, events.get(1).channelCount());
    }

    @Test
    public void eventsWithMoreChannelsComeFirst() throws IOException {
        // Swap the fixture's channel order: the two-channel event must still lead.
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("ch_la1", "La 1 HD");
        reversed.put("ch_dazn1_1080", "DAZN 1 HD");
        reversed.put("ch_laliga_1080", "DAZN LaLiga HD");
        List<EventCatalog.Event> events = EventCatalog.onNow(guide(), reversed, NOW);
        assertEquals("Jornada 10: Barcelona - Real Madrid", events.get(0).title);
    }

    @Test
    public void aGuideChannelWeCannotPlayNeverBecomesAnEvent() throws IOException {
        List<EventCatalog.Event> events = EventCatalog.onNow(guide(), ourChannels(), NOW);
        for (EventCatalog.Event event : events) {
            assertTrue(!event.title.contains("sin canal nuestro"));
        }
    }

    @Test
    public void aChannelWithoutGuideContributesNothingButIsNotAnError() throws IOException {
        Map<String, String> withUnmatched = new LinkedHashMap<>(ourChannels());
        withUnmatched.put("ch_raro", "Canal Que No Esta En La Guia");
        List<EventCatalog.Event> events = EventCatalog.onNow(guide(), withUnmatched, NOW);
        assertEquals(2, events.size());
        assertEquals(2, events.get(0).channelCount());
    }

    @Test
    public void theSameTitleSpelledDifferentlyStaysOneEvent() {
        // Two guide channels whose titles differ only in accents and spacing.
        assertEquals(ChannelGuideMatcher.normalize("Jornada 10: Barcelona - Real Madrid"),
                ChannelGuideMatcher.normalize("Jornada 10  Barcelona  Real Madrid"));
    }

    @Test
    public void nothingOnMeansNoEvents() throws IOException {
        // 14:00 UTC, before anything in the fixture starts.
        assertTrue(EventCatalog.onNow(guide(), ourChannels(), 1792936800000L).isEmpty());
    }

    @Test
    public void noGuideOrNoMatchesIsSafe() throws IOException {
        assertTrue(EventCatalog.onNow(null, ourChannels(), NOW).isEmpty());
        assertTrue(EventCatalog.onNow(guide(), new LinkedHashMap<>(), NOW).isEmpty());
    }

    @Test
    public void theEventSearchUsesTheSameRulesAsTheChannelSearch() throws IOException {
        List<EventCatalog.Event> events = EventCatalog.onNow(guide(), ourChannels(), NOW);
        assertEquals(1, EventCatalog.matching(events, "barcelona").size());
        assertEquals(1, EventCatalog.matching(events, "BARCELONA").size());
        assertEquals(1, EventCatalog.matching(events, "noticias").size());
        assertTrue(EventCatalog.matching(events, "formula 1").isEmpty());
        assertEquals(2, EventCatalog.matching(events, "").size());
    }
}
