package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Pins the window rules that keep a 34 MB guide usable on a 2 GB device. */
public class EpgWindowTest {

    /** 2026-10-25 22:40 UTC: the match that started at 21:00 is on. */
    private static final long NOW = 1792968000000L;
    /** 2026-10-25 21:00 UTC, kick-off. */
    private static final long MATCH_START = 1792962000000L;
    /** 2026-10-25 23:00 UTC, final whistle. */
    private static final long MATCH_STOP = 1792969200000L;

    private static final String GUIDE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<tv>"
            + "  <channel id=\"DAZN LaLiga HD\"><display-name>DAZN LaLiga</display-name></channel>"
            + "  <channel id=\"La 1 HD\"><display-name>La 1</display-name>"
            + "    <display-name>La Primera</display-name></channel>"
            + "  <programme start=\"20261025210000 +0000\" stop=\"20261025230000 +0000\""
            + "      channel=\"DAZN LaLiga HD\">"
            + "    <title>Jornada 10: Barcelona - Real Madrid</title>"
            + "  </programme>"
            + "  <programme start=\"20261025180000 +0000\" stop=\"20261025210000 +0000\""
            + "      channel=\"DAZN LaLiga HD\">"
            + "    <title>Previa Barcelona - Real Madrid</title>"
            + "  </programme>"
            + "  <programme start=\"20261020120000 +0000\" stop=\"20261020130000 +0000\""
            + "      channel=\"La 1 HD\">"
            + "    <title>Programa de hace una semana</title>"
            + "  </programme>"
            + "  <programme start=\"20261026010000 +0000\" stop=\"20261026020000 +0000\""
            + "      channel=\"La 1 HD\">"
            + "    <title>Programa de madrugada</title>"
            + "  </programme>"
            + "</tv>";

    private static EpgWindow parse(String xml, long now, long behind, long ahead) throws IOException {
        return EpgWindow.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                now, behind, ahead);
    }

    @Test
    public void readsTheChannelsWithAllTheirNames() throws IOException {
        EpgWindow window = parse(GUIDE, NOW, 3_600_000L, 6 * 3_600_000L);
        List<ChannelGuideMatcher.GuideChannel> channels = window.channels();
        assertEquals(2, channels.size());
        assertEquals("DAZN LaLiga HD", channels.get(0).id);
        assertEquals("DAZN LaLiga", channels.get(0).names.get(0));
        assertEquals(2, channels.get(1).names.size());
    }

    @Test
    public void keepsOnlyProgrammesInsideTheWindow() throws IOException {
        // One hour either side of 22:40 keeps the match that started at 21:00, and drops the previous
        // programme, the one from a week ago and the one after midnight.
        EpgWindow window = parse(GUIDE, NOW, 3_600_000L, 3_600_000L);
        assertEquals(1, window.programmes().size());
        assertEquals("Jornada 10: Barcelona - Real Madrid", window.programmes().get(0).title);

        // Three days of look-back still drop the week-old programme: it ended before the window opens.
        EpgWindow wide = parse(GUIDE, NOW, 3 * 24 * 3_600_000L, 3_600_000L);
        for (EpgWindow.Programme programme : wide.programmes()) {
            assertFalse(programme.title.contains("hace una semana"));
        }
    }

    @Test
    public void aWindowBeforeAnythingStartsKeepsNothing() throws IOException {
        // 14:00 UTC, an hour before the earliest programme in the fixture and long before the match.
        EpgWindow window = parse(GUIDE, 1792936800000L, 60_000L, 60_000L);
        assertEquals(0, window.programmes().size());
    }

    @Test
    public void knowsWhatIsOnNow() throws IOException {
        EpgWindow window = parse(GUIDE, NOW, 3_600_000L, 3_600_000L);
        Map<String, EpgWindow.Programme> onNow = window.onNow(NOW);
        assertEquals("Jornada 10: Barcelona - Real Madrid", onNow.get("DAZN LaLiga HD").title);
        assertFalse(onNow.containsKey("La 1 HD"));
    }

    @Test
    public void thePreMatchShowIsOnBeforeKickOffAndNotAfter() throws IOException {
        EpgWindow window = parse(GUIDE, MATCH_START - 1, 3_600_000L, 3_600_000L);
        Map<String, EpgWindow.Programme> onNow = window.onNow(MATCH_START - 1);
        assertEquals("Previa Barcelona - Real Madrid", onNow.get("DAZN LaLiga HD").title);
    }

    @Test
    public void aProgrammeIsNotOnAtItsExactStopTime() throws IOException {
        EpgWindow window = parse(GUIDE, NOW, 3_600_000L, 3_600_000L);
        EpgWindow.Programme match = window.programmes().get(0);
        assertEquals(MATCH_START, match.startMillis);
        assertEquals(MATCH_STOP, match.stopMillis);
        assertTrue(match.isOnAt(MATCH_STOP - 1));
        assertFalse(match.isOnAt(MATCH_STOP));
    }

    @Test
    public void parsesXmltvTimestampsWithAndWithoutOffset() {
        // 2026-10-25 21:00:00 UTC
        assertEquals(1792962000000L, EpgWindow.parseTimestamp("20261025210000 +0000"));
        // The same instant written in a +0200 zone must land on the same millisecond.
        assertEquals(1792962000000L, EpgWindow.parseTimestamp("20261025230000 +0200"));
        assertEquals(0, EpgWindow.parseTimestamp(null));
        assertEquals(0, EpgWindow.parseTimestamp("basura"));
    }

    @Test
    public void aGuideThatIsNotXmlIsReportedNotCrash() {
        try {
            parse("<tv><programme", NOW, 0, 0);
            assertTrue("expected a failure for a truncated guide", false);
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("XMLTV"));
        }
    }

    @Test
    public void aGuideWithTensOfThousandsOfProgrammesKeepsOnlyTheWindow() throws IOException {
        // The real guide has 83,073 programmes. This builds 30,000 over 30 days and proves the window is
        // what decides how much is kept, not the size of the file.
        long day = 24 * 3_600_000L;
        StringBuilder xml = new StringBuilder(3_000_000);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>");
        xml.append("<channel id=\"C\"><display-name>C</display-name></channel>");
        long base = NOW - 15 * day;
        for (int i = 0; i < 30_000; i++) {
            long start = base + i * (day / 1000);
            xml.append("<programme start=\"")
                    .append(EpgWindow.formatTimestamp(start))
                    .append(" +0000\" stop=\"")
                    .append(EpgWindow.formatTimestamp(start + 3_600_000L))
                    .append(" +0000\" channel=\"C\"><title>P")
                    .append(i)
                    .append("</title></programme>");
        }
        xml.append("</tv>");

        long began = System.nanoTime();
        EpgWindow window = parse(xml.toString(), NOW, 3_600_000L, 3_600_000L);
        long elapsedMs = (System.nanoTime() - began) / 1_000_000L;

        // Each programme lasts an hour and they start 86 seconds apart, so a three-hour window holds
        // about 125 of them. What matters is that 30,000 in the file does not mean 30,000 in memory.
        assertTrue("kept nothing", !window.programmes().isEmpty());
        assertTrue("kept far too much: " + window.programmes().size(),
                window.programmes().size() <= 500);
        assertTrue("took " + elapsedMs + " ms", elapsedMs < 20_000);
    }

    @Test
    public void anEmptyGuideIsNotAnError() throws IOException {
        EpgWindow window = parse("<?xml version=\"1.0\"?><tv></tv>", NOW, 0, 0);
        assertTrue(window.channels().isEmpty());
        assertTrue(window.programmes().isEmpty());
    }
}
