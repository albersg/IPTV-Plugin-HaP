package com.streamvault.plugin.hap;

import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The D-pad ordering of the first-run screen (T8, S6).
 *
 * <p>The assistant lives in its own activity, so the shell's rail — and with it the shell's
 * {@link FocusNavigator} — does not exist there. What it needs instead is the same guarantee in a
 * simpler shape: <b>one column, walked with UP and DOWN, in the order the screen is built</b>.
 *
 * <p>The controls are read from the live view tree on every press, so a step that hides its action
 * (the server started, so "Turn on server" is gone) or a saved list that grows changes the order
 * without anyone telling this class about it. The layout keeps every control on a line of its own —
 * the two ways out are stacked, not side by side — so "one cell per row" is exact and not an
 * approximation. The movement arithmetic is not re-implemented: the column is handed to
 * {@link FocusGrid} with {@link FocusGrid.Edges#CLAMPED}, which is the same rule the shell's
 * sections use, so the two screens cannot disagree about what UP and DOWN mean.
 *
 * <p>Rules, in one place:
 * <ul>
 *   <li>UP and DOWN move exactly one control. At the first and last control the press is consumed
 *       and nothing moves, so a press can never fall out of the column into a geometric guess.</li>
 *   <li>Nothing focused yet: the press enters the column at its first control.</li>
 *   <li>Only focusable <b>leaves</b> are cells, so a card, a row layout or the scroll wrapper can
 *       never take the focus away from the control inside it.</li>
 *   <li>Nothing hidden, disabled or detached is ever a cell.</li>
 *   <li>A control that gains focus is scrolled into view immediately, never animated.</li>
 *   <li>LEFT and RIGHT are not owned here: on this screen they belong to the text fields' caret,
 *       and there is nothing to the side of the column to move to.</li>
 * </ul>
 *
 * <p>Everything runs on the main thread. The class holds no {@code Context}, registers no listener
 * and keeps no reference to a view outside the activity's own tree, so a finished wizard leaves
 * nothing behind. BACK is not handled here at all: the activity finishes, which is how the user
 * leaves from any step.
 */
final class FirstRunFocus {

    private final View root;
    private final View content;
    /** Cells of the column, in reading order, rebuilt on every press. */
    private final List<View> cells = new ArrayList<>(8);
    /** Reused rectangle: a key press must not allocate one view-sized object per move. */
    private final Rect scrollRect = new Rect();
    /** Key codes whose ACTION_DOWN was consumed, so their ACTION_UP is consumed with them. */
    private final Set<Integer> consumedDownKeys = new HashSet<>(2);

    /**
     * @param root    the root layout of the screen, used to find the focused control
     * @param content the column holding every control of the three steps
     */
    FirstRunFocus(View root, View content) {
        this.root = root;
        this.content = content;
    }

    /**
     * Resolves one D-pad event.
     *
     * @return true when the press was resolved here and must not reach the platform focus search
     */
    boolean handleKeyEvent(KeyEvent event) {
        if (event == null) return false;
        int keyCode = event.getKeyCode();
        boolean up = keyCode == KeyEvent.KEYCODE_DPAD_UP;
        if (!up && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) return consumedDownKeys.remove(keyCode);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        boolean handled = step(up ? FocusGrid.Direction.UP : FocusGrid.Direction.DOWN);
        if (handled) consumedDownKeys.add(keyCode);
        return handled;
    }

    /**
     * Puts focus on the first control of the column: on open, and whenever the control that had
     * the focus disappeared.
     *
     * @return true when a control took the focus
     */
    boolean focusFirst() {
        collect();
        return !cells.isEmpty() && focus(cells.get(0));
    }

    private boolean step(FocusGrid.Direction direction) {
        collect();
        if (cells.isEmpty()) return false;
        int current = cells.indexOf(root.findFocus());
        FocusGrid.Move move = column(cells.size()).move(current, direction);
        if (!move.handled()) return false;
        if (move.index() == current) return true;
        return focus(cells.get(move.index()));
    }

    /** The column as a grid of one-cell rows, which is the same rule the shell's sections use. */
    private static FocusGrid column(int cells) {
        int[] rows = new int[cells];
        Arrays.fill(rows, 1);
        return FocusGrid.of(FocusGrid.Edges.CLAMPED, rows);
    }

    private boolean focus(View target) {
        if (!target.requestFocus()) return false;
        scrollIntoView(target);
        return true;
    }

    /** Keeps a control on screen with a margin, immediately and never animated. */
    private void scrollIntoView(View view) {
        if (view == null || !view.isShown()) return;
        int width = view.getWidth();
        int height = view.getHeight();
        if (width == 0 || height == 0) return; // not measured yet: the platform scrolls it itself
        int margin = Math.round(8 * view.getResources().getDisplayMetrics().density);
        scrollRect.set(0, 0, width, height);
        scrollRect.inset(-margin, -margin);
        view.requestRectangleOnScreen(scrollRect, true);
    }

    private void collect() {
        cells.clear();
        collectInto(content);
    }

    private void collectInto(View view) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (isCell(child)) cells.add(child);
            else collectInto(child);
        }
    }

    /**
     * A cell is a shown, enabled, focusable <b>leaf</b>. Anything that also holds focusable
     * children is a container and never enters the column, which is what stops the cards and the
     * scroll wrapper from taking the focus from the control inside them.
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
}
