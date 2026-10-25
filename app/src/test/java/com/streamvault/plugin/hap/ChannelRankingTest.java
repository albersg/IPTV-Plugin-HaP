package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/** Pins the ordering rules of the channel list. */
public class ChannelRankingTest {

    private static final class Item {
        final String id;
        final String name;

        Item(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class Score implements ChannelRanking.Score {
        final boolean available;
        final int peers;

        Score(boolean available, int peers) {
            this.available = available;
            this.peers = peers;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public int peers() {
            return peers;
        }
    }

    private static final Function<Item, String> ID = item -> item.id;
    private static final Function<Item, String> NAME = item -> item.name;

    private List<String> names(List<Item> items) {
        List<String> out = new ArrayList<>();
        for (Item item : items) out.add(item.name);
        return out;
    }

    @Test
    public void workingChannelsComeFirstThenMorePeersThenName() {
        List<Item> items = Arrays.asList(
                new Item("c", "Charlie"),   // vive, 5 peers
                new Item("a", "Alpha"),     // muerto
                new Item("b", "Bravo"),     // vivo, 40 peers
                new Item("d", "Delta")      // sin comprobar
        );
        ChannelRanking.Lookup lookup = id -> {
            switch (id) {
                case "b": return new Score(true, 40);
                case "c": return new Score(true, 5);
                case "a": return new Score(false, 0);
                default: return null;
            }
        };

        List<Item> ordered = ChannelRanking.order(items, ID, NAME, lookup);
        assertEquals(Arrays.asList("Bravo", "Charlie", "Alpha", "Delta"), names(ordered));
    }

    @Test
    public void withoutAnyResultTheOrderIsAlphabetical() {
        List<Item> items = Arrays.asList(
                new Item("3", "Zulu"),
                new Item("1", "alfa"),
                new Item("2", "Bravo")
        );
        assertEquals(Arrays.asList("alfa", "Bravo", "Zulu"),
                names(ChannelRanking.order(items, ID, NAME, id -> null)));
    }

    @Test
    public void equalPeersFallBackToTheName() {
        List<Item> items = Arrays.asList(new Item("x", "Zeta"), new Item("y", "alfa"));
        ChannelRanking.Lookup lookup = id -> new Score(true, 7);
        assertEquals(Arrays.asList("alfa", "Zeta"), names(ChannelRanking.order(items, ID, NAME, lookup)));
    }

    @Test
    public void theInputListIsNeverModified() {
        List<Item> items = new ArrayList<>(Arrays.asList(new Item("a", "Alpha"), new Item("b", "Bravo")));
        ChannelRanking.order(items, ID, NAME, id -> new Score("b".equals(id), "b".equals(id) ? 3 : 0));
        assertEquals("Alpha", items.get(0).name);
        assertEquals("Bravo", items.get(1).name);
    }

    @Test
    public void aNullLookupStillProducesAStableOrder() {
        List<Item> items = Arrays.asList(new Item("b", "Bravo"), new Item("a", "Alpha"));
        assertEquals(Arrays.asList("Alpha", "Bravo"), names(ChannelRanking.order(items, ID, NAME, null)));
    }

    @Test
    public void negativePeersAreTreatedAsNone() {
        List<Item> items = Arrays.asList(new Item("a", "Alpha"), new Item("b", "Bravo"));
        ChannelRanking.Lookup lookup = id -> new Score(true, "a".equals(id) ? -5 : 2);
        assertEquals(Arrays.asList("Bravo", "Alpha"), names(ChannelRanking.order(items, ID, NAME, lookup)));
    }

    @Test
    public void orderingAnEmptyOrNullListIsSafe() {
        assertTrue(ChannelRanking.order(null, ID, NAME, id -> null).isEmpty());
        assertTrue(ChannelRanking.order(new ArrayList<>(), ID, NAME, id -> null).isEmpty());
    }
}
