package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pins the arithmetic behind the in-place list updates of the HaP screen (S7).
 *
 * <p>The screen used to answer every list change with {@code removeAllViews()} and a fresh set of
 * children. That costs the identity of every row, which is what the focus repair of
 * {@code FocusNavigator} existed to paper over. The replacement has one decision to make before it
 * may touch a single view — "is the new model the same as the rendered one, and if not, how much of
 * the tail actually differs" — and that decision is pure arithmetic, so it is pinned here rather
 * than on a device that is not connected.
 *
 * <p>{@link ListPatch} never imports {@code android.*}; the three questions it answers are:
 * <ul>
 *   <li>{@link ListPatch#sameItems} — may an update do nothing at all?</li>
 *   <li>{@link ListPatch#of} — how many children are reused, created and dropped?</li>
 *   <li>{@link ListPatch#needsText} — may this text be written, or is it already there?</li>
 * </ul>
 */
public final class ListPatchTest {

    @Test
    public void anIdenticallyValuedModelNeedsNoWork() {
        List<HapRows.Endpoint> rendered = rows("M3U", "http://localhost:8888/aio");
        List<HapRows.Endpoint> wanted = rows("M3U", "http://localhost:8888/aio");

        // Different instances: the decision is made on the values, not on object identity, because
        // every refresh builds a fresh model off the main thread.
        assertNotSame(rendered.get(0), wanted.get(0));
        assertTrue("equal values must be recognised as unchanged", ListPatch.sameItems(rendered, wanted));
        assertTrue(ListPatch.of(rendered.size(), wanted.size()).isNoOp());
    }

    @Test
    public void aChangedValueIsAChange() {
        assertFalse(ListPatch.sameItems(rows("M3U", "one"), rows("M3U", "two")));
        assertFalse(ListPatch.sameItems(rows("M3U", "one"), rows("EPG", "one")));
    }

    @Test
    public void aChangedOrderOrLengthIsAChange() {
        List<HapRows.Endpoint> first = rows("M3U", "one");
        List<HapRows.Endpoint> second = rows("EPG", "two");

        assertFalse("order is part of the model", ListPatch.sameItems(
                Arrays.asList(first.get(0), second.get(0)),
                Arrays.asList(second.get(0), first.get(0))
        ));
        assertFalse("a longer model is a change", ListPatch.sameItems(first, Arrays.asList(first.get(0), second.get(0))));
        assertFalse("a shorter model is a change", ListPatch.sameItems(Arrays.asList(first.get(0), second.get(0)), first));
    }

    @Test
    public void nothingAndEmptyAreTheSameModel() {
        assertTrue(ListPatch.sameItems(null, null));
        assertTrue(ListPatch.sameItems(null, Collections.emptyList()));
        assertTrue(ListPatch.sameItems(Collections.emptyList(), null));
        assertFalse(ListPatch.sameItems(null, rows("M3U", "one")));
        assertFalse(ListPatch.sameItems(rows("M3U", "one"), null));
    }

    @Test
    public void thePatchReusesTheOverlapAndOnlyTouchesTheTail() {
        ListPatch.Patch unchanged = ListPatch.of(3, 3);
        assertEquals(3, unchanged.reuse());
        assertEquals(0, unchanged.create());
        assertEquals(0, unchanged.drop());
        assertTrue(unchanged.isNoOp());

        ListPatch.Patch filled = ListPatch.of(0, 4);
        assertEquals(0, filled.reuse());
        assertEquals(4, filled.create());
        assertEquals(0, filled.drop());
        assertFalse(filled.isNoOp());

        ListPatch.Patch trimmed = ListPatch.of(4, 2);
        assertEquals(2, trimmed.reuse());
        assertEquals(0, trimmed.create());
        assertEquals(2, trimmed.drop());
        assertFalse(trimmed.isNoOp());

        ListPatch.Patch grown = ListPatch.of(2, 5);
        assertEquals(2, grown.reuse());
        assertEquals(3, grown.create());
        assertEquals(0, grown.drop());

        ListPatch.Patch empty = ListPatch.of(0, 0);
        assertTrue(empty.isNoOp());
    }

    /** A negative count is not a shape: it must never reach {@code getChildCount}. */
    @Test
    public void impossibleCountsAreClamped() {
        ListPatch.Patch patch = ListPatch.of(-3, -1);
        assertEquals(0, patch.reuse());
        assertEquals(0, patch.create());
        assertEquals(0, patch.drop());
        assertTrue(patch.isNoOp());
    }

    @Test
    public void textIsWrittenOnlyWhenItDiffers() {
        assertTrue("empty view, new text", ListPatch.needsText(null, "hola"));
        assertFalse("an empty view already says the empty label", ListPatch.needsText(null, ""));
        assertFalse("same text", ListPatch.needsText("hola", "hola"));
        assertFalse("same empty text", ListPatch.needsText("", ""));
        assertTrue("different text", ListPatch.needsText("hola", "adios"));
        assertTrue("text cleared", ListPatch.needsText("hola", ""));
        assertTrue("null source is read as empty", ListPatch.needsText("hola", null));
    }

    private static List<HapRows.Endpoint> rows(String label, String url) {
        List<HapRows.Endpoint> rows = new ArrayList<>(1);
        rows.add(new HapRows.Endpoint(label, url));
        return rows;
    }
}
