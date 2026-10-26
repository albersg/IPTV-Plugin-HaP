package com.streamvault.plugin.hap;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every switch must carry its own label, because a screen reader announces the switch and not the
 * text beside it.
 *
 * <p>The panels put the label in a {@code TextView} and the control in a sibling {@code Switch}; the
 * visual pairing is invisible to TalkBack, which reads an unlabelled switch as "switch, off" with no
 * subject. This walks every section of the real activity and fails when a switch has no content
 * description. Units cannot see the device reading it, but they can pin the label that makes the
 * device able to.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w960dp-h540dp")
public class ControlLabelsTest {

    private static final int[] NAV_IDS = {
            R.id.hap_nav_home,
            R.id.hap_nav_channels,
            R.id.hap_nav_events,
            R.id.hap_nav_sources,
            R.id.hap_nav_connections,
            R.id.hap_nav_settings,
    };

    private static HapConfigActivity launch() {
        HapConfigActivity activity = Robolectric.buildActivity(HapConfigActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        return activity;
    }

    private static void collectSwitches(View view, List<Switch> out) {
        if (view instanceof Switch) out.add((Switch) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectSwitches(group.getChildAt(i), out);
        }
    }

    @Test
    public void everySwitchInEverySectionCarriesItsOwnLabel() {
        HapConfigActivity activity = launch();
        ViewGroup container = activity.findViewById(R.id.hap_section_container);
        assertNotNull("the shell must host its sections in one container", container);

        Set<Switch> seen = new LinkedHashSet<>();
        List<String> unlabelled = new ArrayList<>();
        for (int id : NAV_IDS) {
            View entry = activity.findViewById(id);
            assertNotNull("missing rail entry", entry);
            entry.performClick();
            ShadowLooper.idleMainLooper();

            List<Switch> switches = new ArrayList<>();
            collectSwitches(container, switches);
            seen.addAll(switches);
            for (Switch control : switches) {
                CharSequence description = control.getContentDescription();
                if (description == null || description.toString().trim().isEmpty()) {
                    unlabelled.add(control.getClass().getSimpleName() + "@" + id);
                }
            }
        }

        assertTrue("no section rendered a switch at all", seen.size() >= 4);
        assertTrue("switches without a TalkBack label: " + unlabelled, unlabelled.isEmpty());
    }
}
