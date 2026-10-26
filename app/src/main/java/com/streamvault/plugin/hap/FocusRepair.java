package com.streamvault.plugin.hap;

/**
 * Pins when the shell must take focus back after the platform moved it.
 *
 * <p>Seen on the box: toggling a service switch disables it while the transition runs, the platform
 * then moves focus to the first focusable view it finds - the Inicio rail entry - and the user's
 * next press acts on the rail instead of on the switch they were operating. The existing repair only
 * fired when focus went <b>nowhere</b> (null, or the old view was detached), so a move to the rail
 * looked intentional.
 *
 * <p>The distinction that matters is why the old control stopped holding focus:
 * <ul>
 *   <li>The user pressed LEFT and the navigator moved focus to the rail itself: the old control is
 *       still attached and enabled, so nothing was taken away.</li>
 *   <li>The old control was detached by a rebuild, or disabled while a transition runs: focus was
 *       taken away, and the section must get it back on a surviving control.</li>
 * </ul>
 *
 * <p>No {@code android.*} import may appear in this file: the rule is pinned on the JVM by
 * {@code FocusRepairTest}; the wiring is verified on the box.
 */
final class FocusRepair {

    /**
     * @param focusedWasInContent  the control that had focus belonged to the visible section
     * @param focusedStillUsable   it is still attached and enabled, so the move was a navigation
     * @param newFocusIsInRail     the platform put focus on a rail entry
     * @return true when the section must take focus back
     */
    static boolean shouldReturnToSection(boolean focusedWasInContent,
                                         boolean focusedStillUsable,
                                         boolean newFocusIsInRail) {
        if (!focusedWasInContent || !newFocusIsInRail) return false;
        return !focusedStillUsable;
    }

    private FocusRepair() {
    }
}
