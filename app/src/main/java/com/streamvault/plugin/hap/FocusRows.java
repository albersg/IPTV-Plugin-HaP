package com.streamvault.plugin.hap;

/**
 * The visual row order of the D-pad graph (S6).
 *
 * <p>{@link FocusNavigator} collects one {@code Row} per horizontal container or per focusable leaf,
 * in view-tree order. That is correct for a stack and wrong for a composed line: in {@code Conexiones}
 * the two endpoint rows live in a left column and the LAN switch in a right column, so the tree reads
 * "URL, URL, switch" while the screen reads "URL + switch, URL". A DOWN press from the second URL
 * therefore landed on the switch, one row <b>above</b> it.
 *
 * <p>This class only decides which cells share a line and in what order the lines come. Movement
 * stays in {@link FocusGrid}: one row per press, the column preserved and clamped. Sorting by
 * position is not a return to the platform's geometric focus search - nothing here looks at the
 * pressed direction, and no cell is ever chosen by nearest-neighbour.
 *
 * <p>Two rules keep it safe:
 * <ul>
 *   <li>Cells whose vertical extents overlap share a row, ordered left to right. Rows are ordered by
 *       their top edge.</li>
 *   <li>If anything is unmeasured (zero height, which is what a view returns before layout), the
 *       whole answer is {@code null} and the caller keeps the tree order. A key press never reorders
 *       a screen that has not been laid out.</li>
 * </ul>
 *
 * <p>No {@code android.*} import may appear in this file: the rule is pinned on the JVM by
 * {@code FocusRowsTest}.
 */
final class FocusRows {

    /** The cells in visual reading order, and how many of them belong to each row. */
    static final class Rows {
        final int[] order;
        final int[] sizes;

        Rows(int[] order, int[] sizes) {
            this.order = order;
            this.sizes = sizes;
        }
    }

    /**
     * @param tops    top edge of each cell, in tree order
     * @param lefts   left edge of each cell, in tree order
     * @param bottoms bottom edge of each cell, in tree order
     * @return the visual order and row sizes, or {@code null} when a cell has not been measured
     */
    static Rows order(int[] tops, int[] lefts, int[] bottoms) {
        final int count = tops.length;
        if (count != lefts.length || count != bottoms.length) {
            throw new IllegalArgumentException("cell arrays must have one entry each");
        }
        if (count == 0) return new Rows(new int[0], new int[0]);
        for (int i = 0; i < count; i++) {
            if (bottoms[i] <= tops[i]) return null;
        }

        // Insertion sort: at most a few dozen cells, and no boxing on a key press.
        int[] sorted = new int[count];
        for (int i = 0; i < count; i++) sorted[i] = i;
        for (int i = 1; i < count; i++) {
            int value = sorted[i];
            int j = i - 1;
            while (j >= 0 && before(value, sorted[j], tops, lefts)) {
                sorted[j + 1] = sorted[j];
                j--;
            }
            sorted[j + 1] = value;
        }

        int[] order = new int[count];
        int[] sizes = new int[count];
        int rows = 0;
        int cursor = 0;
        int bandBottom = 0;
        int rowStart = 0;
        boolean first = true;
        for (int i = 0; i < count; i++) {
            int cell = sorted[i];
            boolean sameLine = !first && tops[cell] < bandBottom;
            if (!sameLine) {
                if (!first) {
                    sizes[rows] = i - rowStart;
                    rows++;
                    rowStart = i;
                }
                first = false;
                bandBottom = bottoms[cell];
            } else {
                bandBottom = Math.max(bandBottom, bottoms[cell]);
            }
            order[cursor++] = cell;
        }
        sizes[rows] = count - rowStart;
        rows++;

        // Within a line, left to right, whatever order the tree produced.
        int start = 0;
        for (int row = 0; row < rows; row++) {
            int size = sizes[row];
            for (int i = 1; i < size; i++) {
                int value = order[start + i];
                int j = i - 1;
                while (j >= 0 && lefts[value] < lefts[order[start + j]]) {
                    order[start + j + 1] = order[start + j];
                    j--;
                }
                order[start + j + 1] = value;
            }
            start += size;
        }
        int[] trimmed = new int[rows];
        System.arraycopy(sizes, 0, trimmed, 0, rows);
        return new Rows(order, trimmed);
    }

    private static boolean before(int first, int second, int[] tops, int[] lefts) {
        if (tops[first] != tops[second]) return tops[first] < tops[second];
        if (lefts[first] != lefts[second]) return lefts[first] < lefts[second];
        return first < second;
    }

    private FocusRows() {
    }
}
