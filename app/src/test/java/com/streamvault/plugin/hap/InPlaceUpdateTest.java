package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Pins the two halves of the in-place update that a device would otherwise have to prove (S7).
 *
 * <p>First the value models: an update may only be skipped when the freshly built model compares
 * equal to the rendered one, so every field that a row can show must take part in equality. A
 * model that forgot a field would silently keep stale text on screen.
 *
 * <p>Second the wiring, read from the sources, because the absence of a rebuild cannot be asserted
 * on the JVM without an android view tree: the screen must contain no {@code removeAllViews()} at
 * all, the six row lists must be driven by {@link InPlaceList}, and {@link InPlaceList#apply} must
 * consult the pure decision before it touches a child.
 */
public final class InPlaceUpdateTest {

    private static final String HAP_SOURCES = "app/src/main/java/com/streamvault/plugin/hap/";

    // ------------------------------------------------------------------ the models

    @Test
    public void endpointRowsCompareByLabelAndUrl() {
        HapRows.Endpoint row = new HapRows.Endpoint("M3U", "http://127.0.0.1:8888/aio");
        assertEquals(row, new HapRows.Endpoint("M3U", "http://127.0.0.1:8888/aio"));
        assertEquals(row.hashCode(), new HapRows.Endpoint("M3U", "http://127.0.0.1:8888/aio").hashCode());
        assertNotEquals(row, new HapRows.Endpoint("M3U", "http://127.0.0.1:8888/other"));
        assertNotEquals(row, new HapRows.Endpoint("EPG", "http://127.0.0.1:8888/aio"));
    }

    @Test
    public void sourceRowsCompareByEverythingTheyShow() {
        HapRows.Source row = new HapRows.Source(
                "id", "Mi lista", "http://example/list.m3u", "http://example/list.m3u", true, false);
        assertEquals(row, new HapRows.Source(
                "id", "Mi lista", "http://example/list.m3u", "http://example/list.m3u", true, false));
        // The id is not drawn but it decides which source a click selects.
        assertNotEquals(row, new HapRows.Source(
                "other", "Mi lista", "http://example/list.m3u", "http://example/list.m3u", true, false));
        assertNotEquals(row, new HapRows.Source(
                "id", "Otra", "http://example/list.m3u", "http://example/list.m3u", true, false));
        // The stored value fills the edit form, so it is part of the row even when it is not drawn.
        assertNotEquals(row, new HapRows.Source(
                "id", "Mi lista", "http://example/other.m3u", "http://example/list.m3u", true, false));
        assertNotEquals(row, new HapRows.Source(
                "id", "Mi lista", "http://example/list.m3u", "Otra linea", true, false));
        assertNotEquals(row, new HapRows.Source(
                "id", "Mi lista", "http://example/list.m3u", "http://example/list.m3u", false, false));
        assertNotEquals(row, new HapRows.Source(
                "id", "Mi lista", "http://example/list.m3u", "http://example/list.m3u", true, true));
    }

    @Test
    public void customChannelRowsCompareByIndexNameAndContentId() {
        HapRows.CustomChannel row = new HapRows.CustomChannel(0, "Canal", "abc123");
        assertEquals(row, new HapRows.CustomChannel(0, "Canal", "abc123"));
        assertNotEquals(row, new HapRows.CustomChannel(1, "Canal", "abc123"));
        assertNotEquals(row, new HapRows.CustomChannel(0, "Otro", "abc123"));
        assertNotEquals(row, new HapRows.CustomChannel(0, "Canal", "def456"));
    }

    @Test
    public void clientRowsCompareByEveryColumn() {
        HapRows.Client row = new HapRows.Client("10.0.0.2", "TiviMate", "Canal 1", "12.5 MB");
        assertEquals(row, new HapRows.Client("10.0.0.2", "TiviMate", "Canal 1", "12.5 MB"));
        assertNotEquals(row, new HapRows.Client("10.0.0.3", "TiviMate", "Canal 1", "12.5 MB"));
        assertNotEquals(row, new HapRows.Client("10.0.0.2", "StreamVault", "Canal 1", "12.5 MB"));
        assertNotEquals(row, new HapRows.Client("10.0.0.2", "TiviMate", "Canal 2", "12.5 MB"));
        assertNotEquals(row, new HapRows.Client("10.0.0.2", "TiviMate", "Canal 1", "13.0 MB"));
    }

    @Test
    public void pluginTabsCompareByLabelCountSelectionAndFirstChannel() {
        HapRows.PluginTab tab = new HapRows.PluginTab("plugin", "Mi lista", 3, true, "abc123");
        assertEquals(tab, new HapRows.PluginTab("plugin", "Mi lista", 3, true, "abc123"));
        assertNotEquals(tab, new HapRows.PluginTab("other", "Mi lista", 3, true, "abc123"));
        assertNotEquals(tab, new HapRows.PluginTab("plugin", "Otra", 3, true, "abc123"));
        assertNotEquals(tab, new HapRows.PluginTab("plugin", "Mi lista", 4, true, "abc123"));
        assertNotEquals(tab, new HapRows.PluginTab("plugin", "Mi lista", 3, false, "abc123"));
        assertNotEquals(tab, new HapRows.PluginTab("plugin", "Mi lista", 3, true, "def456"));
        assertEquals(
                new HapRows.PluginTab("plugin", "Vacia", 0, false, null),
                new HapRows.PluginTab("plugin", "Vacia", 0, false, null)
        );
    }

    @Test
    public void channelRowsCompareBySelectionResultTextAndColours() {
        HapRows.Channel row = new HapRows.Channel("abc123", "Canal 1", true, "4 peers", 0xFF3DDC84, "OK", 0x333DDC84, 0xFF3DDC84);
        assertEquals(row, new HapRows.Channel("abc123", "Canal 1", true, "4 peers", 0xFF3DDC84, "OK", 0x333DDC84, 0xFF3DDC84));
        assertNotEquals(row, new HapRows.Channel("abc123", "Canal 1", false, "4 peers", 0xFF3DDC84, "OK", 0x333DDC84, 0xFF3DDC84));
        assertNotEquals(row, new HapRows.Channel("abc123", "Canal 2", true, "4 peers", 0xFF3DDC84, "OK", 0x333DDC84, 0xFF3DDC84));
        // A result that arrived is a change even when the strings happen to match.
        assertNotEquals(row, new HapRows.Channel("abc123", "Canal 1", true, null, 0, null, 0, 0));
        assertNotEquals(row, new HapRows.Channel("abc123", "Canal 1", true, "4 peers", 0xFFFF6B6B, "OK", 0x333DDC84, 0xFF3DDC84));
        assertNotEquals(row, new HapRows.Channel("abc123", "Canal 1", true, "4 peers", 0xFF3DDC84, "Sin seeds", 0x333DDC84, 0xFF3DDC84));
        assertNotEquals(row, new HapRows.Channel("abc123", "Canal 1", true, "4 peers", 0xFF3DDC84, "OK", 0xFF16243F, 0xFF3DDC84));
        assertNotEquals(row, new HapRows.Channel("abc123", "Canal 1", true, "4 peers", 0xFF3DDC84, "OK", 0x333DDC84, 0xFF7E90AC));
        assertEquals(row, new HapRows.Channel("abc123", "Canal 1", true, "4 peers", 0xFF3DDC84, "OK", 0x333DDC84, 0xFF3DDC84));
    }

    // ------------------------------------------------------------------ the wiring

    /** Nothing on this screen may rebuild a list any more: that is what dropped the focus. */
    @Test
    public void theScreenNeverClearsAListToRefillIt() {
        assertEquals(
                "no renderer may clear its container: update the children in place",
                0,
                count(source("HapConfigActivity.java"), "removeAllViews(")
        );
    }

    @Test
    public void everyRowListIsDrivenByTheInPlaceUpdater() {
        String activity = source("HapConfigActivity.java");
        // The channel list is the exception and left this set on purpose (D3): it is a recycler with
        // a ListAdapter, pinned by ChannelRecyclerTest. The five short lists stay in place, where
        // rebinding them costs less than a viewport would.
        String[] lists = {
                "InPlaceList<HapRows.Endpoint>",
                "InPlaceList<HapRows.Source>",
                "InPlaceList<HapRows.CustomChannel>",
                "InPlaceList<HapRows.Client>",
                "InPlaceList<HapRows.PluginTab>"
        };
        for (String list : lists) {
            assertTrue(list + " must own its container", activity.contains(list));
        }
        assertFalse(
                "the channel list is a recycler now, not an in-place list",
                activity.contains("InPlaceList<HapRows.Channel>")
        );
    }

    /** The one decision that protects every row identity is taken before any child is touched. */
    @Test
    public void theUpdaterConsultsTheModelBeforeTouchingTheContainer() {
        String updater = source("InPlaceList.java");
        String apply = methodBody(updater, "boolean apply(List<T> next)");

        assertTrue("an unchanged model must return before any view work", apply.contains("ListPatch.sameItems("));
        int decision = apply.indexOf("ListPatch.sameItems(");
        int add = apply.indexOf("container.addView(");
        int remove = apply.indexOf("container.removeView(");
        assertTrue("the decision must precede addView", decision >= 0 && add > decision);
        assertTrue("the decision must precede removeView", decision >= 0 && remove > decision);

        assertEquals("children are only ever appended", 1, count(updater, "container.addView("));
        assertEquals("children are only ever dropped from the tail", 1, count(updater, "container.removeView("));
    }

    /** A bind writes text only after asking whether the text already says that. */
    @Test
    public void theScreenWritesTextThroughAComparingHelper() {
        String activity = source("HapConfigActivity.java");
        String applyText = methodBody(activity, "private void applyText(TextView view, String text)");
        assertTrue(applyText.contains("ListPatch.needsText("));
        String applyVisibility = methodBody(activity, "private void applyVisibility(View view, boolean visible)");
        assertTrue(applyVisibility.contains("getVisibility()"));
    }

    // ------------------------------------------------------------------ source helpers

    private static String source(String fileName) {
        String relative = HAP_SOURCES + fileName;
        File base = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int depth = 0; depth < 4 && base != null; depth++) {
            File candidate = new File(base, relative);
            if (candidate.isFile()) return read(candidate);
            File moduleRelative = new File(base, "src/main/java/com/streamvault/plugin/hap/" + fileName);
            if (moduleRelative.isFile()) return read(moduleRelative);
            base = base.getParentFile();
        }
        throw new AssertionError("source not found: " + relative + " from " + System.getProperty("user.dir"));
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("cannot read " + file, error);
        }
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("method not found: " + signature, start >= 0);
        int open = source.indexOf('{', start);
        assertTrue("method has no body: " + signature, open >= 0);
        int close = source.indexOf("\n    }\n", open);
        assertTrue("method body is not closed: " + signature, close >= 0);
        return source.substring(open, close);
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return found;
    }
}
