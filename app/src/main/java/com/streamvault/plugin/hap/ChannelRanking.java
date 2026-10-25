package com.streamvault.plugin.hap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Orders channels by what is known about them: the ones that work first, then the ones with more peers,
 * and the untested or failed ones last.
 *
 * <p>A list of hundreds of channels is only useful if the working ones are at the top. Peers are the one
 * number the engine reports that correlates with a channel being alive; they do not predict stutter on
 * their own, which is why "unknown" and "failed" are pushed down rather than guessed at.
 *
 * <p>Pure on purpose: no Android types, so the ordering rules are pinned by plain JUnit tests.
 */
public final class ChannelRanking {

    private ChannelRanking() {
    }

    /** What is known about one channel. */
    public interface Score {

        /** True when the last check found the channel alive. */
        boolean available();

        /** Peers reported by the last check; 0 when there is no result. */
        int peers();
    }

    /** Looks up the score of a channel by its id, or null when it was never checked. */
    public interface Lookup {
        Score of(String contentId);
    }

    /**
     * The ordering, as a comparator: alive before dead, then more peers first, then by name so the
     * result is stable between runs.
     */
    public static <T> Comparator<T> comparator(
            Function<T, String> idOf,
            Function<T, String> nameOf,
            Lookup lookup
    ) {
        return (a, b) -> {
            Score left = lookup == null ? null : lookup.of(idOf.apply(a));
            Score right = lookup == null ? null : lookup.of(idOf.apply(b));
            int byState = Boolean.compare(isRanked(right), isRanked(left));
            if (byState != 0) return byState;

            int leftPeers = left == null ? 0 : Math.max(0, left.peers());
            int rightPeers = right == null ? 0 : Math.max(0, right.peers());
            int byPeers = Integer.compare(rightPeers, leftPeers);
            if (byPeers != 0) return byPeers;

            String leftName = nameOf == null ? "" : String.valueOf(nameOf.apply(a));
            String rightName = nameOf == null ? "" : String.valueOf(nameOf.apply(b));
            return leftName.compareToIgnoreCase(rightName);
        };
    }

    /** A new list with the same items in ranked order. The input is never modified. */
    public static <T> List<T> order(
            List<T> items,
            Function<T, String> idOf,
            Function<T, String> nameOf,
            Lookup lookup
    ) {
        List<T> out = new ArrayList<>(items == null ? 0 : items.size());
        if (items == null) return out;
        out.addAll(items);
        out.sort(comparator(idOf, nameOf, lookup));
        return out;
    }

    /**
     * True when a channel has a result worth ranking above the rest: it answered and it is usable. A
     * failed or never-checked channel sorts below every one that did.
     */
    private static boolean isRanked(Score score) {
        return score != null && score.available();
    }
}
