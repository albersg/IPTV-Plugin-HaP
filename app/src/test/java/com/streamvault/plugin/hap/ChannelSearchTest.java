package com.streamvault.plugin.hap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pins the rules of the channel search. */
public class ChannelSearchTest {

    @Test
    public void emptyQueryKeepsEverything() {
        assertTrue(ChannelSearch.matches("DAZN Baloncesto 720p", ""));
        assertTrue(ChannelSearch.matches("DAZN Baloncesto 720p", null));
        assertTrue(ChannelSearch.matches("DAZN Baloncesto 720p", "   "));
    }

    @Test
    public void matchesAnywhereInTheName() {
        assertTrue(ChannelSearch.matches("DAZN Baloncesto 720p", "balon"));
        assertTrue(ChannelSearch.matches("DAZN Baloncesto 720p", "DAZN"));
        assertTrue(ChannelSearch.matches("DAZN Baloncesto 720p", "720p"));
        assertFalse(ChannelSearch.matches("DAZN Baloncesto 720p", "formula1"));
    }

    @Test
    public void ignoresCaseAndSurroundingSpaces() {
        assertTrue(ChannelSearch.matches("DAZN Baloncesto", "  dazn  "));
        assertTrue(ChannelSearch.matches("DAZN Baloncesto", "BALONCESTO"));
    }

    @Test
    public void ignoresAccents() {
        // A remote cannot type "ó", and the provider's name may carry one.
        assertTrue(ChannelSearch.matches("Móvistar Deportes", "movistar"));
        assertTrue(ChannelSearch.matches("Movistar Deportes", "móvi"));
        assertTrue(ChannelSearch.matches("Ñandú TV", "nandu"));
    }

    @Test
    public void aMissingNameNeverMatchesAQuery() {
        assertFalse(ChannelSearch.matches(null, "algo"));
        assertTrue(ChannelSearch.matches(null, ""));
    }

    @Test
    public void normalizesWhatItCompares() {
        assertTrue("baloncesto".equals(ChannelSearch.normalize("  BALONCESTO  ")));
        assertTrue("nandu tv".equals(ChannelSearch.normalize("Ñandú TV")));
        assertTrue("".equals(ChannelSearch.normalize(null)));
    }
}
