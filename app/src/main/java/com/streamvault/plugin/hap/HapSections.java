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
     * One entry per panel builder the activity already had. {@code HEADER} is the status panel
     * that owns the start/stop switch; on the new shell it lives inside the home status card.
     */
    enum Panel {
        HEADER,
        CHANNEL_STATUS,
        SOURCES,
        CUSTOM_CHANNELS,
        AIO,
        EXTERNAL_PLAYERS,
        CLIENTS,
        IPFS,
        IPFS_ADVANCED,
        LOGS,
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
            Collections.unmodifiableList(Arrays.asList(Panel.AIO, Panel.EXTERNAL_PLAYERS, Panel.CLIENTS));
    private static final List<Panel> SETTINGS =
            Collections.unmodifiableList(Arrays.asList(
                    Panel.IPFS,
                    Panel.IPFS_ADVANCED,
                    Panel.LOGS,
                    Panel.UPDATE,
                    Panel.UPDATE_SETTINGS
            ));

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
}
