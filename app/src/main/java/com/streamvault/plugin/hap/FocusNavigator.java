package com.streamvault.plugin.hap;

import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The D-pad contract of the HaP shell (S6): where every press lands, what survives a refresh, and
 * what the user comes back to.
 *
 * <p>Android's default movement is a geometric nearest-neighbour search over view bounds. On this
 * screen that search is the wrong tool: rows hold different numbers of controls, a control row is
 * wrapped in a horizontal scroll, the channel list has a scroll of its own and collapsible headers
 * sit between the panels. The search therefore skips rows, lands on controls clipped by the edge of
 * the screen, and answers differently depending on how far the page happens to be scrolled.
 *
 * <p>This class replaces that guess with an order derived from <b>how the screen is built</b>:
 * <ul>
 *   <li>The screen has two graphs: the five rail entries, and the controls of the visible section.
 *       A press is always resolved inside the graph that owns the focused control, so it never
 *       crosses between them by accident.</li>
 *   <li>A <b>row</b> is the set of controls on one visual line: the focusable leaves of a
 *       horizontal container, or a one-cell row for anything else. Rows are read from the live view
 *       tree on every press, so a container that a background result rebuilt, hid or filled never
 *       makes the graph stale.</li>
 *   <li>The movement arithmetic itself lives in {@link FocusGrid}, which has no {@code android.*}
 *       import and is pinned by a JVM test.</li>
 *   <li>Only focusable <b>leaves</b> are cells, so a scroll wrapper or a row layout can never take
 *       the focus away from the control inside it.</li>
 *   <li>Nothing GONE, disabled or detached is ever a cell.</li>
 * </ul>
 *
 * <p>Three things are repaired rather than guessed:
 * <ul>
 *   <li><b>Scroll</b>: a control that gains focus is scrolled into view with a margin, immediately,
 *       never animated, so a D-pad journey never focuses something off-screen.</li>
 *   <li><b>Memory</b>: the last control of each section is remembered as a row/column position, so
 *       coming back to a section restores it instead of starting at the top.</li>
 *   <li><b>Repair</b>: when a rebuild removes the focused control, the platform moves focus
 *       somewhere else (typically the first rail entry). The user is put back on the nearest
 *       surviving control of the same section instead.</li>
 * </ul>
 *
 * <p>Everything runs on the main thread, so a 1 Hz refresh can never interleave with a key press.
 * The class holds no {@code Context} and no {@code Runnable}; its single listener is removed by
 * {@link #detach()}, and every remembered view is weakly held.
 */
final class FocusNavigator {

    /** One visual line: the controls a LEFT/RIGHT press walks through. */
    private static final class Row {
        final List<View> cells = new ArrayList<>(4);
    }

    /** A remembered place in a graph. */
    private static final class Anchor {
        /** Weak: a control that a rebuild threw away must not be kept alive by this memory. */
        final WeakReference<View> view;
        final int row;
        final int column;
        final boolean rail;

        Anchor(View view, int row, int column, boolean rail) {
            this.view = new WeakReference<>(view);
            this.row = row;
            this.column = column;
            this.rail = rail;
        }
    }

    /** The live graph of one container: its cells in reading order plus their row/column shape. */
    private static final class Graph {
        private final FocusGrid grid;
        private final List<View> cells;
        private final boolean rail;

        private Graph(FocusGrid grid, List<View> cells, boolean rail) {
            this.grid = grid;
            this.cells = cells;
            this.rail = rail;
        }

        static Graph of(View container, FocusGrid.Edges edges, boolean rail) {
            List<Row> rows = new ArrayList<>();
            collect(container, rows);
            int[] sizes = new int[rows.size()];
            int cells = 0;
            for (int i = 0; i < rows.size(); i++) {
                sizes[i] = rows.get(i).cells.size();
                cells += sizes[i];
            }
            List<View> flat = new ArrayList<>(cells);
            for (Row row : rows) flat.addAll(row.cells);
            return new Graph(FocusGrid.of(edges, sizes), flat, rail);
        }

        int indexOf(View view) {
            return view == null ? FocusGrid.NO_INDEX : cells.indexOf(view);
        }

        View cellAt(int index) {
            return index < 0 || index >= cells.size() ? null : cells.get(index);
        }

        boolean isEmpty() {
            return cells.isEmpty();
        }
    }

    private final View root;
    private final View rail;
    private final View content;

    /** Last control used in each section, so returning to a section restores it (S6). */
    private final Map<HapSections.Section, Anchor> sectionAnchors =
            new EnumMap<>(HapSections.Section.class);
    /** Where the user was, whichever graph they were in. */
    private Anchor lastAnchor;
    /** Where the user was when this window lost focus, restored when it comes back. */
    private Anchor pausedAnchor;

    private HapSections.Section section = HapSections.Section.INICIO;

    /** Key codes whose ACTION_DOWN was consumed, so their ACTION_UP is consumed with them. */
    private final Set<Integer> consumedDownKeys = new HashSet<>(4);
    /** Reused rectangle: a key press must not allocate one view-sized object per move. */
    private final Rect scrollRect = new Rect();

    private ViewTreeObserver.OnGlobalFocusChangeListener focusWatcher;
    private boolean detached;

    /**
     * @param root    the root layout of the screen, used to find the focused control and to watch
     *                focus changes
     * @param rail    the container holding the five rail entries
     * @param content the container whose visible child is the current section
     */
    FocusNavigator(View root, View rail, View content) {
        this.root = root;
        this.rail = rail;
        this.content = content;
    }

    // ------------------------------------------------------------------ wiring

    /** Registers the one listener this class owns. Paired with {@link #detach()}. */
    void attach() {
        if (focusWatcher != null || detached) return;
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (!observer.isAlive()) return;
        focusWatcher = this::onFocusChanged;
        observer.addOnGlobalFocusChangeListener(focusWatcher);
    }

    /** Removes the listener, so a finished activity leaves nothing behind. */
    void detach() {
        detached = true;
        consumedDownKeys.clear();
        if (focusWatcher == null) return;
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnGlobalFocusChangeListener(focusWatcher);
        focusWatcher = null;
    }

    /** Tells the navigator which section is on screen, so each one keeps its own memory. */
    void setSection(HapSections.Section section) {
        if (section != null) this.section = section;
    }

    // ------------------------------------------------------------------ keys

    /**
     * Resolves one D-pad event.
     *
     * @return true when the press was resolved here and must not reach the platform focus search
     */
    boolean handleKeyEvent(KeyEvent event) {
        if (detached || event == null) return false;
        int keyCode = event.getKeyCode();
        if (!isDirection(keyCode)) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) return consumedDownKeys.remove(keyCode);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        boolean handled = press(keyCode);
        if (handled) consumedDownKeys.add(keyCode);
        return handled;
    }

    private boolean press(int keyCode) {
        View focused = root.findFocus();

        if (isInside(focused, rail)) {
            // RIGHT enters the section and LEFT has nowhere to go: the rail is the left edge of the
            // window, so the press is consumed instead of being handed to a geometric search.
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return focusSection();
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true;
            return moveWithin(Graph.of(rail, FocusGrid.Edges.WRAPPED, true), focused, keyCode);
        }

        if (isInside(focused, content)) {
            // The caret keeps LEFT/RIGHT while the text field still has room in that direction, so
            // a URL stays editable and only its ends hand the press back to the shell.
            if (focused instanceof EditText && caretHasRoom((EditText) focused, keyCode)) return false;
            Graph graph = Graph.of(content, FocusGrid.Edges.CLAMPED, false);
            if (moveWithin(graph, focused, keyCode)) return true;
            // LEFT from the first control of a row has nothing to its left inside the section, so
            // it returns to the rail entry of the section that is on screen.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return focusRailItem(section);
            return false;
        }

        // Focus is nowhere the shell owns it: the next press puts it back where the user was
        // instead of leaving the D-pad dead.
        Anchor anchor = lastAnchor != null ? lastAnchor : pausedAnchor;
        if (anchor == null) return false;
        return focusAnchor(graphOf(anchor), anchor);
    }

    /** One press inside one graph; true when the graph placed it. */
    private boolean moveWithin(Graph graph, View focused, int keyCode) {
        FocusGrid.Direction direction = directionOf(keyCode);
        if (direction == null || graph.isEmpty()) return false;
        int current = graph.indexOf(focused);
        FocusGrid.Move move = graph.grid.move(current, direction);
        if (!move.handled()) return false;
        if (move.index() == current || move.index() == FocusGrid.NO_INDEX) return true;
        return focusCell(graph, move.index());
    }

    private static boolean isDirection(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private static FocusGrid.Direction directionOf(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return FocusGrid.Direction.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return FocusGrid.Direction.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return FocusGrid.Direction.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return FocusGrid.Direction.RIGHT;
            default:
                return null;
        }
    }

    /** True when LEFT/RIGHT would move the caret of this text field instead of leaving it. */
    private static boolean caretHasRoom(EditText edit, int keyCode) {
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return false;
        }
        int start = edit.getSelectionStart();
        int end = edit.getSelectionEnd();
        if (start < 0 || end < 0) return false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return start > 0;
        return end < edit.getText().length();
    }

    // ------------------------------------------------------------------ focus memory

    /**
     * Puts focus on the control this section was left on, or on its first control.
     *
     * @return true when a control took the focus
     */
    boolean focusSection() {
        return focusAnchor(
                Graph.of(content, FocusGrid.Edges.CLAMPED, false),
                sectionAnchors.get(section)
        );
    }

    /**
     * Puts focus on the rail entry of {@code target}. Rail cells are the section entries in rail
     * order, which is the order {@code bindNavigation} creates them in.
     *
     * @return true when the entry took the focus
     */
    boolean focusRailItem(HapSections.Section target) {
        Graph graph = Graph.of(rail, FocusGrid.Edges.WRAPPED, true);
        int index = target == null ? FocusGrid.NO_INDEX : target.ordinal();
        if (index < 0 || index >= graph.cells.size()) index = graph.grid.entry();
        if (index == FocusGrid.NO_INDEX) return false;
        return focusCell(graph, index);
    }

    /** Remembers the control that has focus, before this window loses it. */
    void rememberFocus() {
        if (detached) return;
        View focused = root.findFocus();
        if (focused == null) return; // keep the previous memory rather than forgetting it
        if (!isInside(focused, rail) && !isInside(focused, content)) return;
        save(focused);
        if (lastAnchor != null) pausedAnchor = lastAnchor;
    }

    /**
     * Puts the user back on the control that a dialog, another screen or the installer left behind.
     * Does nothing while the shell still owns the focus, so it can never steal it.
     */
    void restoreFocus() {
        if (detached || pausedAnchor == null) return;
        if (rail.hasFocus() || content.hasFocus()) return;
        focusAnchor(graphOf(pausedAnchor), pausedAnchor);
    }

    private Graph graphOf(Anchor anchor) {
        return anchor.rail
                ? Graph.of(rail, FocusGrid.Edges.WRAPPED, true)
                : Graph.of(content, FocusGrid.Edges.CLAMPED, false);
    }

    private boolean focusAnchor(Graph graph, Anchor anchor) {
        if (graph.isEmpty()) return false;
        if (anchor != null) {
            int index = graph.indexOf(anchor.view.get());
            if (index != FocusGrid.NO_INDEX) return focusCell(graph, index);
            int nearest = graph.grid.cellAt(anchor.row, anchor.column);
            if (nearest != FocusGrid.NO_INDEX) return focusCell(graph, nearest);
        }
        int entry = graph.grid.entry();
        return entry != FocusGrid.NO_INDEX && focusCell(graph, entry);
    }

    private boolean focusCell(Graph graph, int index) {
        View target = graph.cellAt(index);
        if (target == null) return false;
        if (!target.requestFocus()) return false;
        remember(target, graph.grid.rowOf(index), graph.grid.columnOf(index), graph.rail);
        scrollIntoView(target);
        return true;
    }

    private void remember(View view, int row, int column, boolean rail) {
        Anchor anchor = new Anchor(view, row, column, rail);
        if (!rail) sectionAnchors.put(section, anchor);
        lastAnchor = anchor;
    }

    /** Saves a control the platform focused without asking us, e.g. a click or the first focus. */
    private void save(View focused) {
        boolean onRail = isInside(focused, rail);
        if (!onRail && !isInside(focused, content)) return;
        Graph graph = Graph.of(onRail ? rail : content,
                onRail ? FocusGrid.Edges.WRAPPED : FocusGrid.Edges.CLAMPED, onRail);
        int index = graph.indexOf(focused);
        if (index == FocusGrid.NO_INDEX) return;
        remember(focused, graph.grid.rowOf(index), graph.grid.columnOf(index), onRail);
    }

    // ------------------------------------------------------------------ repair after a rebuild

    /**
     * The shell's one focus watcher.
     *
     * <p>A background result that rebuilds a list removes the focused control with the rest of the
     * list, and the platform then moves focus to whatever it finds first: the page jumps away from
     * the user while it is waiting. When the control that had focus is gone, or focus went nowhere
     * at all, the user is put back on the nearest surviving control of the same graph. Focus that
     * is still valid is never touched, so a refresh can not move anybody.
     */
    private void onFocusChanged(View oldFocus, View newFocus) {
        if (detached || !root.hasWindowFocus()) return;
        boolean lost = newFocus == null || (oldFocus != null && !oldFocus.isAttachedToWindow());
        if (lost) {
            Anchor anchor = lastAnchor != null ? lastAnchor : pausedAnchor;
            if (anchor != null) focusAnchor(graphOf(anchor), anchor);
            return;
        }
        if (lastAnchor != null && lastAnchor.view.get() == newFocus) return;
        save(newFocus);
    }

    // ------------------------------------------------------------------ scrolling

    /**
     * Keeps a control on screen with a margin, at any nesting depth: the channel list scrolls
     * itself and the section scrolls the page. Never animated, so there is no lag between the press
     * and the control being visible.
     */
    private void scrollIntoView(View view) {
        if (view == null || !view.isShown()) return;
        int width = view.getWidth();
        int height = view.getHeight();
        // Not measured yet: the platform already scrolls a control into view when it gains focus,
        // and a posted runnable would outlive the screen for nothing.
        if (width == 0 || height == 0) return;
        int margin = Math.round(8 * view.getResources().getDisplayMetrics().density);
        scrollRect.set(0, 0, width, height);
        scrollRect.inset(-margin, -margin);
        view.requestRectangleOnScreen(scrollRect, true);
    }

    // ------------------------------------------------------------------ view tree to rows

    /** Rows of one container, in reading order. */
    private static void collect(View view, List<Row> rows) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        if (isHorizontal(group)) {
            collectHorizontal(group, rows);
            return;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (isCell(child)) {
                Row single = new Row();
                single.cells.add(child);
                rows.add(single);
            } else {
                collect(child, rows);
            }
        }
    }

    /**
     * One visual line. A block nested inside the line (the endpoint list beside the LAN switch) is a
     * stack of rows of its own and is emitted first, so the graph keeps the reading order of the
     * layout instead of flattening one column into another.
     */
    private static void collectHorizontal(ViewGroup group, List<Row> rows) {
        List<View> cells = new ArrayList<>(group.getChildCount());
        List<Row> nested = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (isCell(child)) cells.add(child);
            else collect(child, nested);
        }
        rows.addAll(nested);
        if (!cells.isEmpty()) {
            Row row = new Row();
            row.cells.addAll(cells);
            rows.add(row);
        }
    }

    private static boolean isHorizontal(ViewGroup group) {
        if (group instanceof HorizontalScrollView) return true;
        return group instanceof LinearLayout
                && ((LinearLayout) group).getOrientation() == LinearLayout.HORIZONTAL;
    }

    /**
     * A cell is a shown, enabled, focusable <b>leaf</b>. Anything that also holds focusable children
     * is a container and never enters the graph, which is what stops scroll wrappers and row layouts
     * from taking the focus from the control inside them.
     */
    private static boolean isCell(View view) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (!view.isShown() || !view.isEnabled() || !view.isFocusable()) return false;
        return !hasFocusableDescendant(view);
    }

    private static boolean hasFocusableDescendant(View view) {
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child.isFocusable() || hasFocusableDescendant(child)) return true;
        }
        return false;
    }

    private static boolean isInside(View view, View ancestor) {
        if (view == null || ancestor == null) return false;
        View current = view;
        while (current != null) {
            if (current == ancestor) return true;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }
}
