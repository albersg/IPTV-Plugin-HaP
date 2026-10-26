package com.streamvault.plugin.hap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pins the rule that decides when the shell takes focus back from the rail. */
public class FocusRepairTest {

    @Test
    public void aControlTakenAwayByADisableReturnsFocusToTheSection() {
        // The box: toggling the IPFS switch disables it while the service starts. The platform moves
        // focus to Inicio, and without this the next press acts on the rail.
        assertTrue(FocusRepair.shouldReturnToSection(true, false, true));
    }

    @Test
    public void pressingLeftToReachTheRailIsNotARepair() {
        // The navigator moved focus itself; the old control is still attached and enabled.
        assertFalse(FocusRepair.shouldReturnToSection(true, true, true));
    }

    @Test
    public void focusThatStaysInTheSectionIsNeverMoved() {
        assertFalse(FocusRepair.shouldReturnToSection(true, false, false));
        assertFalse(FocusRepair.shouldReturnToSection(true, true, false));
    }

    @Test
    public void focusThatWasAlreadyOnTheRailIsLeftAlone() {
        assertFalse(FocusRepair.shouldReturnToSection(false, false, true));
        assertFalse(FocusRepair.shouldReturnToSection(false, true, true));
    }
}
