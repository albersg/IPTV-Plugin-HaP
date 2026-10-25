package com.streamvault.plugin.hap;

/**
 * The one rule that decides whether HaP offers its first-run assistant (T8, S6).
 *
 * <p>The assistant exists to take a fresh install to a working state: turn the server on, add one
 * M3U list, connect a player. It is therefore offered <b>only while HaP has nothing to serve and
 * the user has not already closed it</b>, so it appears when it is needed and never permanently.
 * Three stored facts decide that, and nothing else:
 *
 * <ul>
 *   <li>{@code configuredSources} — how many M3U lists the user added. Zero means HaP cannot serve
 *       anything yet, which is exactly when the assistant is useful.</li>
 *   <li>{@code dismissed} — the user said "not now". A dismissal is a decision and is never
 *       overruled, not even on an install that still has no list.</li>
 *   <li>{@code completed} — the user finished the assistant. Finishing hides it for good as
 *       well.</li>
 * </ul>
 *
 * <p>The facts are read, and the two decisions stored, by {@link FirstRunStore}; this class only
 * answers the question, which is what makes it testable without a device. No {@code android.*}
 * import may appear in this file: the rule is pinned on the JVM by {@code FirstRunDecisionTest}.
 */
final class FirstRunDecision {

    private FirstRunDecision() {
    }

    /**
     * Whether the home screen should offer the assistant as one card.
     *
     * @param configuredSources M3U lists the user added; a negative value counts as none
     * @param dismissed         whether the user already closed the assistant with "not now"
     * @param completed         whether the user already finished the assistant
     * @return true only on an install that has nothing to serve and no decision recorded yet
     */
    static boolean shouldOffer(int configuredSources, boolean dismissed, boolean completed) {
        if (dismissed || completed) return false;
        return configuredSources <= 0;
    }
}
