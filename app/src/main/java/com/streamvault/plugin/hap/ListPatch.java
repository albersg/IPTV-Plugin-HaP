package com.streamvault.plugin.hap;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The arithmetic of an in-place list update: the only decision the HaP screen is allowed to take
 * before it touches a child view (S7).
 *
 * <p>Every list of the screen (endpoints, sources, custom channels, clients, plugin tabs, channel
 * rows) is described by a model that is rebuilt from data. Rebuilding the model is cheap; throwing
 * the children away is not, because it drops their identity, and with it the D-pad focus, the
 * scroll offset and the allocation-free tick. So an update asks three questions here first:
 * <ul>
 *   <li>{@link #sameItems} — is this model the one already on screen? Then the update stops before
 *       it reads, creates, appends or removes a single view.</li>
 *   <li>{@link #of} — how many children are reused at their index, how many are created at the
 *       tail, how many are dropped from the tail. Never anything else: children keep their index
 *       and are never reordered, which is what keeps a focused row the same row.</li>
 *   <li>{@link #needsText} — may this label be written at all? A {@code setText} with the text it
 *       already shows still invalidates the layout.</li>
 * </ul>
 *
 * <p>No {@code android.*} import may appear in this file: the decision is pinned on the JVM by
 * {@code ListPatchTest}, because a device is not available to prove that an unchanged tick really
 * does nothing.
 */
final class ListPatch {

    /** What one update does to the children of a container. */
    static final class Patch {

        private final int reuse;
        private final int create;
        private final int drop;

        private Patch(int reuse, int create, int drop) {
            this.reuse = reuse;
            this.create = create;
            this.drop = drop;
        }

        /** Children that already exist and keep their index. */
        int reuse() {
            return reuse;
        }

        /** Children that have to be created, appended after the reused ones. */
        int create() {
            return create;
        }

        /** Children that have to be removed from the tail. */
        int drop() {
            return drop;
        }

        /** True when the shape did not change, so no child has to be appended or removed. */
        boolean isNoOp() {
            return create == 0 && drop == 0;
        }

        @Override
        public String toString() {
            return "Patch{reuse=" + reuse + ", create=" + create + ", drop=" + drop + '}';
        }
    }

    private ListPatch() {
    }

    /**
     * True when both models describe the same rows, in the same order, with the same values.
     *
     * <p>Value equality, not identity: every refresh builds fresh model objects off the main
     * thread, so an {@code ==} check would always answer "changed" and defeat the whole point.
     * A {@code null} list is read as an empty one.
     */
    static <T> boolean sameItems(List<T> rendered, List<T> wanted) {
        List<T> left = safe(rendered);
        List<T> right = safe(wanted);
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (!Objects.equals(left.get(i), right.get(i))) return false;
        }
        return true;
    }

    /**
     * The plan for a container that currently holds {@code renderedCount} children and has to show
     * {@code wantedCount} rows. Counts below zero are clamped: a negative shape is not a shape and
     * must never reach {@code getChildCount} arithmetic.
     */
    static Patch of(int renderedCount, int wantedCount) {
        int rendered = Math.max(0, renderedCount);
        int wanted = Math.max(0, wantedCount);
        int reuse = Math.min(rendered, wanted);
        return new Patch(reuse, wanted - reuse, rendered - reuse);
    }

    /**
     * True when {@code next} differs from what {@code current} already says, so writing it is
     * worth an invalidation. A {@code null} value is read as the empty label.
     */
    static boolean needsText(CharSequence current, String next) {
        String value = next == null ? "" : next;
        CharSequence shown = current == null ? "" : current;
        return !value.contentEquals(shown);
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? Collections.<T>emptyList() : list;
    }
}
