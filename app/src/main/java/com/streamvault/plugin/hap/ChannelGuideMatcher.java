package com.streamvault.plugin.hap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Matches the channels of a playlist to the channels of an XMLTV guide.
 *
 * <p>Playlists rarely carry usable guide identifiers: this app's own lists build them from the channel
 * name ({@code DAZN_LaLiga_720p_}) while the guide publishes the real one ({@code DAZN LaLiga HD}), so a
 * direct comparison matches nothing. Names are also full of quality markers a guide never has.
 *
 * <p>Matching therefore escalates, and each step only sees what the previous one left unmatched:
 * <ol>
 *   <li><b>exact id</b> - when the playlist does ship real ids, this is authoritative;</li>
 *   <li><b>normalised name</b> - quality markers, underscores, accents and punctuation removed;</li>
 *   <li><b>alias</b> - a small table for the names a playlist and a guide spell differently;</li>
 *   <li><b>token similarity</b> - last resort, and always reported as {@link Confidence#PROBABLE} so the
 *       user is never shown a guess as a fact.</li>
 * </ol>
 *
 * <p>A channel that matches nothing is not an error and is not hidden: it simply has no guide entry.
 *
 * <p>Pure on purpose: no Android types and no I/O, so every rule is pinned by plain JUnit tests.
 */
public final class ChannelGuideMatcher {

    /** How sure the matcher is about one channel. */
    public enum Confidence {
        /** The playlist id is the guide id. */
        EXACT_ID,
        /** The names are the same once normalised. */
        NORMALIZED_NAME,
        /** An alias entry says they are the same channel. */
        ALIAS,
        /** Only the meaningful words overlap: shown as a guess, never as a fact. */
        PROBABLE
    }

    /** One channel of the guide: its id and every name it publishes. */
    public static final class GuideChannel {
        public final String id;
        public final List<String> names;

        public GuideChannel(String id, List<String> names) {
            this.id = id == null ? "" : id;
            this.names = names == null ? new ArrayList<>() : new ArrayList<>(names);
        }
    }

    /** A resolved pairing. */
    public static final class Match {
        public final String channelId;
        public final String guideId;
        public final Confidence confidence;

        Match(String channelId, String guideId, Confidence confidence) {
            this.channelId = channelId;
            this.guideId = guideId;
            this.confidence = confidence;
        }
    }

    /** Minimum share of shared words for the last layer to accept a pairing. */
    static final double PROBABLE_THRESHOLD = 0.6;

    private ChannelGuideMatcher() {
    }

    /** Strips what a guide never publishes: quality markers, separators, accents and punctuation. */
    public static String normalize(String value) {
        if (value == null) return "";
        String text = value.toLowerCase(java.util.Locale.ROOT)
                .replace('_', ' ')
                .replace('+', ' ')
                .replace('*', ' ');
        text = ChannelSearch.normalize(text);
        StringBuilder out = new StringBuilder(text.length());
        for (String token : text.split("[^a-z0-9ñ]+")) {
            if (token.isEmpty() || isQualityToken(token)) continue;
            if (out.length() > 0) out.append(' ');
            out.append(token);
        }
        return out.toString().trim();
    }

    private static boolean isQualityToken(String token) {
        switch (token) {
            case "240p":
            case "360p":
            case "420p":
            case "480p":
            case "576p":
            case "720p":
            case "1080p":
            case "1440p":
            case "2160p":
            case "4k":
            case "8k":
            case "sd":
            case "hd":
            case "fhd":
            case "uhd":
            case "hdr":
            case "h265":
            case "hevc":
            case "sd1":
                return true;
            default:
                return false;
        }
    }

    /**
     * Resolves every channel it can and leaves the rest out of the result.
     *
     * @param channels   the playlist channels, in list order
     * @param idOf       the playlist id of a channel
     * @param nameOf     the name of a channel as the playlist shows it
     * @param guide      the guide channels
     * @param aliases    normalised playlist name to guide id, may be null
     */
    public static <T> List<Match> matchAll(
            List<T> channels,
            Function<T, String> idOf,
            Function<T, String> nameOf,
            List<GuideChannel> guide,
            Map<String, String> aliases
    ) {
        List<Match> out = new ArrayList<>();
        if (channels == null || guide == null) return out;

        Map<String, String> byId = new LinkedHashMap<>();
        Map<String, String> byName = new LinkedHashMap<>();
        for (GuideChannel channel : guide) {
            if (channel.id.isEmpty()) continue;
            // Only the raw id is authoritative. Its normalised form goes into the name index, because
            // a normalised comparison is exactly that: a name match, not an identifier match.
            byId.putIfAbsent(channel.id.trim().toLowerCase(java.util.Locale.ROOT), channel.id);
            for (String name : channel.names) {
                byName.putIfAbsent(normalize(name), channel.id);
            }
            byName.putIfAbsent(normalize(channel.id), channel.id);
        }

        for (T channel : channels) {
            String id = idOf == null ? "" : String.valueOf(idOf.apply(channel));
            String name = nameOf == null ? "" : String.valueOf(nameOf.apply(channel));
            Match resolved = resolve(id, name, byId, byName, aliases);
            if (resolved != null) out.add(resolved);
        }
        return out;
    }

    private static Match resolve(
            String channelId,
            String channelName,
            Map<String, String> byId,
            Map<String, String> byName,
            Map<String, String> aliases
    ) {
        String normalizedName = normalize(channelName);

        String exact = byId.get(channelId.trim().toLowerCase(java.util.Locale.ROOT));
        if (exact != null) return new Match(channelId, exact, Confidence.EXACT_ID);

        String byNormalizedName = byName.get(normalizedName);
        if (byNormalizedName != null) {
            return new Match(channelId, byNormalizedName, Confidence.NORMALIZED_NAME);
        }

        if (aliases != null) {
            String alias = aliases.get(normalizedName);
            if (alias != null && !alias.trim().isEmpty()) {
                return new Match(channelId, alias.trim(), Confidence.ALIAS);
            }
        }

        String probable = bestByName(normalizedName, byName);
        if (probable != null) return new Match(channelId, probable, Confidence.PROBABLE);
        return null;
    }

    /**
     * The guide id whose names share enough words with {@code normalizedName}, or null. Ties and weak
     * overlaps are rejected: a wrong pairing is worse than none, because it would put an event on a
     * channel that is not showing it.
     */
    private static String bestByName(String normalizedName, Map<String, String> byName) {
        if (normalizedName.isEmpty()) return null;
        List<String> want = words(normalizedName);
        if (want.isEmpty()) return null;

        String best = null;
        double bestScore = 0;
        boolean ambiguous = false;
        for (Map.Entry<String, String> entry : byName.entrySet()) {
            List<String> have = words(entry.getKey());
            if (have.isEmpty()) continue;
            double score = similarity(want, have);
            if (score > bestScore + 1e-9) {
                bestScore = score;
                best = entry.getValue();
                ambiguous = false;
            } else if (Math.abs(score - bestScore) < 1e-9 && best != null
                    && !best.equals(entry.getValue())) {
                ambiguous = true;
            }
        }
        if (best == null || bestScore < PROBABLE_THRESHOLD || ambiguous) return null;
        return best;
    }

    /** Words that carry meaning: the same quality filtering as {@link #normalize}. */
    private static List<String> words(String normalized) {
        List<String> out = new ArrayList<>();
        for (String token : normalized.split(" ")) {
            if (token.isEmpty() || isQualityToken(token) || token.length() < 2) continue;
            out.add(token);
        }
        return out;
    }

    /** Share of shared words over the larger of the two, so extra words cost accuracy. */
    static double similarity(List<String> left, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        int shared = 0;
        for (String token : left) {
            if (right.contains(token)) shared++;
        }
        return (double) shared / Math.max(left.size(), right.size());
    }
}
