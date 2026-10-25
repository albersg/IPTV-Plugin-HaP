package com.streamvault.plugin.hap;

/**
 * Pure row/column arithmetic of the D-pad focus graph of the HaP shell (S6).
 *
 * <p>The graph is the set of focusable controls of one container, grouped into the visual rows
 * they appear on: a row is what LEFT/RIGHT walks through, and UP/DOWN move between rows. The
 * shape is passed in as the number of cells each row holds, in reading order, so this class never
 * looks at a {@code View}, a rectangle or a screen. Android's geometric focus search is replaced
 * on purpose: with ragged rows, nested rows inside other rows and controls inside scroll views, a
 * nearest-neighbour search jumps to a control the user did not ask for.
 *
 * <p>Rules, in one place:
 * <ul>
 *   <li>UP and DOWN move exactly one row and keep the column of the row they left, clamped to the
 *       target row's last cell when that row is shorter ("nearest available column"). The clamp is
 *       stateless, so DOWN then UP always comes back to the row it left.</li>
 *   <li>{@link Edges#CLAMPED} (a section): the first and last row consume the press and stay, so a
 *       section can never be left by pressing UP or DOWN past its end.</li>
 *   <li>{@link Edges#WRAPPED} (the navigation rail): UP on the first row lands on the last one and
 *       DOWN on the last one lands on the first, so the rail is a closed loop instead of a place a
 *       press can escape from into the content or out of the window.</li>
 *   <li>LEFT and RIGHT move one cell inside the current row. LEFT at the first cell is not
 *       consumed: what lies to the left of a section is the rail, and only the shell knows that.
 *       RIGHT at the last cell is consumed and stays, so a press never falls back to a guess.</li>
 *   <li>Nothing focused yet: both axes enter the graph at its first control, never in the
 *       middle.</li>
 *   <li>A row without cells is not part of the graph, and an empty graph consumes nothing.</li>
 * </ul>
 *
 * <p>No {@code android.*} import may appear in this file: the movement rule is pinned on the JVM
 * by {@code FocusGridTest}, because a device is not available to verify it and because the rule is
 * the one part of the D-pad contract that is worth testing without one.
 */
final class FocusGrid {

    /** A D-pad press. */
    enum Direction {
        UP,
        DOWN,
        LEFT,
        RIGHT
    }

    /** What UP and DOWN do at the first and last row of the graph. */
    enum Edges {
        /** The ends of the graph stay where they are: the press is consumed and nothing moves. */
        CLAMPED,
        /** The ends of the graph join: the last row leads to the first one and the reverse. */
        WRAPPED
    }

    /** No row, no column, no target: what the graph answers when it has nothing to offer. */
    static final int NO_INDEX = -1;

    /** Where a press lands, and whether the graph consumed it. */
    static final class Move {

        private final int index;
        private final boolean handled;

        private Move(int index, boolean handled) {
            this.index = index;
            this.handled = handled;
        }

        /** A press the graph resolved: it lands on {@code index}. */
        static Move of(int index, boolean handled) {
            return new Move(index, handled);
        }

        /** A press the graph does not own, left for the shell to resolve. */
        static Move unhandled(int index) {
            return new Move(index, false);
        }

        /** Flat index of the control the press lands on, or {@link #NO_INDEX}. */
        int index() {
            return index;
        }

        /** True when the graph consumed the press, even if the index did not change. */
        boolean handled() {
            return handled;
        }
    }

    private final Edges edges;
    /** First cell of every row, in reading order. */
    private final int[] rowStarts;
    /** Cells of every row, in reading order; a row is never empty. */
    private final int[] rowSizes;
    private final int cells;

    private FocusGrid(Edges edges, int[] requestedRowSizes) {
        this.edges = edges;
        int rows = 0;
        for (int size : requestedRowSizes) {
            if (size > 0) rows++;
        }
        this.rowSizes = new int[rows];
        this.rowStarts = new int[rows];
        int start = 0;
        int row = 0;
        for (int size : requestedRowSizes) {
            if (size <= 0) continue;
            rowSizes[row] = size;
            rowStarts[row] = start;
            start += size;
            row++;
        }
        this.cells = start;
    }

    /**
     * Builds a graph from the number of cells of each row, in reading order.
     *
     * @param edges    what UP and DOWN do at the first and last row
     * @param rowSizes cells per row; a row of zero cells is ignored
     */
    static FocusGrid of(Edges edges, int... rowSizes) {
        return new FocusGrid(edges == null ? Edges.CLAMPED : edges,
                rowSizes == null ? new int[0] : rowSizes);
    }

    /** Rows in the graph. */
    int rows() {
        return rowSizes.length;
    }

    /** Cells in the graph. */
    int cells() {
        return cells;
    }

    /** First cell of the graph, or {@link #NO_INDEX} when it has none. */
    int entry() {
        return cells == 0 ? NO_INDEX : 0;
    }

    /** Cells of one row, or {@code 0} when the row is outside the graph. */
    int rowSize(int row) {
        if (row < 0 || row >= rowSizes.length) return 0;
        return rowSizes[row];
    }

    /** Flat index of the first cell of one row, or {@link #NO_INDEX} outside the graph. */
    int startOf(int row) {
        if (row < 0 || row >= rowStarts.length) return NO_INDEX;
        return rowStarts[row];
    }

    /** Row of a flat index, or {@link #NO_INDEX} outside the graph. */
    int rowOf(int index) {
        if (index < 0 || index >= cells) return NO_INDEX;
        for (int row = rowStarts.length - 1; row >= 0; row--) {
            if (index >= rowStarts[row]) return row;
        }
        return NO_INDEX;
    }

    /** Column of a flat index inside its row, or {@link #NO_INDEX} outside the graph. */
    int columnOf(int index) {
        int row = rowOf(index);
        if (row == NO_INDEX) return NO_INDEX;
        return index - rowStarts[row];
    }

    /**
     * The cell one press away from {@code index}.
     *
     * @param index     the control that has focus, or {@link #NO_INDEX} when nothing owns it
     * @param direction the pressed direction
     * @return the target cell and whether the press was consumed; an unhandled move keeps the
     *         current index so the caller can decide what lies outside the graph
     */
    Move move(int index, Direction direction) {
        if (cells == 0 || direction == null) return Move.unhandled(NO_INDEX);
        if (index < 0 || index >= cells) {
            // Nothing focused yet: every axis enters the graph at its first control.
            return Move.of(0, true);
        }

        int row = rowOf(index);
        int column = columnOf(index);

        if (direction == Direction.LEFT || direction == Direction.RIGHT) {
            int target = column + (direction == Direction.LEFT ? -1 : 1);
            // Left of the first cell of a row is outside the graph: the shell owns that step.
            if (target < 0) return Move.unhandled(index);
            // Right of the last cell stays put instead of falling back to a geometric guess.
            if (target >= rowSizes[row]) return Move.of(index, true);
            return Move.of(rowStarts[row] + target, true);
        }

        int targetRow = row + (direction == Direction.UP ? -1 : 1);
        if (targetRow < 0 || targetRow >= rowSizes.length) {
            if (edges == Edges.CLAMPED) return Move.of(index, true);
            targetRow = (targetRow + rowSizes.length) % rowSizes.length;
        }
        int targetColumn = Math.min(column, rowSizes[targetRow] - 1);
        return Move.of(rowStarts[targetRow] + targetColumn, true);
    }

    /**
     * The cell of a remembered row/column position, with the column clamped to that row's last
     * cell. Used to put the user back where they were after a section gained or lost controls.
     *
     * @return the flat index, or {@link #NO_INDEX} when the row is outside the graph
     */
    int cellAt(int row, int column) {
        if (row < 0 || row >= rowSizes.length) return NO_INDEX;
        int clampedColumn = Math.max(0, Math.min(column, rowSizes[row] - 1));
        return rowStarts[row] + clampedColumn;
    }
}
