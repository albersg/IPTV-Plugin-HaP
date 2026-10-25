package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM tests for the D-pad movement rules of the HaP shell (S6).
 *
 * <p>{@link FocusGrid} is the row/column arithmetic behind {@code FocusNavigator}: it takes the
 * index of the control that has focus and the shape of the graph, and answers which control the
 * press lands on and whether the press was consumed. Nothing here needs a device, which is the
 * point: the movement rule is the part a remote-only interface can only get wrong once.
 *
 * <p>The two graphs of the shell use the two edge policies pinned here: the five rail entries
 * wrap around, so UP on {@code Inicio} lands on {@code Ajustes} instead of jumping into the
 * content, and a section clamps, so DOWN on its last row never leaves it.
 */
public final class FocusGridTest {

    private static FocusGrid clamped(int... rowSizes) {
        return FocusGrid.of(FocusGrid.Edges.CLAMPED, rowSizes);
    }

    private static FocusGrid wrapped(int... rowSizes) {
        return FocusGrid.of(FocusGrid.Edges.WRAPPED, rowSizes);
    }

    // ---------------------------------------------------------------- shape

    /** Rows are sized exactly as given, in reading order, and the flat index walks them in order. */
    @Test
    public void rowsAreSizedAndIndexedInReadingOrder() {
        FocusGrid grid = clamped(2, 3);
        assertEquals(2, grid.rows());
        assertEquals(5, grid.cells());
        assertEquals(2, grid.rowSize(0));
        assertEquals(3, grid.rowSize(1));
        assertEquals(0, grid.startOf(0));
        assertEquals(2, grid.startOf(1));
        assertEquals(0, grid.rowOf(1));
        assertEquals(1, grid.rowOf(2));
        assertEquals(1, grid.rowOf(4));
        assertEquals(0, grid.columnOf(2));
        assertEquals(2, grid.columnOf(4));
        assertEquals(0, grid.entry());
    }

    /** A row without cells is not part of the graph, so a press can never land on nothing. */
    @Test
    public void emptyRowsAreNotPartOfTheGraph() {
        FocusGrid grid = clamped(2, 0, 2);
        assertEquals(2, grid.rows());
        assertEquals(4, grid.cells());
        assertEquals(2, grid.startOf(1));
        // The empty row between them is skipped: DOWN goes from (0,1) to (1,1) and keeps the column.
        assertEquals(3, grid.move(1, FocusGrid.Direction.DOWN).index());
        assertEquals(1, grid.move(3, FocusGrid.Direction.UP).index());
    }

    /** An empty graph has no target at all, so it consumes nothing and the shell keeps the press. */
    @Test
    public void emptyGraphHandsEveryPressBack() {
        FocusGrid grid = clamped();
        assertEquals(0, grid.rows());
        assertEquals(0, grid.cells());
        assertEquals(FocusGrid.NO_INDEX, grid.entry());
        for (FocusGrid.Direction direction : FocusGrid.Direction.values()) {
            FocusGrid.Move move = grid.move(FocusGrid.NO_INDEX, direction);
            assertFalse(direction.toString(), move.handled());
            assertEquals(FocusGrid.NO_INDEX, move.index());
        }
    }

    // ---------------------------------------------------------------- vertical movement

    /** Away from the edges UP and DOWN move exactly one row and keep the column. */
    @Test
    public void upAndDownMoveOneRowAndKeepTheColumn() {
        FocusGrid grid = clamped(3, 3, 3);
        assertEquals(4, grid.move(1, FocusGrid.Direction.DOWN).index());
        assertEquals(1, grid.move(4, FocusGrid.Direction.UP).index());
    }

    /** DOWN on the last row of a section stays on the last row: the section is not left. */
    @Test
    public void downOnTheLastRowOfASectionStaysInsideIt() {
        FocusGrid.Move move = clamped(3, 3).move(5, FocusGrid.Direction.DOWN);
        assertTrue(move.handled());
        assertEquals(5, move.index());
    }

    /** UP on the first row of a section stays on the first row, for the same reason. */
    @Test
    public void upOnTheFirstRowOfASectionStaysInsideIt() {
        FocusGrid.Move move = clamped(3, 3).move(1, FocusGrid.Direction.UP);
        assertTrue(move.handled());
        assertEquals(1, move.index());
    }

    /** A ragged last row keeps the column when it can and takes its last cell when it cannot. */
    @Test
    public void raggedLastRowTakesItsNearestColumn() {
        FocusGrid grid = clamped(3, 3, 1);
        assertEquals(5, grid.move(2, FocusGrid.Direction.DOWN).index());
        assertEquals(6, grid.move(5, FocusGrid.Direction.DOWN).index());
        assertEquals(3, grid.move(6, FocusGrid.Direction.UP).index());
    }

    /** A single control is a section of its own: UP, DOWN and RIGHT stay on it, LEFT asks the rail. */
    @Test
    public void sectionWithASingleControlNeverLeavesIt() {
        FocusGrid grid = clamped(1);
        assertTrue(grid.move(0, FocusGrid.Direction.UP).handled());
        assertEquals(0, grid.move(0, FocusGrid.Direction.UP).index());
        assertTrue(grid.move(0, FocusGrid.Direction.DOWN).handled());
        assertEquals(0, grid.move(0, FocusGrid.Direction.DOWN).index());
        assertTrue(grid.move(0, FocusGrid.Direction.RIGHT).handled());
        assertEquals(0, grid.move(0, FocusGrid.Direction.RIGHT).index());
        FocusGrid.Move left = grid.move(0, FocusGrid.Direction.LEFT);
        assertFalse(left.handled());
        assertEquals(0, left.index());
    }

    /** Nothing focused yet: a press enters the graph at its first control, never in the middle. */
    @Test
    public void nothingFocusedEntersAtTheFirstControl() {
        FocusGrid grid = clamped(3, 3);
        assertEquals(0, grid.move(FocusGrid.NO_INDEX, FocusGrid.Direction.DOWN).index());
        assertEquals(0, grid.move(FocusGrid.NO_INDEX, FocusGrid.Direction.UP).index());
        assertTrue(grid.move(FocusGrid.NO_INDEX, FocusGrid.Direction.DOWN).handled());
    }

    // ---------------------------------------------------------------- rail edges

    /** The rail wraps: UP on its first entry lands on its last one instead of leaving the rail. */
    @Test
    public void railWrapsUpFromTheFirstEntryToTheLast() {
        FocusGrid.Move move = wrapped(1, 1, 1, 1, 1).move(0, FocusGrid.Direction.UP);
        assertTrue(move.handled());
        assertEquals(4, move.index());
    }

    /** The rail wraps: DOWN on its last entry lands on its first one. */
    @Test
    public void railWrapsDownFromTheLastEntryToTheFirst() {
        FocusGrid.Move move = wrapped(1, 1, 1, 1, 1).move(4, FocusGrid.Direction.DOWN);
        assertTrue(move.handled());
        assertEquals(0, move.index());
    }

    /** A wrap keeps the column when the row it lands on is long enough. */
    @Test
    public void wrapKeepsTheColumnWhenTheTargetRowIsLongEnough() {
        FocusGrid grid = wrapped(4, 4);
        assertEquals(7, grid.move(3, FocusGrid.Direction.DOWN).index());
        assertEquals(3, grid.move(7, FocusGrid.Direction.DOWN).index());
        assertEquals(7, grid.move(3, FocusGrid.Direction.UP).index());
    }

    /** A wrap onto a shorter row clamps the column to that row's last cell. */
    @Test
    public void wrapClampsTheColumnOntoAShorterRow() {
        assertEquals(1, wrapped(2, 5).move(4, FocusGrid.Direction.DOWN).index());
    }

    // ---------------------------------------------------------------- horizontal movement

    /** LEFT at the start of a row is not consumed: the shell owns the step out to the rail. */
    @Test
    public void leftAtTheStartOfARowIsHandedBackToTheShell() {
        FocusGrid grid = clamped(3, 3);
        FocusGrid.Move move = grid.move(3, FocusGrid.Direction.LEFT);
        assertFalse(move.handled());
        assertEquals(3, move.index());
    }

    /** LEFT away from the start of a row moves one cell left. */
    @Test
    public void leftMovesOneCellInsideTheRow() {
        FocusGrid.Move move = clamped(3, 3).move(4, FocusGrid.Direction.LEFT);
        assertTrue(move.handled());
        assertEquals(3, move.index());
    }

    /** RIGHT moves one cell and stops at the end of the row instead of guessing a neighbour. */
    @Test
    public void rightMovesOneCellAndStopsAtTheEndOfTheRow() {
        FocusGrid grid = clamped(2);
        assertEquals(1, grid.move(0, FocusGrid.Direction.RIGHT).index());
        FocusGrid.Move last = grid.move(1, FocusGrid.Direction.RIGHT);
        assertTrue(last.handled());
        assertEquals(1, last.index());
        assertNotEquals(2, last.index());
        assertEquals(0, grid.move(1, FocusGrid.Direction.LEFT).index());
    }

    // ---------------------------------------------------------------- remembered positions

    /** An anchor restored into a shorter graph lands on the nearest cell of that row. */
    @Test
    public void cellAtClampsTheColumnWhenRestoringAMemory() {
        FocusGrid grid = clamped(3, 1);
        assertEquals(1, grid.cellAt(0, 1));
        assertEquals(3, grid.cellAt(1, 2));
        assertEquals(FocusGrid.NO_INDEX, grid.cellAt(2, 0));
        assertEquals(FocusGrid.NO_INDEX, grid.cellAt(-1, 0));
        assertEquals(FocusGrid.NO_INDEX, clamped().cellAt(0, 0));
    }
}
