package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Pins the wiring of the one list that became a recycler (S7, D3).
 *
 * <p>Whether a row was rebound or rebuilt cannot be asserted on the JVM without an android view
 * tree, so the same two things are pinned that {@code InPlaceUpdateTest} pins for the in-place
 * lists: the decision itself ({@code ChannelDiffTest}, {@code ScrollHandoffTest}) and the wiring,
 * read from the sources.
 *
 * <p>What the wiring has to keep:
 * <ul>
 *   <li>the channel list is the <b>only</b> list that left {@link InPlaceList}; the five short
 *       lists stay where they are, because in-place rebinding is the right tool for them;</li>
 *   <li>one adapter is created with the screen and never replaced, so a refresh cannot rebuild
 *       it - it can only hand the differ a new model;</li>
 *   <li>the recycler never animates a row, because a peer result must repaint its row instead of
 *       fading it while the user is standing on it;</li>
 *   <li>exactly one place hands a vertical D-pad press back to the platform, and it is the
 *       recycler hand-off of {@code FocusNavigator}, so the clamp-and-consume rule of the rest of
 *       the shell is not re-opened;</li>
 *   <li>recyclerview is the only dependency this change added.</li>
 * </ul>
 */
public final class ChannelRecyclerTest {

    private static final String HAP_SOURCES = "app/src/main/java/com/streamvault/plugin/hap/";

    // ------------------------------------------------------------------ the list

    /** The channel list left the in-place updater: it is a recycler now, driven by a differ. */
    @Test
    public void theChannelListIsDrivenByTheDiffingAdapter() {
        String activity = source("HapConfigActivity.java");

        assertFalse(
                "the channel list must not be an InPlaceList any more: that is what kept every row in memory",
                activity.contains("InPlaceList<HapRows.Channel>")
        );
        assertEquals("one adapter, created with the screen", 1, count(activity, "new ChannelAdapter("));
        assertEquals("the adapter is installed once and never replaced", 1, count(activity, "setAdapter(channelAdapter)"));
        assertTrue(
                "the rows reach the list through the differ, not through a rebuild",
                activity.contains("channelAdapter.submitList(")
        );
    }

    /** The five short lists keep the in-place updater: converting them would cost more than it saves. */
    @Test
    public void theOtherListsStayInPlace() {
        String activity = source("HapConfigActivity.java");
        String[] lists = {
                "InPlaceList<HapRows.Endpoint>",
                "InPlaceList<HapRows.Source>",
                "InPlaceList<HapRows.CustomChannel>",
                "InPlaceList<HapRows.Client>",
                "InPlaceList<HapRows.PluginTab>"
        };
        for (String list : lists) {
            assertTrue(list + " must stay on the in-place updater", activity.contains(list));
        }
    }

    /** The recycler is a plain list: no row animation, and its own size never depends on its rows. */
    @Test
    public void theRecyclerNeverAnimatesARow() {
        String activity = source("HapConfigActivity.java");

        assertTrue(
                "a result must repaint its row, not fade it under the user's focus",
                activity.contains("setItemAnimator(null)")
        );
        assertTrue(
                "the list has a fixed height, so a row change never re-measures the list",
                activity.contains("setHasFixedSize(true)")
        );
    }

    // ------------------------------------------------------------------ the adapter

    /** The adapter compares the row's value model, and identity and content are two questions. */
    @Test
    public void theAdapterAsksTwoDifferentQuestions() {
        String adapter = source("ChannelAdapter.java");
        assertTrue(
                "the channel list is a ListAdapter over the row value model",
                adapter.contains("extends ListAdapter<HapRows.Channel,")
        );
        assertTrue(
                "a holder only knows how to fill the row the screen gave it",
                adapter.contains("implements RecyclerView.ViewHolder")
                        || adapter.contains("extends RecyclerView.ViewHolder")
        );

        String diff = source("ChannelDiff.java");
        assertTrue(
                "the diff is the one DiffUtil asks for",
                diff.contains("extends DiffUtil.ItemCallback<HapRows.Channel>")
        );
        assertTrue(
                "identity is the channel, so a result rebinds one row instead of replacing it",
                methodBody(diff, "public boolean areItemsTheSame(").contains("contentId")
        );
        assertTrue(
                "content is the whole value model, so an unchanged tick dispatches nothing",
                methodBody(diff, "public boolean areContentsTheSame(").contains("equals(")
        );
        assertFalse(
                "the decision has no android.* dependency: it is pinned on the JVM",
                diff.contains("import android.")
        );
    }

    // ------------------------------------------------------------------ the D-pad

    /** Exactly one hand-off exists, and it is the recycler's; the rest of the tree still clamps. */
    @Test
    public void aVerticalPressIsHandedOffOnlyForARecyclerRow() {
        String navigator = source("FocusNavigator.java");

        assertEquals(
                "there is one, and only one, place where a press is handed back",
                1,
                count(navigator, "ScrollHandoff.shouldHandOff(")
        );
        assertTrue(
                "the hand-off needs a recycler row, not any control",
                navigator.contains("instanceof RecyclerView")
        );
        String rule = source("ScrollHandoff.java");
        assertFalse(
                "the rule itself is plain Java: no android.* import may appear in it",
                rule.contains("import android.")
        );
    }

    // ------------------------------------------------------------------ the build

    /** The catalog declares recyclerview and the build references it, and nothing else joined. */
    @Test
    public void onlyTheRecyclerWasAddedToTheBuild() {
        String catalog = file("gradle/libs.versions.toml");
        assertTrue("the catalog must declare recyclerview", catalog.contains("recyclerview ="));

        String build = file("app/build.gradle.kts");
        assertTrue("the build must reference the catalog entry", build.contains("implementation(libs.recyclerview)"));
        assertEquals(
                "recyclerview is the only dependency this change added",
                2,
                count(build, "implementation(libs.")
        );
    }

    // ------------------------------------------------------------------ source helpers

    private static String source(String fileName) {
        String relative = HAP_SOURCES + fileName;
        File found = resolve(relative);
        if (found == null) throw new AssertionError("source not found: " + relative);
        return read(found);
    }

    private static String file(String relative) {
        File found = resolve(relative);
        if (found == null) throw new AssertionError("file not found: " + relative);
        return read(found);
    }

    private static File resolve(String relative) {
        File base = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int depth = 0; depth < 4 && base != null; depth++) {
            File candidate = new File(base, relative);
            if (candidate.isFile()) return candidate;
            File moduleRelative = new File(base, "src/main/java/com/streamvault/plugin/hap/" + relative);
            if (relative.startsWith(HAP_SOURCES) && moduleRelative.isFile()) return moduleRelative;
            base = base.getParentFile();
        }
        return null;
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("cannot read " + file, error);
        }
    }

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
