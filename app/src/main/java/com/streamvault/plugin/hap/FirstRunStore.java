package com.streamvault.plugin.hap;

import android.content.Context;
import android.content.SharedPreferences;

import com.jopsis.httpaceserveproxy.HapBridge;

/**
 * The stored facts behind {@link FirstRunDecision}, and the place the user's answer is written
 * (T8, S6).
 *
 * <p>Three facts, one reader each: how many M3U lists the user added, whether the assistant was
 * dismissed, and whether it was completed. Nothing here decides anything — the decision is
 * {@link FirstRunDecision}, which is pure and pinned by a JVM test.
 *
 * <p><b>Blocking by design.</b> Counting sources reads a preferences file and parses the stored
 * list, and {@code commit()} writes one, so every method here touches disk and must be called off
 * the main thread. Its two callers do exactly that: {@link HapConfigActivity} asks on its work
 * executor, and {@link FirstRunActivity} writes on its own.
 *
 * <p>The wizard state has its own preferences file instead of sharing HaP's settings file: it is
 * state of the interface, not of the server, and it must survive a reset of the source list. Both
 * decisions are write-once — they are never cleared, because clearing one would make the assistant
 * reappear on its own, which is the one thing it must never do.
 */
final class FirstRunStore {

    private static final String PREFS_NAME = "hap_first_run";
    private static final String KEY_DISMISSED = "wizard_dismissed";
    private static final String KEY_COMPLETED = "wizard_completed";

    private FirstRunStore() {
    }

    /**
     * Whether the home screen should offer the assistant right now, read from the live facts.
     *
     * <p>Blocking: see the class note.
     */
    static boolean shouldOfferNow(Context context) {
        return FirstRunDecision.shouldOffer(
                userSourceCount(context),
                isDismissed(context),
                isCompleted(context)
        );
    }

    /**
     * M3U lists the user added, which is what "HaP has something to serve" means.
     *
     * <p>{@link HapBridge#sources} also returns the generated {@code Custom} list; only a source the
     * user can remove is one the user configured, so that is the flag this counts. Blocking.
     */
    static int userSourceCount(Context context) {
        int count = 0;
        for (HapBridge.SourceInfo source : HapBridge.sources(context)) {
            if (source.removable) count++;
        }
        return count;
    }

    /** Whether the user closed the assistant with "not now". Blocking. */
    static boolean isDismissed(Context context) {
        return prefs(context).getBoolean(KEY_DISMISSED, false);
    }

    /** Whether the user finished the assistant. Blocking. */
    static boolean isCompleted(Context context) {
        return prefs(context).getBoolean(KEY_COMPLETED, false);
    }

    /** Records "not now", off the main thread. Never cleared. Blocking. */
    static void markDismissed(Context context) {
        prefs(context).edit().putBoolean(KEY_DISMISSED, true).commit();
    }

    /** Records that the user finished, off the main thread. Never cleared. Blocking. */
    static void markCompleted(Context context) {
        prefs(context).edit().putBoolean(KEY_COMPLETED, true).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
