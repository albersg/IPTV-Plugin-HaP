package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListUpdateCallback;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM tests for the two questions the channel list asks {@link DiffUtil} (S7, D3).
 *
 * <p>These are the tests that stand in for the device. The claim of this change is not that a
 * recycler draws, it is that <b>a refresh tick which changes nothing dispatches nothing</b>: no
 * insert, no remove, no move and no rebind, therefore no layout, no scroll and no focus change.
 * {@code DiffUtil} is plain Java, so that claim is measured here by counting the updates a diff
 * produces, instead of being asserted in a comment.
 *
 * <p>Identity and content are deliberately two different questions: the AceStream id is the row,
 * so a peer result rebinds the row the user is standing on; the value model is the content, so an
 * unchanged tick is not a change at all.
 */
public final class ChannelDiffTest {

    // ------------------------------------------------------------------ which row is which

    @Test
    public void aRowIsItsChannelEvenWhenThatTickChangedIt() {
        ChannelDiff diff = new ChannelDiff();

        assertTrue(
                "the same channel is the same row, whatever the result says today",
                diff.areItemsTheSame(channel("abc", true, null, null), channel("abc", false, "4 peers", "OK"))
        );
        assertFalse(
                "another channel is another row",
                diff.areItemsTheSame(channel("abc", true, null, null), channel("def", true, null, null))
        );
        assertFalse(
                "a missing id cannot match anything",
                diff.areItemsTheSame(channel(null, true, null, null), channel(null, true, null, null))
        );
    }

    @Test
    public void theWholeValueModelIsTheContent() {
        ChannelDiff diff = new ChannelDiff();
        HapRows.Channel row = channel("abc", true, "4 peers", "OK");

        assertTrue("an equal model is not a change", diff.areContentsTheSame(row, channel("abc", true, "4 peers", "OK")));
        assertFalse("the label", diff.areContentsTheSame(row, new HapRows.Channel("abc", "Otro", true, "4 peers", COLOR_OK, "OK", COLOR_OK_MUTED, COLOR_OK)));
        assertFalse("the selection", diff.areContentsTheSame(row, new HapRows.Channel("abc", "Canal 1", false, "4 peers", COLOR_OK, "OK", COLOR_OK_MUTED, COLOR_OK)));
        assertFalse("the detail line", diff.areContentsTheSame(row, new HapRows.Channel("abc", "Canal 1", true, "2 peers", COLOR_OK, "OK", COLOR_OK_MUTED, COLOR_OK)));
        assertFalse("the detail colour", diff.areContentsTheSame(row, new HapRows.Channel("abc", "Canal 1", true, "4 peers", COLOR_BAD, "OK", COLOR_OK_MUTED, COLOR_OK)));
        assertFalse("the pill", diff.areContentsTheSame(row, new HapRows.Channel("abc", "Canal 1", true, "4 peers", COLOR_OK, "Sin seeds", COLOR_OK_MUTED, COLOR_OK)));
        assertFalse("the pill background", diff.areContentsTheSame(row, new HapRows.Channel("abc", "Canal 1", true, "4 peers", COLOR_OK, "OK", COLOR_BAD_MUTED, COLOR_OK)));
        assertFalse("the pill text colour", diff.areContentsTheSame(row, new HapRows.Channel("abc", "Canal 1", true, "4 peers", COLOR_OK, "OK", COLOR_OK_MUTED, COLOR_BAD)));
        assertFalse("a row that gained its first result", diff.areContentsTheSame(channel("abc", true, null, null), row));
    }

    // ------------------------------------------------------------------ what a diff dispatches

    /** The claim: an unchanged model reaches the list as no update at all. */
    @Test
    public void anUnchangedModelDispatchesNothing() {
        List<HapRows.Channel> shown = channels(20, "abc");

        Recorder recorder = dispatch(shown, copy(shown));

        assertEquals("an unchanged tick must not insert", 0, recorder.inserted);
        assertEquals("an unchanged tick must not remove", 0, recorder.removed);
        assertEquals("an unchanged tick must not move", 0, recorder.moved);
        assertEquals("an unchanged tick must not rebind a row", 0, recorder.changed);
        assertEquals(0, recorder.total());
    }

    /** One result that arrived is exactly one row rebound, and the rows around it are untouched. */
    @Test
    public void oneArrivedResultRebindsOneRow() {
        List<HapRows.Channel> shown = channels(20, "abc");
        List<HapRows.Channel> next = copy(shown);
        next.set(7, new HapRows.Channel("abc7", "Canal 7", false, "4 peers", COLOR_OK, "OK", COLOR_OK_MUTED, COLOR_OK));

        Recorder recorder = dispatch(shown, next);

        assertEquals("nothing is inserted", 0, recorder.inserted);
        assertEquals("nothing is removed", 0, recorder.removed);
        assertEquals("the row keeps its place", 0, recorder.moved);
        assertEquals("one row is rebound", 1, recorder.changed);
        assertEquals("and it is the row whose result arrived", 7, recorder.changedPositions.get(0).intValue());
    }

    /** A list that loads more channels inserts the tail, and the rows on screen keep their place. */
    @Test
    public void aLongerListInsertsOnlyTheTail() {
        List<HapRows.Channel> shown = channels(4, "abc");
        List<HapRows.Channel> next = channels(7, "abc");

        Recorder recorder = dispatch(shown, next);

        assertEquals("three rows arrive", 3, recorder.inserted);
        assertEquals("nothing on screen is removed", 0, recorder.removed);
        assertEquals("nothing on screen is rebound", 0, recorder.changed);
        assertEquals("nothing on screen moves", 0, recorder.moved);
    }

    // ------------------------------------------------------------------ fixtures

    private static final int COLOR_OK = 0xFF3DDC84;
    private static final int COLOR_OK_MUTED = 0x333DDC84;
    private static final int COLOR_BAD = 0xFFFF6B6B;
    private static final int COLOR_BAD_MUTED = 0x33FF6B6B;

    private static HapRows.Channel channel(String id, boolean selected, String detail, String badge) {
        boolean hasResult = badge != null;
        return new HapRows.Channel(
                id,
                "Canal 1",
                selected,
                detail,
                hasResult ? COLOR_OK : 0,
                badge,
                hasResult ? COLOR_OK_MUTED : 0,
                hasResult ? COLOR_OK : 0
        );
    }

    /** A list of {@code count} channels whose ids start with {@code prefix}. */
    private static List<HapRows.Channel> channels(int count, String prefix) {
        List<HapRows.Channel> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new HapRows.Channel(
                    prefix + i,
                    "Canal " + i,
                    false,
                    null,
                    0,
                    null,
                    0,
                    0
            ));
        }
        return rows;
    }

    /** Every row rebuilt from its own values: the shape a refresh tick produces. */
    private static List<HapRows.Channel> copy(List<HapRows.Channel> rows) {
        List<HapRows.Channel> next = new ArrayList<>(rows.size());
        for (HapRows.Channel row : rows) {
            next.add(new HapRows.Channel(
                    row.contentId,
                    row.name,
                    row.selected,
                    row.detail,
                    row.detailColor,
                    row.badge,
                    row.badgeBackground,
                    row.badgeForeground
            ));
        }
        return next;
    }

    private static Recorder dispatch(List<HapRows.Channel> shown, List<HapRows.Channel> next) {
        final ChannelDiff callback = new ChannelDiff();
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return shown.size();
            }

            @Override
            public int getNewListSize() {
                return next.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return callback.areItemsTheSame(shown.get(oldItemPosition), next.get(newItemPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return callback.areContentsTheSame(shown.get(oldItemPosition), next.get(newItemPosition));
            }
        });
        Recorder recorder = new Recorder();
        result.dispatchUpdatesTo(recorder);
        return recorder;
    }

    /** Counts what the differ would tell a list to do. */
    private static final class Recorder implements ListUpdateCallback {

        int inserted;
        int removed;
        int moved;
        int changed;
        final List<Integer> changedPositions = new ArrayList<>();

        @Override
        public void onInserted(int position, int count) {
            inserted += count;
        }

        @Override
        public void onRemoved(int position, int count) {
            removed += count;
        }

        @Override
        public void onMoved(int fromPosition, int toPosition) {
            moved++;
        }

        @Override
        public void onChanged(int position, int count, Object payload) {
            changed += count;
            for (int i = 0; i < count; i++) changedPositions.add(position + i);
        }

        int total() {
            return inserted + removed + moved + changed;
        }
    }
}
