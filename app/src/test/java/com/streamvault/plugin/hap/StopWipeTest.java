package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jopsis.httpaceserveproxy.StopWipe;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Pins P3: an explicit stop may wipe the streaming cache and the engine log, and nothing else.
 *
 * <p>The engine keeps its live cache, its log and its configuration in one directory — the
 * directory {@code AceServeRuntime} passes to the engine as its cache dir. The wipe therefore has
 * exactly one rule to get right: everything in there goes except {@code acestream.conf}, which the
 * engine reads at startup, so the next start is configured as before. M3U sources, custom channels,
 * preferences and downloaded update files live outside that directory, which is why the wipe is
 * scoped to it instead of to the app's whole cache.
 *
 * <p>The directory is a real one on a temporary filesystem, so the test does not need a Context and
 * does not run against a device.
 */
public final class StopWipeTest {

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    /** The engine's cache dir, created empty. */
    private File engineCacheDir() throws Exception {
        return scratch.newFolder("aceserve");
    }

    private File write(File parent, String name, String content) throws Exception {
        File file = new File(parent, name);
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** The streaming cache is what the user asked to delete, so all of it goes. */
    @Test
    public void deletesTheStreamingCacheContents() throws Exception {
        File cacheDir = engineCacheDir();
        File chunk = write(cacheDir, "chunk-0001.cache", "cache");
        File nested = write(new File(cacheDir, ".ACEStream/torrents"), "piece-0001", "piece");
        File log = write(cacheDir, "acestream.log", "engine log");

        int removed = StopWipe.wipeCacheDirectory(cacheDir);

        assertFalse(chunk.exists());
        assertFalse(nested.exists());
        assertFalse(".ACEStream must not survive the wipe", new File(cacheDir, ".ACEStream").exists());
        assertTrue(removed >= 3);
        assertTrue("the engine log is deleted too", !log.exists());
    }

    /** The engine reads its conf file at startup: deleting it would change the next start. */
    @Test
    public void keepsAcestreamConfSoTheNextStartIsConfigured() throws Exception {
        File cacheDir = engineCacheDir();
        File conf = write(cacheDir, "acestream.conf", "--live-cache-size=104857600\n");
        write(cacheDir, "chunk-0001.cache", "cache");

        StopWipe.wipeCacheDirectory(cacheDir);

        assertTrue(conf.isFile());
        assertEquals("--live-cache-size=104857600\n",
                new String(Files.readAllBytes(conf.toPath()), StandardCharsets.UTF_8));
    }

    /** Downloaded update files and user data live elsewhere and must never be touched. */
    @Test
    public void neverTouchesAnythingOutsideTheEngineCacheDirectory() throws Exception {
        File cacheRoot = scratch.getRoot();
        File engineDir = new File(cacheRoot, "aceserve");
        assertTrue(engineDir.mkdirs());
        File updateApk = write(new File(cacheRoot, "update"), "update.apk", "apk");
        File sibling = write(cacheRoot, "keep.txt", "keep");

        StopWipe.wipeCacheDirectory(engineDir);

        assertTrue(updateApk.isFile());
        assertTrue(sibling.isFile());
    }

    /** A missing or empty directory is not a failure: the stop path must never see an error. */
    @Test
    public void toleratesAMissingOrEmptyDirectory() throws Exception {
        File missing = new File(scratch.getRoot(), "aceserve");
        assertEquals(0, StopWipe.wipeCacheDirectory(missing));
        assertEquals(0, StopWipe.wipeCacheDirectory(engineCacheDir()));
        assertEquals(0, StopWipe.wipeCacheDirectory(null));
    }

    /** Only the conf file in an otherwise empty directory means there is nothing to delete. */
    @Test
    public void reportsNothingRemovedWhenOnlyTheConfIsLeft() throws Exception {
        File cacheDir = engineCacheDir();
        write(cacheDir, "acestream.conf", "conf");

        assertEquals(0, StopWipe.wipeCacheDirectory(cacheDir));
        assertTrue(new File(cacheDir, "acestream.conf").isFile());
    }
}
