package com.streamvault.plugin.hap;

import androidx.recyclerview.widget.DiffUtil;

/**
 * The two questions the channel list asks {@link DiffUtil} (S7, D3).
 *
 * <p>The channel list is the one list long enough that keeping every row in memory costs real
 * memory on a 2 GB box, so it became a recycler: only the rows of the viewport exist as views.
 * That trade has one condition - the list is no longer re-bound by hand, so something has to say
 * what changed. This class is that answer, and it is the part of the trade that decides whether a
 * refresh tick touches the screen at all:
 * <ul>
 *   <li><b>Identity</b> is the channel: two ticks that carry the same AceStream id are the same
 *       row, so a peer result rebinds the row the user is standing on instead of removing and
 *       inserting one. A row without an id has no identity and matches nothing, rather than
 *       collapsing into every other row that has none.</li>
 *   <li><b>Content</b> is the whole value model of {@link HapRows.Channel}. Every field of it is
 *       drawn or decides what a click does, so equal models mean nothing changed and the differ
 *       dispatches no insert, no remove, no move and no rebind - no layout, no scroll, no focus
 *       change.</li>
 * </ul>
 *
 * <p>No {@code android.*} import: {@link DiffUtil} itself is plain Java, so both questions are
 * pinned on the JVM by {@code ChannelDiffTest}, including the count of updates an unchanged model
 * produces.
 */
final class ChannelDiff extends DiffUtil.ItemCallback<HapRows.Channel> {

    @Override
    public boolean areItemsTheSame(HapRows.Channel oldItem, HapRows.Channel newItem) {
        String left = oldItem.contentId;
        String right = newItem.contentId;
        if (left == null || right == null) return false;
        return left.equals(right);
    }

    @Override
    public boolean areContentsTheSame(HapRows.Channel oldItem, HapRows.Channel newItem) {
        return oldItem.equals(newItem);
    }
}
