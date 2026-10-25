package com.streamvault.plugin.hap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM tests for the one place where the shell hands a D-pad press back to the platform (S6, D3).
 *
 * <p>{@link ScrollHandoff} answers a question about numbers only - the direction of the press, the
 * adapter position of the focused row and how many rows the list holds - so the rule that decides
 * whether traversal continues by scrolling is pinned here instead of on a television.
 *
 * <p>What the rule has to keep:
 * <ul>
 *   <li>a vertical press is handed off only while the list still has a row beyond the focused one,
 *       because that is the press the recycler turns into a scroll;</li>
 *   <li>at the last row of the list nothing is handed off, so the shell keeps consuming the press
 *       and focus cannot escape the section;</li>
 *   <li>a view that is not a row of the list, or a list that is empty, hands off nothing;</li>
 *   <li>LEFT and RIGHT are never handed off: horizontal movement is the shell's, everywhere.</li>
 * </ul>
 */
public final class ScrollHandoffTest {

    private static final FocusGrid.Direction UP = FocusGrid.Direction.UP;
    private static final FocusGrid.Direction DOWN = FocusGrid.Direction.DOWN;

    @Test
    public void aVerticalPressIsHandedOffWhileTheListHasARowBeyond() {
        assertTrue("nine rows below the first one", ScrollHandoff.shouldHandOff(DOWN, 0, 10));
        assertTrue("one row below this one", ScrollHandoff.shouldHandOff(DOWN, 8, 10));
        assertTrue("one row above this one", ScrollHandoff.shouldHandOff(UP, 1, 10));
    }

    @Test
    public void theEndsOfTheListAreStillConsumed() {
        assertFalse("the last row has nothing below it", ScrollHandoff.shouldHandOff(DOWN, 9, 10));
        assertFalse("the first row has nothing above it", ScrollHandoff.shouldHandOff(UP, 0, 10));
        assertFalse("a list of one row has no neighbour at all", ScrollHandoff.shouldHandOff(DOWN, 0, 1));
        assertFalse("a list of one row has no neighbour at all", ScrollHandoff.shouldHandOff(UP, 0, 1));
    }

    @Test
    public void aViewThatIsNotARowOfTheListIsNeverHandedOff() {
        assertFalse(
                "RecyclerView reports NO_POSITION for a view it does not own",
                ScrollHandoff.shouldHandOff(DOWN, ScrollHandoff.NO_POSITION, 10)
        );
        assertFalse("an empty list hands nothing off", ScrollHandoff.shouldHandOff(DOWN, 0, 0));
        assertFalse(
                "a position left over from a model that shrank hands nothing off",
                ScrollHandoff.shouldHandOff(DOWN, 12, 10)
        );
    }

    /** Horizontal movement belongs to the shell, so no list may take it back. */
    @Test
    public void horizontalMovementIsNeverHandedOff() {
        assertFalse(ScrollHandoff.shouldHandOff(FocusGrid.Direction.LEFT, 5, 10));
        assertFalse(ScrollHandoff.shouldHandOff(FocusGrid.Direction.RIGHT, 5, 10));
        assertFalse(ScrollHandoff.shouldHandOff(null, 5, 10));
    }
}
