package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Pins the reader that makes the channel list work before the server does. */
public class M3uParserTest {

    private static List<M3uParser.Entry> parse(String text) throws IOException {
        return M3uParser.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String PLAYLIST = "#EXTM3U\n"
            + "#EXTINF:-1 tvg-id=\"DAZN_LaLiga_720p_\" tvg-logo=\"x.png\",DAZN LaLiga 720p *\n"
            + "acestream://9dd9cd78f0b08097a0ad290606dbbee999709fa5\n"
            + "#EXTINF:-1,DAZN 1 1080p **\n"
            + "acestream://7b0193004089d4d29821a5cdf17f407d0d192314\n"
            + "#EXTINF:-1,Canal HTTP\n"
            + "http://example.com/stream.m3u8\n";

    @Test
    public void readsNameAndHashForEveryAceStreamEntry() throws IOException {
        List<M3uParser.Entry> entries = parse(PLAYLIST);
        assertEquals(2, entries.size());
        assertEquals("DAZN LaLiga 720p *", entries.get(0).name);
        assertEquals("9dd9cd78f0b08097a0ad290606dbbee999709fa5", entries.get(0).contentId);
        assertEquals("DAZN 1 1080p **", entries.get(1).name);
    }

    @Test
    public void anHttpEntryIsSkippedBecauseItHasNoHashToOpen() throws IOException {
        for (M3uParser.Entry entry : parse(PLAYLIST)) {
            assertTrue(!entry.name.contains("HTTP"));
        }
    }

    @Test
    public void theNameIsWhatFollowsTheLastComma() {
        assertEquals("Canal, con coma", M3uParser.nameOf("#EXTINF:-1 tvg-name=\"x\",Canal, con coma"));
        assertEquals("", M3uParser.nameOf("#EXTINF:-1 sin coma"));
    }

    @Test
    public void theHashSurvivesQueryStringsAndTrailingSlashes() {
        assertEquals("abc123", M3uParser.contentIdOf("acestream://abc123"));
        assertEquals("abc123", M3uParser.contentIdOf("acestream://abc123?x=1"));
        assertEquals("abc123", M3uParser.contentIdOf("acestream://abc123/"));
        assertNull(M3uParser.contentIdOf("http://example.com/a.ts"));
        assertNull(M3uParser.contentIdOf("acestream://"));
    }

    @Test
    public void junkBetweenEntriesDoesNotThrow() throws IOException {
        List<M3uParser.Entry> entries = parse("#EXTM3U\n\n#EXTINF:-1,A\n\n#comentario\nacestream://x\n");
        assertEquals(1, entries.size());
    }

    @Test
    public void anEntryWithoutAUrlIsIgnored() throws IOException {
        assertTrue(parse("#EXTM3U\n#EXTINF:-1,Sin url\n#EXTINF:-1,B\nacestream://y\n").size() == 1);
    }
}
