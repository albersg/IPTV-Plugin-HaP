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
                        HapSections.Section.EVENTOS,
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
                        HapSections.Panel.CLIENTS,
                        HapSections.Panel.DIAGNOSTICS
                ),
                panels(HapSections.Section.CONEXIONES)
        );
    }

    @Test
    public void ajustesHostsIpfsItsAdvancedOptionsLogsPrivacyAndUpdatesInThatOrder() {
        assertEquals(
                Arrays.asList(
                        HapSections.Panel.IPFS,
                        HapSections.Panel.IPFS_ADVANCED,
                        HapSections.Panel.LOGS,
                        HapSections.Panel.PRIVACY,
                        HapSections.Panel.RECOVERY,
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

    // ------------------------------------------------------------------ phone shell (M3, S5)

    /** The mockup's bottom bar has exactly these four entries, in this order. */
    @Test
    public void phoneBottomBarIsTheFourEntriesOfTheMockupInOrder() {
        assertArrayEquals(
                new HapSections.MobileEntry[]{
                        HapSections.MobileEntry.INICIO,
                        HapSections.MobileEntry.LISTAS,
                        HapSections.MobileEntry.CANALES,
                        HapSections.MobileEntry.MAS
                },
                HapSections.MobileEntry.values()
        );
    }

    /** Three entries open a section directly; Más has no section of its own, it opens a menu. */
    @Test
    public void threeEntriesOpenTheirSectionAndMoreOpensNone() {
        assertEquals(HapSections.Section.INICIO, HapSections.mobileSection(HapSections.MobileEntry.INICIO));
        assertEquals(HapSections.Section.FUENTES, HapSections.mobileSection(HapSections.MobileEntry.LISTAS));
        assertEquals(HapSections.Section.CANALES, HapSections.mobileSection(HapSections.MobileEntry.CANALES));
        assertNull(HapSections.mobileSection(HapSections.MobileEntry.MAS));
    }

    /** Which entry is highlighted for a section: the last two live behind Más. */
    @Test
    public void eachSectionMapsBackToTheBottomBarEntryThatOwnsIt() {
        assertEquals(HapSections.MobileEntry.INICIO, HapSections.mobileEntry(HapSections.Section.INICIO));
        assertEquals(HapSections.MobileEntry.LISTAS, HapSections.mobileEntry(HapSections.Section.FUENTES));
        assertEquals(HapSections.MobileEntry.CANALES, HapSections.mobileEntry(HapSections.Section.CANALES));
        assertEquals(HapSections.MobileEntry.MAS, HapSections.mobileEntry(HapSections.Section.CONEXIONES));
        assertEquals(HapSections.MobileEntry.MAS, HapSections.mobileEntry(HapSections.Section.AJUSTES));
    }

    /** Más offers the two sections the bar has no room for, in rail order. */
    @Test
    public void moreMenuOffersEventsConnectionsAndSettingsInThatOrder() {
        assertEquals(
                Arrays.asList(HapSections.Section.EVENTOS, HapSections.Section.CONEXIONES,
                        HapSections.Section.AJUSTES),
                HapSections.moreMenu()
        );
    }

    /** The menu is read by the layout builder; a mutable list would allow a late add. */
    @Test
    public void moreMenuIsImmutable() {
        try {
            HapSections.moreMenu().add(HapSections.Section.INICIO);
            throw new AssertionError("moreMenu() is mutable");
        } catch (UnsupportedOperationException expected) {
            // immutable, as intended
        }
    }

    /** Nothing is lost on a phone: three direct entries plus the two behind Más cover all five. */
    @Test
    public void everySectionIsReachableFromThePhoneShell() {
        EnumSet<HapSections.Section> direct = EnumSet.noneOf(HapSections.Section.class);
        for (HapSections.MobileEntry entry : HapSections.MobileEntry.values()) {
            HapSections.Section section = HapSections.mobileSection(entry);
            if (section != null) direct.add(section);
        }
        EnumSet<HapSections.Section> behindMore = EnumSet.copyOf(HapSections.moreMenu());
        assertTrue(
                "a section is offered twice on a phone",
                java.util.Collections.disjoint(direct, behindMore)
        );
        EnumSet<HapSections.Section> reachable = EnumSet.copyOf(direct);
        reachable.addAll(behindMore);
        assertEquals("a section is unreachable on a phone", EnumSet.allOf(HapSections.Section.class), reachable);
    }

    /** The two mappings are inverses; the two sections behind Más are the only non-round trip. */
    @Test
    public void bottomBarAndSectionsAgreeInBothDirections() {
        for (HapSections.MobileEntry entry : HapSections.MobileEntry.values()) {
            HapSections.Section section = HapSections.mobileSection(entry);
            if (section != null) assertEquals(entry, HapSections.mobileEntry(section));
        }
        for (HapSections.Section section : HapSections.Section.values()) {
            HapSections.MobileEntry entry = HapSections.mobileEntry(section);
            if (entry == HapSections.MobileEntry.MAS) {
                assertTrue("Más is not reached by a bottom-bar section", HapSections.moreMenu().contains(section));
            } else {
                assertEquals(section, HapSections.mobileSection(entry));
            }
        }
    }
}
