package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pins the rules of the localfile: source kind. */
public class LocalSourceFileTest {

    @Test
    public void recognisesTheScheme() {
        assertTrue(LocalSourceFile.isLocalValue("localfile:/data/files/lists/a.m3u"));
        assertTrue(LocalSourceFile.isLocalValue("LOCALFILE:/data/a.m3u"));
        assertTrue(LocalSourceFile.isLocalValue("  localfile:/data/a.m3u  "));
    }

    @Test
    public void rejectsEverythingElse() {
        assertFalse(LocalSourceFile.isLocalValue(null));
        assertFalse(LocalSourceFile.isLocalValue(""));
        assertFalse(LocalSourceFile.isLocalValue("localfile:"));
        assertFalse(LocalSourceFile.isLocalValue("http://example.com/a.m3u"));
        // A remote URL that merely starts with the same letters must never be read from disk.
        assertFalse(LocalSourceFile.isLocalValue("localfileserver.example.com/a.m3u"));
        assertFalse(LocalSourceFile.isLocalValue("local:/data/a.m3u"));
    }

    @Test
    public void extractsThePath() {
        assertEquals("/data/files/lists/a.m3u", LocalSourceFile.pathOf("localfile:/data/files/lists/a.m3u"));
        assertEquals("/x.m3u", LocalSourceFile.pathOf("LOCALFILE:/x.m3u"));
        assertEquals("", LocalSourceFile.pathOf("http://example.com/a.m3u"));
    }

    @Test
    public void roundTripsAFile() {
        java.io.File file = new java.io.File("/data/files/lists/mi lista.m3u");
        String value = LocalSourceFile.valueFor(file);
        assertEquals("/data/files/lists/mi lista.m3u", LocalSourceFile.pathOf(value));
        assertEquals(value, LocalSourceFile.valueFor(new java.io.File(LocalSourceFile.pathOf(value))));
    }

    @Test
    public void namesASourceAfterItsFile() {
        assertEquals("mi lista", LocalSourceFile.displayName("localfile:/data/lists/mi lista.m3u"));
        assertEquals("a", LocalSourceFile.displayName("localfile:/data/lists/a.m3u8"));
        // No extension, and no name at all, still produce something to show.
        assertEquals("lista", LocalSourceFile.displayName("localfile:/data/lists/lista"));
        assertEquals("/", LocalSourceFile.displayName("localfile:/"));
    }

    @Test
    public void recognisesPlaylistExtensions() {
        assertTrue(LocalSourceFile.hasPlaylistExtension("a.m3u"));
        assertTrue(LocalSourceFile.hasPlaylistExtension("A.M3U8"));
        assertFalse(LocalSourceFile.hasPlaylistExtension("a.txt"));
        assertFalse(LocalSourceFile.hasPlaylistExtension("m3u"));
        assertFalse(LocalSourceFile.hasPlaylistExtension(null));
        assertEquals("m3u8", LocalSourceFile.extensionOf("localfile:/a/b.M3U8"));
    }
}
