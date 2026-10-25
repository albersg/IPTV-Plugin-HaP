package com.streamvault.plugin.hap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Pins when the cached guide may be shown, which decides whether the screen waits or opens. */
public class GuideCacheTest {

    private static final long NOW = 1792968000000L;
    private static final long HOUR = 3_600_000L;
    private static final byte[] GUIDE = "<tv><channel id=\"a\"/></tv>".getBytes(StandardCharsets.UTF_8);

    private static File newDirectory() throws IOException {
        return Files.createTempDirectory("guide-cache").toFile();
    }

    @Test
    public void aGuideYoungerThanSixHoursIsUsable() throws IOException {
        File dir = newDirectory();
        GuideCache.write(dir, GUIDE);
        assertTrue(GuideCache.isUsable(dir, System.currentTimeMillis()));
        assertArrayEquals(GUIDE, GuideCache.read(dir, System.currentTimeMillis()));
    }

    @Test
    public void freshnessIsDecidedByAgeNotByHope() {
        assertTrue(GuideCache.isFresh(NOW - HOUR, NOW));
        assertTrue(GuideCache.isFresh(NOW, NOW));
        assertFalse(GuideCache.isFresh(NOW - 7 * HOUR, NOW));
        assertFalse(GuideCache.isFresh(0, NOW));
    }

    @Test
    public void aClockThatWentBackwardsIsNotFreshness() {
        assertFalse(GuideCache.isFresh(NOW + HOUR, NOW));
    }

    @Test
    public void anEmptyDirectoryHasNoGuide() throws IOException {
        File dir = newDirectory();
        assertFalse(GuideCache.isUsable(dir, NOW));
        assertNull(GuideCache.read(dir, NOW));
        assertEquals(-1, GuideCache.ageMillis(dir, NOW));
    }

    @Test
    public void aStaleCopyIsNotShownButIsStillOnDisk() throws IOException {
        File dir = newDirectory();
        GuideCache.write(dir, GUIDE);
        File file = GuideCache.file(dir);
        assertTrue(file.setLastModified(NOW - 8 * HOUR));
        assertFalse(GuideCache.isUsable(dir, NOW));
        assertNull(GuideCache.read(dir, NOW));
        assertTrue("the old copy stays for a background refresh to replace", file.isFile());
    }

    @Test
    public void anEmptyGuideIsRefusedInsteadOfCached() throws IOException {
        File dir = newDirectory();
        try {
            GuideCache.write(dir, new byte[0]);
            assertTrue("expected a refusal", false);
        } catch (IOException expected) {
            assertFalse(GuideCache.file(dir).exists());
        }
    }

    @Test
    public void writingReplacesThePreviousCopyWithoutLeavingDebris() throws IOException {
        File dir = newDirectory();
        GuideCache.write(dir, GUIDE);
        byte[] second = "<tv/>".getBytes(StandardCharsets.UTF_8);
        GuideCache.write(dir, second);
        assertArrayEquals(second, GuideCache.read(dir, System.currentTimeMillis()));
        assertEquals(1, dir.listFiles((d, name) -> name.endsWith(".part") || name.endsWith(".xml")).length);
    }
}
