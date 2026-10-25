package com.streamvault.plugin.hap;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The five sections of the HaP shell and the panels each one hosts.
 *
 * <p>Before the redesign every panel was appended to one long scroll. The redesign splits that
 * scroll into sections, and this class is the single place that says where each panel went, so
 * "nothing was deleted, only reorganised" (S8) can be checked without a device. The activity
 * builds its section views from {@link #panels(Section)} and keeps them for the lifetime of the
 * activity, instead of rebuilding them on every navigation.
 *
 * <p>Plain Java on purpose: no android.* imports, so the mapping is pinned by
 * {@code HapSectionsTest} on the JVM.
 */
final class HapSections {

    /** Rail order, top to bottom, and the order of the {@code values()} array. */
    enum Section {
        INICIO,
        CANALES,
        FUENTES,
        CONEXIONES,
        AJUSTES
    }

    /**
     * The four entries of the phone bottom bar, left to right, exactly as the mockup draws them.
     *
     * <p>They are not a second list of sections: three of them open a {@link Section} directly and
     * {@link #MAS} only opens the menu of {@link #moreMenu()}. Adding a section to {@link Section}
     * without deciding where a phone reaches it therefore breaks
     * {@code HapSectionsTest#everySectionIsReachableFromThePhoneShell}, on the JVM, instead of
     * silently leaving a phone user without that section (S5, S6).
     */
    enum MobileEntry {
        INICIO,
        LISTAS,
        CANALES,
        MAS
    }

    /**
     * One entry per panel builder the activity already had. {@code HEADER} is the status panel
     * that owns the start/stop switch; on the new shell it lives inside the home status card.
     */
    enum Panel {
        HEADER,
        DIAGNOSTICS,
        CHANNEL_STATUS,
        SOURCES,
        CUSTOM_CHANNELS,
        AIO,
        EXTERNAL_PLAYERS,
        CLIENTS,
        IPFS,
        IPFS_ADVANCED,
        LOGS,
        PRIVACY,
        UPDATE,
        UPDATE_SETTINGS
    }

    private static final List<Panel> HOME =
            Collections.unmodifiableList(Arrays.asList(Panel.HEADER));
    private static final List<Panel> CHANNELS =
            Collections.unmodifiableList(Arrays.asList(Panel.CHANNEL_STATUS));
    private static final List<Panel> SOURCES =
            Collections.unmodifiableList(Arrays.asList(Panel.SOURCES, Panel.CUSTOM_CHANNELS));
    private static final List<Panel> CONNECTIONS =
            Collections.unmodifiableList(Arrays.asList(
                    Panel.AIO, Panel.EXTERNAL_PLAYERS, Panel.CLIENTS, Panel.DIAGNOSTICS));
    private static final List<Panel> SETTINGS =
            Collections.unmodifiableList(Arrays.asList(
                    Panel.IPFS,
                    Panel.IPFS_ADVANCED,
                    Panel.LOGS,
                    Panel.PRIVACY,
                    Panel.UPDATE,
                    Panel.UPDATE_SETTINGS
            ));

    /**
     * The two sections the phone's fourth entry offers, in rail order. The phone bar has room for
     * four entries and the design keeps five sections, so {@code Más} is the door to these two and
     * nothing else: the television keeps all five as direct entries (S1, S5).
     */
    private static final List<Section> MORE_MENU =
            Collections.unmodifiableList(Arrays.asList(Section.CONEXIONES, Section.AJUSTES));

    private HapSections() {
    }

    /** The panels of one section, in the order they appear inside it. */
    static List<Panel> panels(Section section) {
        switch (section) {
            case INICIO:
                return HOME;
            case CANALES:
                return CHANNELS;
            case FUENTES:
                return SOURCES;
            case CONEXIONES:
                return CONNECTIONS;
            case AJUSTES:
                return SETTINGS;
            default:
                throw new IllegalArgumentException("Unknown section: " + section);
        }
    }

    /**
     * Where BACK goes from {@code current}: any section but Inicio returns to Inicio, and Inicio
     * itself has no target because BACK there leaves the activity.
     */
    static Section backTarget(Section current) {
        if (current == null || current == Section.INICIO) return null;
        return Section.INICIO;
    }

    /**
     * The section a bottom-bar entry opens, or {@code null} for {@link MobileEntry#MAS}, which
     * opens {@link #moreMenu()} instead of navigating.
     */
    static Section mobileSection(MobileEntry entry) {
        switch (entry) {
            case INICIO:
                return Section.INICIO;
            case LISTAS:
                return Section.FUENTES;
            case CANALES:
                return Section.CANALES;
            case MAS:
            default:
                return null;
        }
    }

    /**
     * The bottom-bar entry that owns {@code section}: this is what the phone shell highlights when
     * the section is on screen, so {@code Conexiones} and {@code Ajustes} light up {@code Más}.
     */
    static MobileEntry mobileEntry(Section section) {
        switch (section) {
            case FUENTES:
                return MobileEntry.LISTAS;
            case CANALES:
                return MobileEntry.CANALES;
            case CONEXIONES:
            case AJUSTES:
                return MobileEntry.MAS;
            case INICIO:
            default:
                return MobileEntry.INICIO;
        }
    }

    /** The sections the phone's {@code Más} entry offers, in rail order. Immutable. */
    static List<Section> moreMenu() {
        return MORE_MENU;
    }
}
