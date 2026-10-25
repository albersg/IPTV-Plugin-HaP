package com.streamvault.plugin.hap;

/**
 * The one place where a D-pad press is handed back to the platform (S6, D3).
 *
 * <p>{@link FocusNavigator} consumes every UP and DOWN it resolves, because an unplaced vertical
 * press used to fall through to the platform's geometric focus search: on the device that landed
 * focus on the rail or dropped the press entirely. A {@link androidx.recyclerview.widget.RecyclerView}
 * breaks that rule once, and only for itself: it keeps only the rows of its viewport in the view
 * tree, so at the last visible row the focus graph has nothing to move to. Consuming the press
 * there would dead-end traversal - the rows below would be unreachable with the remote - so the
 * press is handed back and the list turns it into a scroll.
 *
 * <p>The hand-off is deliberately arithmetic, so it can be pinned without a television:
 * <ul>
 *   <li>it happens only while the list still holds a row beyond the focused one in the pressed
 *       direction, which is exactly the press the recycler can turn into a scroll;</li>
 *   <li>at the last row of the list nothing is handed off and the shell keeps consuming the press,
 *       so focus still cannot leave the section at its end;</li>
 *   <li>a view that is not a row of the list ({@link #NO_POSITION}), an empty list and a position
 *       left over from a model that shrank hand off nothing;</li>
 *   <li>LEFT and RIGHT are never handed off: horizontal movement is the shell's, everywhere.</li>
 * </ul>
 *
 * <p>No {@code android.*} import may appear in this file: the rule is pinned on the JVM by
 * {@code ScrollHandoffTest}.
 */
final class ScrollHandoff {

    /** What a list answers when the focused view is not one of its rows. */
    static final int NO_POSITION = -1;

    /**
     * @param direction the pressed direction
     * @param position  the adapter position of the focused row, or {@link #NO_POSITION}
     * @param rowCount  how many rows the list holds
     * @return true when the press must reach the list instead of being consumed by the shell
     */
    static boolean shouldHandOff(FocusGrid.Direction direction, int position, int rowCount) {
        if (direction != FocusGrid.Direction.UP && direction != FocusGrid.Direction.DOWN) return false;
        if (position == NO_POSITION || rowCount <= 0 || position >= rowCount) return false;
        return direction == FocusGrid.Direction.DOWN ? position < rowCount - 1 : position > 0;
    }

    private ScrollHandoff() {
    }
}
