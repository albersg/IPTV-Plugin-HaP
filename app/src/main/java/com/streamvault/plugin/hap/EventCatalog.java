package com.streamvault.plugin.hap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is on right now, grouped by programme and crossed with the channels this app can play.
 *
 * <p>This is the bridge between a guide and a playlist: the guide says which of <em>its</em> channels is
 * showing a programme, and the matcher says which of <em>ours</em> correspond. An event lists the playlist
 * channels that can actually be played, never the guide's names, so tapping one always leads somewhere.
 *
 * <p>A channel with no guide entry contributes nothing here and is not affected: it stays in the channel
 * list exactly as before. That is the rule the whole feature rests on.
 *
 * <p>Pure on purpose: no Android types and no I/O, so the grouping and ordering are pinned by plain JUnit
 * tests.
 */
public final class EventCatalog {

    /** One programme that is on now, with the channels of this app that carry it. */
    public static final class Event {
        /** The programme title as the guide publishes it. */
        public final String title;
        /** Playlist channel ids, in the order the playlist lists them. */
        public final List<String> channelIds;

        Event(String title, List<String> channelIds) {
            this.title = title;
            this.channelIds = channelIds;
        }

        /** How many of our channels carry this event. */
        public int channelCount() {
            return channelIds.size();
        }
    }

    private EventCatalog() {
    }

    /**
     * Groups what is on at {@code nowMillis} by programme title.
     *
     * <p>Two channels showing the same title become one event with two channels. Titles are compared
     * normalised, so a guide that writes the same programme with different spacing or accents does not
     * produce two events. The first spelling seen is the one shown.
     *
     * <p>Events with more channels come first, because an event carried by one channel is usually filler
     * and an event carried by eight is usually the one worth watching.
     *
     * @param guide          the guide window
     * @param channelToGuide playlist channel id to guide channel id, as produced by the matcher
     * @param nowMillis      the instant to look at
     */
    public static List<Event> onNow(
            EpgWindow guide,
            Map<String, String> channelToGuide,
            long nowMillis
    ) {
        List<Event> out = new ArrayList<>();
        if (guide == null || channelToGuide == null || channelToGuide.isEmpty()) return out;

        Map<String, EpgWindow.Programme> byGuideChannel = guide.onNow(nowMillis);
        if (byGuideChannel.isEmpty()) return out;

        // Normalised title key, so the same programme spelled slightly differently stays one event.
        Map<String, String> titles = new LinkedHashMap<>();
        Map<String, List<String>> channels = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : channelToGuide.entrySet()) {
            String channelId = entry.getKey();
            String guideId = entry.getValue();
            if (channelId == null || guideId == null) continue;
            EpgWindow.Programme programme = byGuideChannel.get(guideId);
            if (programme == null) continue;
            String title = programme.title == null ? "" : programme.title.trim();
            if (title.isEmpty()) continue;
            String key = ChannelGuideMatcher.normalize(title);
            if (key.isEmpty()) continue;
            titles.putIfAbsent(key, title);
            List<String> list = channels.get(key);
            if (list == null) {
                list = new ArrayList<>();
                channels.put(key, list);
            }
            if (!list.contains(channelId)) list.add(channelId);
        }

        for (Map.Entry<String, List<String>> entry : channels.entrySet()) {
            out.add(new Event(titles.get(entry.getKey()), entry.getValue()));
        }
        out.sort((a, b) -> {
            int byChannels = Integer.compare(b.channelCount(), a.channelCount());
            if (byChannels != 0) return byChannels;
            return a.title.compareToIgnoreCase(b.title);
        });
        return out;
    }

    /**
     * The events a title query keeps, using the same accent- and case-insensitive rules as the channel
     * search, so "barcelona" finds "Jornada 10: Barcelona - Real Madrid".
     */
    public static List<Event> matching(List<Event> events, String query) {
        List<Event> out = new ArrayList<>();
        if (events == null) return out;
        for (Event event : events) {
            if (ChannelSearch.matches(event.title, query)) out.add(event);
        }
        return out;
    }
}
