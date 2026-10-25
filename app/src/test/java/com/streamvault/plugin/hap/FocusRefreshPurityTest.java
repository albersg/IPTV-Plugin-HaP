package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Pins the second half of the focus contract (S6): no refresh and no asynchronous result may move,
 * steal or drop the focus.
 *
 * <p>A 1 Hz status refresh and every background result end up calling the same handful of methods,
 * and each of those methods may only touch text, colours and visibility. The rule is structural
 * rather than behavioural: focus may be requested from exactly one place per screen class, and that
 * place must be the one that the user's own action reaches, never the refresh. Reading the sources
 * is what makes that checkable without a device, because a real assertion about focus needs a
 * window, a view tree and a remote.
 *
 * <p>This test fails when somebody adds a {@code requestFocus()} to a render or status method, and
 * that is exactly the regression it exists to catch.
 */
public final class FocusRefreshPurityTest {

    private static final String HAP_SOURCES =
            "app/src/main/java/com/streamvault/plugin/hap/";

    /** Calls that move the focus somewhere, whichever object they are invoked on. */
    private static final String[] FOCUS_MOVING_CALLS = {
            "requestFocus(",
            "clearFocus(",
            "focusSection(",
            "focusRailItem(",
            "restoreFocus("
    };

    /**
     * Methods that run on the 1 Hz tick, on a background result or on a rebuilt list. None of them
     * may move the focus, because the user did nothing to ask for it.
     */
    private static final String[] REFRESH_AND_RESULT_METHODS = {
            "private void refreshRuntimeState()",
            "private void readRuntimeState()",
            "private RuntimeSnapshot buildRuntimeSnapshot(HapBridge.HapStatus status)",
            "private void applyRuntimeSnapshot(RuntimeSnapshot snapshot)",
            "private void applyCaption(TextView view, String text)",
            "private void applySwitches(RuntimeSnapshot snapshot)",
            "private void applyIpfsGateway(String url)",
            "private void renderEndpoints(List<EndpointModel> rows)",
            "private void renderSources()",
            "private void renderCustomChannels()",
            "private void renderClients(List<HapBridge.ConnectedClientInfo> clients)",
            "private void renderChannelPlugins()",
            "private void renderChannels()",
            "private void renderChannelFeedback(String message)",
            "private void applyUpdateCheckResult(UpdateClient.Result result, boolean manual)",
            "private void applyUpdateDownloadResult(UpdateDownloader.Result result)",
            "private void applyUpdateVerification(File file, UpdateVerifier.Result verification)",
            "private void onUpdateDownloadProgress(int percent, long receivedBytes, long totalBytes)",
            "private void showPendingInstallResult()",
            "private void setUpdatesFeedback(String message, int color)",
            "private void refreshUpdateInstalledLabel()"
    };

    /** The focus is requested from exactly one place in the navigator: the move itself. */
    @Test
    public void theNavigatorRequestsFocusFromExactlyOnePlace() {
        String navigator = source("FocusNavigator.java");
        assertEquals(1, count(navigator, "requestFocus("));
        assertTrue(
                "focus must only be moved by the navigator's focusCell",
                methodBody(navigator, "private boolean focusCell(Graph graph, int index)")
                        .contains("requestFocus(")
        );
    }

    /** The screen itself may only move the focus when the focused control is being hidden. */
    @Test
    public void theScreenOnlyMovesFocusAwayFromAControlItHides() {
        String activity = source("HapConfigActivity.java");
        assertEquals(1, count(activity, "requestFocus("));
        assertTrue(
                "the only requestFocus of the screen belongs to hideControl",
                methodBody(activity, "private void hideControl(View control, View focusFallback)")
                        .contains("requestFocus(")
        );
        assertEquals("focus must never be dropped", 0, count(activity, "clearFocus("));
    }

    /** Every refresh, result and render method is focus-neutral. */
    @Test
    public void noRefreshOrResultMethodMovesFocus() {
        String activity = source("HapConfigActivity.java");
        for (String signature : REFRESH_AND_RESULT_METHODS) {
            String body = methodBody(activity, signature);
            for (String call : FOCUS_MOVING_CALLS) {
                assertEquals(
                        signature + " must not call " + call,
                        0,
                        count(body, call)
                );
            }
        }
    }

    /** The home screen's one refresh entry point only re-labels the views it already has. */
    @Test
    public void applyingTheServiceStatesNeverTouchesFocus() {
        String home = source("HomeScreen.java");
        String body = methodBody(home, "void applyServices(HapServiceStates states)");
        for (String call : FOCUS_MOVING_CALLS) {
            assertEquals("applyServices must not call " + call, 0, count(body, call));
        }
        assertEquals("the status card is built once", 0, count(home, "removeAllViews("));
    }

    /**
     * The wiring of the two graphs uses the two edge policies {@code FocusGridTest} pins: the rail
     * wraps inside itself, a section clamps so DOWN on its last row cannot leave it.
     */
    @Test
    public void theRailWrapsAndASectionClamps() {
        String navigator = source("FocusNavigator.java");
        assertTrue(
                "the rail must keep its presses inside the rail",
                navigator.contains("Graph.of(rail, FocusGrid.Edges.WRAPPED, true)")
        );
        assertTrue(
                "a section must keep its presses inside the section",
                navigator.contains("Graph.of(content, FocusGrid.Edges.CLAMPED, false)")
        );
    }

    // ------------------------------------------------------------------ source helpers

    /**
     * Reads one HaP source file. Unit tests run from the module directory, but the same test also
     * has to work when Gradle is started from the repository root, so both layouts are tried.
     */
    private static String source(String fileName) {
        String relative = HAP_SOURCES + fileName;
        File base = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int depth = 0; depth < 4 && base != null; depth++) {
            File candidate = new File(base, relative);
            if (candidate.isFile()) return read(candidate);
            File moduleRelative = new File(base, "src/main/java/com/streamvault/plugin/hap/" + fileName);
            if (moduleRelative.isFile()) return read(moduleRelative);
            base = base.getParentFile();
        }
        throw new AssertionError("source not found: " + relative + " from "
                + System.getProperty("user.dir"));
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("cannot read " + file, error);
        }
    }

    /**
     * The body of one method. The delivered sources keep methods at a four-space indent and close
     * them with a four-space brace, so the first such line after the signature ends the body.
     */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("method not found: " + signature, start >= 0);
        int open = source.indexOf('{', start);
        assertTrue("method has no body: " + signature, open >= 0);
        int close = source.indexOf("\n    }\n", open);
        assertTrue("method body is not closed: " + signature, close >= 0);
        return source.substring(open, close);
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return found;
    }
}
