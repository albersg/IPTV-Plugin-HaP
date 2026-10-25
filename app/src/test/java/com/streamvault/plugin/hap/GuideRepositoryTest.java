package com.streamvault.plugin.hap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/** Pins the decisions that keep the events screen from waiting or going empty. */
public class GuideRepositoryTest {

    private static final byte[] XML = "<tv><channel id=\"a\"/></tv>".getBytes(StandardCharsets.UTF_8);

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    @Test
    public void aFreshCacheIsUsedWithoutDownloading() {
        assertEquals(GuideRepository.Plan.USE_CACHE, GuideRepository.plan(true, false));
    }

    @Test
    public void noCacheMeansDownload() {
        assertEquals(GuideRepository.Plan.DOWNLOAD, GuideRepository.plan(false, false));
    }

    @Test
    public void aForcedRefreshDownloadsEvenWithAFreshCache() {
        assertEquals(GuideRepository.Plan.DOWNLOAD, GuideRepository.plan(true, true));
    }

    @Test
    public void gzipIsRecognisedByItsMagicNumberNotByAHeader() throws IOException {
        assertTrue(GuideRepository.isGzip(gzip(XML)));
        assertFalse(GuideRepository.isGzip(XML));
        assertFalse(GuideRepository.isGzip(null));
        assertFalse(GuideRepository.isGzip(new byte[]{0x1f}));
    }

    @Test
    public void compressedAndPlainGuidesDecodeToTheSameBytes() throws IOException {
        assertArrayEquals(XML, GuideRepository.decode(gzip(XML)));
        assertArrayEquals(XML, GuideRepository.decode(XML));
    }

    @Test
    public void anEmptyAnswerIsAnErrorNotAnEmptyGuide() {
        try {
            GuideRepository.decode(new byte[0]);
            assertTrue("expected a failure", false);
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("empty"));
        }
    }
}
