package com.streamvault.plugin.hap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Pins the visual row order of the D-pad graph.
 *
 * <p>The graph used to take rows in view-tree order. That is right for a stack and wrong for a
 * composed line: in Conexiones the two endpoint rows live in a left column and the LAN switch in a
 * right column, so the tree reads "URL, URL, switch" while the screen reads "URL+switch, URL". On
 * the emulator a DOWN press from the second URL therefore landed on the switch, which sits one row
 * <b>above</b> it - the exact "focus moved the wrong way" class of defect the navigator exists to
 * prevent.
 *
 * <p>The rule here is deliberately small: group cells whose vertical extents overlap into one row,
 * order rows by their top and cells by their left, and never reorder anything that has not been
 * measured. Movement itself stays structural ({@link FocusGrid}): this only decides which cells
 * share a line and in what order the lines come.
 */
public class FocusRowsTest {

    @Test
    public void aVerticalStackKeepsItsOrder() {
        FocusRows.Rows rows = FocusRows.order(
                new int[]{0, 100, 200}, new int[]{0, 0, 0}, new int[]{80, 180, 280});
        assertArrayEquals(new int[]{0, 1, 2}, rows.order);
        assertArrayEquals(new int[]{1, 1, 1}, rows.sizes);
    }

    @Test
    public void aSiblingColumnJoinsTheRowItVisuallyOverlaps() {
        // The emulator's Conexiones panel, measured: the two Copy buttons at x=1324, the LAN switch
        // at x=1666 but level with the first Copy button.
        FocusRows.Rows rows = FocusRows.order(
                new int[]{206, 282, 226},
                new int[]{1324, 1324, 1666},
                new int[]{270, 346, 280});
        // Reading order: M3U copy + LAN switch on one line, then the EPG copy.
        assertArrayEquals(new int[]{0, 2, 1}, rows.order);
        assertArrayEquals(new int[]{2, 1}, rows.sizes);
    }

    @Test
    public void cellsOnOneLineAreOrderedLeftToRightWhateverTheTreeSays() {
        FocusRows.Rows rows = FocusRows.order(
                new int[]{0, 0, 0}, new int[]{900, 100, 500}, new int[]{50, 50, 50});
        assertArrayEquals(new int[]{1, 2, 0}, rows.order);
        assertArrayEquals(new int[]{3}, rows.sizes);
    }

    @Test
    public void aPartiallyOverlappingRowStillBelongsToTheSameLine() {
        FocusRows.Rows rows = FocusRows.order(
                new int[]{0, 40, 120}, new int[]{0, 0, 0}, new int[]{60, 100, 180});
        assertArrayEquals(new int[]{0, 1, 2}, rows.order);
        assertArrayEquals(new int[]{2, 1}, rows.sizes);
    }

    @Test
    public void touchingEdgesAreTwoLinesNotOne() {
        // A row that ends exactly where the next starts is two lines: no overlap, no merge.
        FocusRows.Rows rows = FocusRows.order(
                new int[]{0, 80}, new int[]{0, 0}, new int[]{80, 160});
        assertArrayEquals(new int[]{1, 1}, rows.sizes);
    }

    @Test
    public void anythingUnmeasuredIsLeftInTreeOrder() {
        assertNull(FocusRows.order(
                new int[]{0, 100}, new int[]{0, 0}, new int[]{0, 0}));
        assertNull(FocusRows.order(
                new int[]{0, 100}, new int[]{0, 0}, new int[]{80, 100}));
    }

    @Test
    public void anEmptyGraphIsNotARow() {
        FocusRows.Rows rows = FocusRows.order(new int[0], new int[0], new int[0]);
        assertEquals(0, rows.order.length);
        assertArrayEquals(new int[0], rows.sizes);
    }

    @Test
    public void threeCellsOnOneLineMakeOneRowOfThree() {
        FocusRows.Rows rows = FocusRows.order(
                new int[]{0, 10, 5}, new int[]{0, 900, 400}, new int[]{70, 90, 75});
        assertArrayEquals(new int[]{0, 2, 1}, rows.order);
        assertArrayEquals(new int[]{3}, rows.sizes);
    }
}
