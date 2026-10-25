package com.streamvault.plugin.hap;

import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A list of children that is updated instead of rebuilt (S7).
 *
 * <p>The screen has six lists that are refreshed from data: endpoints, sources, custom channels,
 * clients, plugin tabs and channel rows. Each of them used to answer a change with
 * {@code removeAllViews()} and a fresh set of children, which threw away the identity of every row
 * — including the one the user had focused. That is why the focus repair in {@link FocusNavigator}
 * exists; this class removes the reason for it.
 *
 * <p>The contract, in one place:
 * <ul>
 *   <li>A model equal to the applied one touches no view at all: no read of the container, no
 *       {@code addView}, no {@code removeView}, no binder call and no inflation. The decision is
 *       {@link ListPatch#sameItems}, which is pure and pinned by a JVM test.</li>
 *   <li>A child is created only when the model needs one more row, and it is created once: the
 *       factory produces a {@link Row} whose {@link Row#bind} can be called any number of times.</li>
 *   <li>A child is removed only from the tail, so every surviving row keeps its index and its
 *       focus. Nothing is ever reordered, cleared or re-created in the middle.</li>
 *   <li>A surviving row is rebound only when its own model item changed. {@code bind} compares
 *       before writing, so re-binding a row that happens to say the same thing writes nothing.</li>
 * </ul>
 *
 * <p>No {@code Context} is captured here and no listener is registered: the container, the factory
 * and the rows are all owned by the activity's view tree, so nothing outlives the screen. Rows
 * removed at the tail are dropped from this list, which is the only reference that keeps them.
 */
final class InPlaceList<T> {

    /** One rendered child and the model item it currently shows. */
    interface Row<T> {

        /** The child view. Its layout params are expected to be set by the factory. */
        View view();

        /** Updates the child to show {@code item}, writing only what differs. */
        void bind(T item);
    }

    /** Creates the child of one row. Called only when the model grows. */
    interface Factory<T> {
        Row<T> create();
    }

    private final ViewGroup container;
    private final Factory<T> factory;
    /** The model currently on screen, in child order. */
    private final List<T> model = new ArrayList<>(0);
    private final List<Row<T>> rows = new ArrayList<>(0);

    InPlaceList(ViewGroup container, Factory<T> factory) {
        this.container = container;
        this.factory = factory;
    }

    /**
     * Shows {@code next} in the container, reusing every child whose index and value are unchanged.
     *
     * @return true when the container was touched, false when the model was already on screen
     */
    boolean apply(List<T> next) {
        List<T> wanted = next == null ? Collections.<T>emptyList() : next;
        if (ListPatch.sameItems(model, wanted)) return false;

        ListPatch.Patch patch = ListPatch.of(rows.size(), wanted.size());
        for (int i = 0; i < patch.reuse(); i++) {
            T item = wanted.get(i);
            if (!Objects.equals(model.get(i), item)) rows.get(i).bind(item);
        }
        for (int i = 0; i < patch.create(); i++) {
            int index = patch.reuse() + i;
            Row<T> row = factory.create();
            rows.add(row);
            container.addView(row.view());
            row.bind(wanted.get(index));
        }
        for (int i = 0; i < patch.drop(); i++) {
            Row<T> row = rows.remove(rows.size() - 1);
            container.removeView(row.view());
        }

        model.clear();
        model.addAll(wanted);
        return true;
    }

    /** Drops every child from the tail: the model became empty, nothing has to be re-created. */
    void clear() {
        apply(Collections.<T>emptyList());
    }

    /** How many rows the container holds, as last applied. */
    int size() {
        return rows.size();
    }
}
