package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;

/**
 * JVM tests for the words the home screen shows about the server and its three services.
 *
 * <p>The home screen must never hardcode "Activo": the word is derived from the live runtime
 * snapshot, so a stopped, starting, failed or disabled service has to fall out of the same rules
 * the 1 Hz refresh feeds. The mapping is plain Java with no android.* imports, so it is pinned
 * here instead of on a device.
 */
public final class HapServiceStatesTest {

    private static HapServiceStates states(
            boolean desired,
            boolean ace,
            boolean proxy,
            boolean failure,
            boolean ipfsEnabled,
            boolean ipfsRunning,
            boolean ipfsFailure
    ) {
        return HapServiceStates.of(desired, ace, proxy, failure, ipfsEnabled, ipfsRunning, ipfsFailure);
    }

    @Test
    public void serverIsRunningOnceTheProxyAnswers() {
        HapServiceStates running = states(true, true, true, false, false, false, false);
        assertEquals(HapServiceStates.ServerState.RUNNING, running.server);
    }

    @Test
    public void serverIsStartingWhileItIsWantedButNotUp() {
        HapServiceStates starting = states(true, false, false, false, false, false, false);
        assertEquals(HapServiceStates.ServerState.STARTING, starting.server);
    }

    @Test
    public void serverIsStoppedWhenNobodyAskedItToRun() {
        HapServiceStates stopped = states(false, false, false, false, false, false, false);
        assertEquals(HapServiceStates.ServerState.STOPPED, stopped.server);
    }

    @Test
    public void serverIsInErrorWhenAFailureIsStoredAndNothingIsUp() {
        HapServiceStates failed = states(true, false, false, true, false, false, false);
        assertEquals(HapServiceStates.ServerState.ERROR, failed.server);
    }

    @Test
    public void aceServeAndProxyReportTheWordOfTheirOwnLiveState() {
        HapServiceStates both = states(true, true, true, false, false, false, false);
        assertEquals(HapServiceStates.ServiceState.ACTIVE, both.aceServe);
        assertEquals(HapServiceStates.ServiceState.ACTIVE, both.proxy);

        HapServiceStates starting = states(true, false, false, false, false, false, false);
        assertEquals(HapServiceStates.ServiceState.STARTING, starting.aceServe);
        assertEquals(HapServiceStates.ServiceState.STARTING, starting.proxy);

        HapServiceStates stopped = states(false, false, false, false, false, false, false);
        assertEquals(HapServiceStates.ServiceState.STOPPED, stopped.aceServe);
        assertEquals(HapServiceStates.ServiceState.STOPPED, stopped.proxy);

        HapServiceStates failed = states(true, false, false, true, false, false, false);
        assertEquals(HapServiceStates.ServiceState.ERROR, failed.aceServe);
        assertEquals(HapServiceStates.ServiceState.ERROR, failed.proxy);
    }

    /** AceServe and the proxy can disagree: one up, the other still starting. */
    @Test
    public void aServiceIsJudgedOnItsOwnFlagNotOnItsSiblings() {
        HapServiceStates aceUp = states(true, true, false, false, false, false, false);
        assertEquals(HapServiceStates.ServiceState.ACTIVE, aceUp.aceServe);
        assertEquals(HapServiceStates.ServiceState.STARTING, aceUp.proxy);
    }

    @Test
    public void ipfsIsDisabledWhileTheOptionalFeatureIsOff() {
        HapServiceStates off = states(false, false, false, false, false, false, false);
        assertEquals(HapServiceStates.ServiceState.DISABLED, off.ipfs);
    }

    @Test
    public void ipfsReportsRunningStartingAndError() {
        assertEquals(HapServiceStates.ServiceState.ACTIVE_FEMININE,
                states(true, true, true, false, true, true, false).ipfs);
        assertEquals(HapServiceStates.ServiceState.STARTING,
                states(true, true, true, false, true, false, false).ipfs);
        assertEquals(HapServiceStates.ServiceState.ERROR,
                states(true, true, true, false, true, false, true).ipfs);
    }

    /** A stale IPFS error must not survive the feature being switched off. */
    @Test
    public void aDisabledIpfsNeverShowsAFailure() {
        assertEquals(HapServiceStates.ServiceState.DISABLED,
                states(false, false, false, false, false, false, true).ipfs);
    }

    /** All six words of the design must be reachable, or one of them is dead text. */
    @Test
    public void everyStateWordOfTheDesignIsReachable() {
        EnumSet<HapServiceStates.ServiceState> words = EnumSet.noneOf(HapServiceStates.ServiceState.class);
        boolean[] flags = {false, true};
        for (boolean desired : flags) {
            for (boolean ace : flags) {
                for (boolean proxy : flags) {
                    for (boolean failure : flags) {
                        for (boolean ipfsEnabled : flags) {
                            for (boolean ipfsRunning : flags) {
                                for (boolean ipfsFailure : flags) {
                                    HapServiceStates state = states(
                                            desired, ace, proxy, failure, ipfsEnabled, ipfsRunning, ipfsFailure);
                                    words.add(state.aceServe);
                                    words.add(state.proxy);
                                    words.add(state.ipfs);
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(EnumSet.allOf(HapServiceStates.ServiceState.class), words);
        for (HapServiceStates.ServiceState word : words) {
            assertTrue(word.name(), !word.name().isEmpty());
        }
    }

    /** The model is immutable data: the refresh can hand the same instance to the view safely. */
    @Test
    public void equalInputsProduceEqualStates() {
        assertEquals(
                states(true, true, false, true, true, false, true),
                states(true, true, false, true, true, false, true)
        );
    }
}
