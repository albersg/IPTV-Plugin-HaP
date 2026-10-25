package com.streamvault.plugin.hap;

import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The channel test list of {@code Canales} as a recycler (S7, D3).
 *
 * <p>Every other list on this screen is short: its rows all fit on screen, they are rebindable in
 * place and {@link InPlaceList} is the cheaper tool. The channel list is the exception - a plugin
 * can hold hundreds of channels, and rebinding them in place means one view hierarchy per row held
 * for as long as the panel is open, which is where this screen spends real memory on a 2 GB box.
 * A recycler keeps only the rows of its viewport, and answers a changed model with the smallest
 * update that produces it.
 *
 * <p>Three things are deliberate here:
 * <ul>
 *   <li>Changing the model is {@link ListAdapter#submitList}: it hands the new list to
 *       {@link ChannelDiff} off the main thread and rebinds only the rows whose value model
 *       changed. An unchanged tick dispatches nothing at all.</li>
 *   <li>The adapter owns no view of its own and knows nothing about how a row is built: the screen
 *       supplies a {@link RowFactory} that creates the row with the design system it already
 *       speaks, and a {@link Row} that fills it. The screen's rows are therefore the same rows they
 *       were, texts, colours and click included.</li>
 *   <li>Nothing outlives the screen. The factory is the only reference this class holds, it is
 *       supplied by the activity that owns the view tree, and the adapter is reachable only from
 *       the {@link RecyclerView} inside that activity's window: it is never stored statically, it
 *       registers no observer of its own and it keeps no {@code Context}. The differ behind
 *       {@link ListAdapter} holds the adapter weakly and delivers a pending diff on the main thread,
 *       so a diff in flight when the screen goes away cannot keep it alive.</li>
 * </ul>
 */
final class ChannelAdapter extends ListAdapter<HapRows.Channel, ChannelAdapter.Holder> {

    /** One row of the list: the view the screen built, and the ability to fill it. */
    interface Row {

        /** The row view. Its layout params must be this list's own. */
        View view();

        /** Shows {@code item}, writing only what differs from what the view already says. */
        void bind(HapRows.Channel item);
    }

    /** Creates one row. Called only when the list needs one more view. */
    interface RowFactory {
        Row newRow();
    }

    /**
     * One row in use. The only thing a holder adds to the screen's row is the gap between rows,
     * which a container used to know from the child count and a recycled row can only learn from
     * its position.
     */
    static final class Holder extends RecyclerView.ViewHolder {

        private final Row row;
        private final int gap;
        /** What the row was last spaced by, so a rebind that changes nothing writes nothing. */
        private int appliedGap = Integer.MIN_VALUE;

        Holder(Row row, int gap) {
            super(row.view());
            this.row = row;
            this.gap = gap;
        }

        void bind(HapRows.Channel item, int position) {
            row.bind(item);
            // The list carries no top padding, so its first row sits flush and every other row
            // keeps the gap the container used to give it.
            applyGap(position == 0 ? 0 : gap);
        }

        private void applyGap(int top) {
            if (appliedGap == top) return;
            ViewGroup.LayoutParams params = itemView.getLayoutParams();
            if (!(params instanceof RecyclerView.LayoutParams)) return;
            RecyclerView.LayoutParams margins = (RecyclerView.LayoutParams) params;
            appliedGap = top;
            if (margins.topMargin == top) return;
            margins.topMargin = top;
            itemView.setLayoutParams(margins);
        }
    }

    private final RowFactory factory;
    private final int gap;

    /**
     * @param factory creates one row's views with the screen's design system
     * @param gap     the gap between two rows, in pixels, resolved by the screen
     */
    ChannelAdapter(RowFactory factory, int gap) {
        super(new ChannelDiff());
        this.factory = factory;
        this.gap = gap;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new Holder(factory.newRow(), gap);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        holder.bind(getItem(position), position);
    }
}
