package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Pins the rules of the folder that holds lists pushed to the device. */
public class LocalListFilesTest {

    private File tempDir() throws IOException {
        File dir = File.createTempFile("hap_lists", "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdirs());
        return dir;
    }

    private void touch(File dir, String name) throws IOException {
        assertTrue(new File(dir, name).createNewFile());
    }

    @Test
    public void scansOnlyPlaylistsInOrder() throws IOException {
        File dir = tempDir();
        touch(dir, "b.m3u");
        touch(dir, "A.M3U8");
        touch(dir, "notas.txt");
        assertTrue(new File(dir, "sub").mkdirs());

        List<File> files = LocalListFiles.scan(dir);
        assertEquals(2, files.size());
        assertEquals("A.M3U8", files.get(0).getName());
        assertEquals("b.m3u", files.get(1).getName());
    }

    @Test
    public void scanningAMissingFolderIsNotAnError() {
        assertTrue(LocalListFiles.scan(null).isEmpty());
        assertTrue(LocalListFiles.scan(new File("/no/existe/esta/carpeta")).isEmpty());
    }

    @Test
    public void sanitisesNames() {
        assertEquals("lista.m3u", LocalListFiles.sanitizeName(null));
        assertEquals("lista.m3u", LocalListFiles.sanitizeName("   "));
        assertEquals("mi lista.m3u", LocalListFiles.sanitizeName("  mi   lista  "));
        assertEquals("a_b.m3u", LocalListFiles.sanitizeName("a/b.m3u"));
        // A name without the extension is not a playlist name yet.
        assertEquals("canales.m3u", LocalListFiles.sanitizeName("canales"));
        assertEquals("canales.m3u8", LocalListFiles.sanitizeName("canales.m3u8"));
    }

    @Test
    public void neverOverwritesAnExistingFile() throws IOException {
        File dir = tempDir();
        assertEquals("lista.m3u", LocalListFiles.uniqueName(dir, "lista.m3u"));

        touch(dir, "lista.m3u");
        assertEquals("lista (2).m3u", LocalListFiles.uniqueName(dir, "lista.m3u"));

        touch(dir, "lista (2).m3u");
        assertEquals("lista (3).m3u", LocalListFiles.uniqueName(dir, "lista.m3u"));
    }

    @Test
    public void describesSizes() {
        assertEquals("512 B", LocalListFiles.describeSize(512));
        assertEquals("1.0 KB", LocalListFiles.describeSize(1024));
        assertEquals("2.5 MB", LocalListFiles.describeSize((long) (2.5 * 1024 * 1024)));
    }

    @Test
    public void playlistExtensionMatchesTheSourceRules() {
        assertTrue(LocalSourceFile.hasPlaylistExtension("b.m3u"));
        assertFalse(LocalSourceFile.hasPlaylistExtension("b.txt"));
    }
}
