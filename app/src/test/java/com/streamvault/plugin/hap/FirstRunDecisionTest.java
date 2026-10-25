package com.streamvault.plugin.hap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM tests for the rule that decides whether HaP offers its first-run assistant (T8, S6).
 *
 * <p>The assistant is offered while the app cannot serve anything yet and the user has not already
 * closed it, so the answer is a decision over three stored facts and nothing else: how many M3U
 * lists the user configured, whether it was dismissed, and whether it was completed. The rule is
 * plain Java with no {@code android.*} import, which is why it can be pinned here: a device is not
 * available to check that the card appears when it should and stays away when it must.
 */
public final class FirstRunDecisionTest {

    /** A fresh install that has never opened the assistant: this is the only case that offers it. */
    @Test
    public void noSourcesAndNeverClosedIsOffered() {
        assertTrue(FirstRunDecision.shouldOffer(0, false, false));
    }

    /** One list is enough for HaP to serve: the assistant has nothing left to add. */
    @Test
    public void oneConfiguredSourceHidesTheOffer() {
        assertFalse(FirstRunDecision.shouldOffer(1, false, false));
    }

    @Test
    public void severalConfiguredSourcesHideTheOffer() {
        assertFalse(FirstRunDecision.shouldOffer(4, false, false));
    }

    /** "Not now" is a decision; nothing may overrule it, not even an empty install. */
    @Test
    public void alreadyDismissedIsNotOffered() {
        assertFalse(FirstRunDecision.shouldOffer(0, true, false));
    }

    /** Finishing the assistant counts as a decision too, so it never comes back on its own. */
    @Test
    public void completedIsNotOffered() {
        assertFalse(FirstRunDecision.shouldOffer(0, false, true));
    }

    /** The case that names both facts at once: it was finished and then closed. */
    @Test
    public void dismissedAfterCompletingIsNotOffered() {
        assertFalse(FirstRunDecision.shouldOffer(0, true, true));
    }

    /** Adding a list later never re-opens a closed assistant. */
    @Test
    public void closedAssistantStaysClosedAfterAddingSources() {
        assertFalse(FirstRunDecision.shouldOffer(2, true, true));
    }

    /** A count can never be negative, but a nonsense count must not resurrect the offer. */
    @Test
    public void negativeCountCountsAsNothingConfigured() {
        assertTrue(FirstRunDecision.shouldOffer(-1, false, false));
    }
}
