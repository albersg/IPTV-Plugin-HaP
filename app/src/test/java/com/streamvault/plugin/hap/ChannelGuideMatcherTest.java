package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pins the escalation rules of the guide matcher. */
public class ChannelGuideMatcherTest {

    private static ChannelGuideMatcher.GuideChannel guide(String id, String... names) {
        return new ChannelGuideMatcher.GuideChannel(id, Arrays.asList(names));
    }

    private static List<String[]> channels(String[]... pairs) {
        return new ArrayList<>(Arrays.asList(pairs));
    }

    private static ChannelGuideMatcher.Match matchOf(
            List<String[]> channels,
            List<ChannelGuideMatcher.GuideChannel> guide,
            Map<String, String> aliases
    ) {
        List<ChannelGuideMatcher.Match> matches = ChannelGuideMatcher.matchAll(
                channels, pair -> pair[0], pair -> pair[1], guide, aliases);
        return matches.isEmpty() ? null : matches.get(0);
    }

    @Test
    public void anExactIdWins() {
        ChannelGuideMatcher.Match match = matchOf(
                channels(new String[]{"La 1 HD", "Lo que sea"}),
                Arrays.asList(guide("La 1 HD", "La 1")),
                null);
        assertEquals("La 1 HD", match.guideId);
        assertEquals(ChannelGuideMatcher.Confidence.EXACT_ID, match.confidence);
    }

    @Test
    public void aQualityMarkerDoesNotStopTheNameMatching() {
        // The real case: the playlist builds its ids from the name, the guide publishes the plain one.
        ChannelGuideMatcher.Match match = matchOf(
                channels(new String[]{"DAZN_LaLiga_720p_", "DAZN LaLiga 720p *"}),
                Arrays.asList(guide("DAZN LaLiga HD", "DAZN LaLiga")),
                null);
        assertEquals("DAZN LaLiga HD", match.guideId);
        assertEquals(ChannelGuideMatcher.Confidence.NORMALIZED_NAME, match.confidence);
    }

    @Test
    public void anAliasCoversWhatNormalisationCannot() {
        Map<String, String> aliases = new HashMap<>();
        aliases.put(ChannelGuideMatcher.normalize("M+ Deportes"), "Movistar Plus+ Deportes");
        ChannelGuideMatcher.Match match = matchOf(
                channels(new String[]{"x", "M+ Deportes 1080p **"}),
                Arrays.asList(guide("Movistar Plus+ Deportes", "Movistar Deportes")),
                aliases);
        assertEquals("Movistar Plus+ Deportes", match.guideId);
        assertEquals(ChannelGuideMatcher.Confidence.ALIAS, match.confidence);
    }

    @Test
    public void aWeakNameOverlapIsAcceptedOnlyAsAGuess() {
        ChannelGuideMatcher.Match match = matchOf(
                channels(new String[]{"x", "DAZN LaLiga 2"}),
                Arrays.asList(guide("DAZN LaLiga 2 HD", "DAZN LaLiga 2")),
                null);
        // The names normalise to each other, so this is the name layer, not a guess.
        assertEquals(ChannelGuideMatcher.Confidence.NORMALIZED_NAME, match.confidence);

        ChannelGuideMatcher.Match guessed = matchOf(
                channels(new String[]{"x", "LaLiga TV Hypermotion 720p"}),
                Arrays.asList(guide("LaLiga TV Hypermotion HD", "LaLiga TV Hypermotion")),
                null);
        assertEquals(ChannelGuideMatcher.Confidence.NORMALIZED_NAME, guessed.confidence);
    }

    @Test
    public void anAmbiguousGuessIsRejected() {
        // Two guides share the same words with the channel: guessing would put an event on the wrong one.
        List<ChannelGuideMatcher.GuideChannel> guide = Arrays.asList(
                guide("Canal A Deportes", "Canal A Deportes"),
                guide("Canal B Deportes", "Canal B Deportes"));
        assertNull(matchOf(channels(new String[]{"x", "Deportes Extra"}), guide, null));
    }

    @Test
    public void aChannelWithNoGuideEntrySimplyHasNoMatch() {
        // Not an error: the channel stays in the list and is played as before.
        assertNull(matchOf(
                channels(new String[]{"x", "Canal Inventado Del Todo"}),
                Arrays.asList(guide("La 1 HD", "La 1")),
                null));
    }

    @Test
    public void unmatchedChannelsAreLeftOutAndMatchedOnesKept() {
        List<ChannelGuideMatcher.Match> matches = ChannelGuideMatcher.matchAll(
                channels(
                        new String[]{"DAZN_LaLiga_720p_", "DAZN LaLiga 720p *"},
                        new String[]{"zzz", "Canal Raro Sin Guia"},
                        new String[]{"La 1 HD", "La 1"}),
                pair -> pair[0],
                pair -> pair[1],
                Arrays.asList(guide("DAZN LaLiga HD", "DAZN LaLiga"), guide("La 1 HD", "La 1")),
                null);
        assertEquals(2, matches.size());
        assertEquals("DAZN LaLiga HD", matches.get(0).guideId);
        assertEquals("La 1 HD", matches.get(1).guideId);
    }

    @Test
    public void normalizationRemovesWhatAGuideNeverPublishes() {
        assertEquals("dazn laliga", ChannelGuideMatcher.normalize("DAZN_LaLiga_720p_"));
        assertEquals("dazn laliga", ChannelGuideMatcher.normalize("DAZN LaLiga 1080p **"));
        assertEquals("dazn laliga", ChannelGuideMatcher.normalize("  DAZN  LaLiga  HD  "));
        assertEquals("nandu tv", ChannelGuideMatcher.normalize("Ñandú TV"));
        assertEquals("", ChannelGuideMatcher.normalize(null));
        assertEquals("", ChannelGuideMatcher.normalize("1080p"));
    }

    @Test
    public void similarityCountsSharedWordsOverTheLargerName() {
        assertTrue(ChannelGuideMatcher.similarity(
                Arrays.asList("dazn", "laliga"), Arrays.asList("dazn", "laliga")) > 0.99);
        // An extra word in the guide costs accuracy, so a stray match cannot pass as certain.
        assertTrue(ChannelGuideMatcher.similarity(
                Arrays.asList("dazn", "laliga"), Arrays.asList("dazn", "laliga", "extra")) < 0.7);
        assertEquals(0.0, ChannelGuideMatcher.similarity(
                new ArrayList<>(), Arrays.asList("dazn")), 0.0001);
    }

    @Test
    public void aLongerPlaylistNameStillMatchesTheGuide() {
        ChannelGuideMatcher.Match match = matchOf(
                channels(new String[]{"DAZN_Baloncesto_2_1080p_", "DAZN Baloncesto 2 1080p **"}),
                Arrays.asList(guide("DAZN Baloncesto 2", "DAZN Baloncesto 2")),
                null);
        assertEquals("DAZN Baloncesto 2", match.guideId);
    }
}
