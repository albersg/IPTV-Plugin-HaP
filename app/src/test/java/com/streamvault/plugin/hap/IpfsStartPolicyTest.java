package com.streamvault.plugin.hap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Units for the rule behind the IPFS badge and the launch request.
 *
 * <p>Found on the box: the switch read on and the panel said "starting" with no gateway process,
 * because the badge only looked at the preference. These cases pin the three facts it now needs.
 */
public final class IpfsStartPolicyTest {

    @Test
    public void startingNeedsTheSupervisorToSaySo() {
        assertTrue(IpfsStartPolicy.isStarting(true, false, "Preparing"));
        assertTrue(IpfsStartPolicy.isStarting(true, false, "starting"));
        assertFalse("no phase after a cold start must not read as starting",
                IpfsStartPolicy.isStarting(true, false, ""));
        assertFalse(IpfsStartPolicy.isStarting(true, false, "Running"));
        assertFalse(IpfsStartPolicy.isStarting(true, false, "Failed"));
    }

    @Test
    public void aRunningGatewayIsNeverStarting() {
        assertFalse(IpfsStartPolicy.isStarting(true, true, "Starting"));
    }

    @Test
    public void launchStartsAGatewayThatIsOnButStopped() {
        assertTrue(IpfsStartPolicy.shouldStartOnLaunch(true, false, "", ""));
        assertTrue(IpfsStartPolicy.shouldStartOnLaunch(true, false, null, null));
    }

    @Test
    public void launchLeavesAloneWhatIsAlreadyStartingOrRunning() {
        assertFalse(IpfsStartPolicy.shouldStartOnLaunch(true, false, "Starting", ""));
        assertFalse(IpfsStartPolicy.shouldStartOnLaunch(true, true, "Running", ""));
    }

    @Test
    public void launchDoesNotRetryAFailedGatewayOnEveryRefresh() {
        assertFalse(IpfsStartPolicy.shouldStartOnLaunch(true, false, "Failed", "kubo exited"));
        assertFalse(IpfsStartPolicy.shouldStartOnLaunch(false, false, "", ""));
    }
}
