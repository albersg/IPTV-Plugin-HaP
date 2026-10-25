package com.streamvault.plugin.hap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * JVM tests for the section → panel mapping of the HaP shell.
 *
 * <p>These are the rules that keep every existing panel reachable after the redesign was moved
 * from one long programmatic scroll into five sections: each builder must be hosted by exactly
 * one section, in the documented order, and BACK must walk home instead of leaving the activity.
 * The mapping is plain Java with no android.* imports, so it is pinned here instead of on a
 * device.
 */
public final class HapSectionsTest {

    private static List<HapSections.Panel> panels(HapSections.Section section) {
        return HapSections.panels(section);
    }

    @Test
    public void railOrderIsTheOneInTheDesign() {
        assertArrayEquals(
                new HapSections.Section[]{
                        HapSections.Section.INICIO,
                        HapSections.Section.CANALES,
                        HapSections.Section.FUENTES,
                        HapSections.Section.CONEXIONES,
                        HapSections.Section.AJUSTES
                },
                HapSections.Section.values()
        );
    }

    @Test
    public void homeHostsTheStatusCardThatOwnsTheStartStopControl() {
        assertEquals(
                Arrays.asList(HapSections.Panel.HEADER),
                panels(HapSections.Section.INICIO)
        );
    }

    @Test
    public void canalesHostsOnlyTheChannelStatusPanel() {
        assertEquals(
                Arrays.asList(HapSections.Panel.CHANNEL_STATUS),
                panels(HapSections.Section.CANALES)
        );
    }

    @Test
    public void fuentesHostsSourcesAndCustomChannelsInThatOrder() {
        assertEquals(
                Arrays.asList(HapSections.Panel.SOURCES, HapSections.Panel.CUSTOM_CHANNELS),
                panels(HapSections.Section.FUENTES)
        );
    }

    @Test
    public void conexionesHostsAioExternalPlayersAndClientsInThatOrder() {
        assertEquals(
                Arrays.asList(
                        HapSections.Panel.AIO,
                        HapSections.Panel.EXTERNAL_PLAYERS,
                        HapSections.Panel.CLIENTS
                ),
                panels(HapSections.Section.CONEXIONES)
        );
    }

    @Test
    public void ajustesHostsIpfsItsAdvancedOptionsLogsAndUpdatesInThatOrder() {
        assertEquals(
                Arrays.asList(
                        HapSections.Panel.IPFS,
                        HapSections.Panel.IPFS_ADVANCED,
                        HapSections.Panel.LOGS,
                        HapSections.Panel.UPDATE,
                        HapSections.Panel.UPDATE_SETTINGS
                ),
                panels(HapSections.Section.AJUSTES)
        );
    }

    /** The redesign reorganises the panels; it never drops one (S8). */
    @Test
    public void everyPanelIsHostedExactlyOnce() {
        EnumSet<HapSections.Panel> hosted = EnumSet.noneOf(HapSections.Panel.class);
        for (HapSections.Section section : HapSections.Section.values()) {
            for (HapSections.Panel panel : panels(section)) {
                assertTrue(panel + " is hosted by more than one section", hosted.add(panel));
            }
        }
        assertEquals("a panel is no longer hosted by any section", EnumSet.allOf(HapSections.Panel.class), hosted);
    }

    @Test
    public void noSectionIsEmpty() {
        for (HapSections.Section section : HapSections.Section.values()) {
            assertFalse(section + " has no panels", panels(section).isEmpty());
        }
    }

    /** A section view is built from this list once; a mutable list would allow a late add. */
    @Test
    public void panelListsAreImmutable() {
        for (HapSections.Section section : HapSections.Section.values()) {
            try {
                panels(section).add(HapSections.Panel.LOGS);
                throw new AssertionError("panels(" + section + ") is mutable");
            } catch (UnsupportedOperationException expected) {
                // immutable, as intended
            }
        }
    }

    /** BACK from Canales, Fuentes, Conexiones or Ajustes returns to Inicio. */
    @Test
    public void backFromAnyOtherSectionReturnsHome() {
        for (HapSections.Section section : HapSections.Section.values()) {
            if (section == HapSections.Section.INICIO) continue;
            assertEquals(HapSections.Section.INICIO, HapSections.backTarget(section));
        }
    }

    /** BACK on Inicio is not ours: the activity closes. */
    @Test
    public void backOnHomeLeavesTheActivity() {
        assertNull(HapSections.backTarget(HapSections.Section.INICIO));
    }
}
