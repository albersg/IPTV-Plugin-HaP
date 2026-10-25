package com.streamvault.plugin.hap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
        /** When it started, in milliseconds; 0 when the guide did not say. */
        public final long startMillis;
        /** When it ends, in milliseconds; 0 when the guide did not say. */
        public final long stopMillis;

        Event(String title, List<String> channelIds, long startMillis, long stopMillis) {
            this.title = title;
            this.channelIds = channelIds;
            this.startMillis = startMillis;
            this.stopMillis = stopMillis;
        }

        /** The sport the title names, or null when it does not say. */
        public EventSport.Kind sport() {
            return EventSport.classify(title);
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

        // Some guides fill the schedule with the channel's own name as the programme title. That is not
        // an event: it is the channel wearing a title, and listing it produces nonsense such as "M+ Liga
        // de Campeones 3, emitted by M+ Liga de Campeones 3 720p".
        Set<String> channelNames = new LinkedHashSet<>();
        for (ChannelGuideMatcher.GuideChannel channel : guide.channels()) {
            channelNames.add(ChannelGuideMatcher.normalize(channel.id));
            for (String name : channel.names) channelNames.add(ChannelGuideMatcher.normalize(name));
        }
        channelNames.remove("");

        // Normalised title key, so the same programme spelled slightly differently stays one event.
        Map<String, String> titles = new LinkedHashMap<>();
        Map<String, List<String>> channels = new LinkedHashMap<>();
        Map<String, Long> starts = new LinkedHashMap<>();
        Map<String, Long> stops = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : channelToGuide.entrySet()) {
            String channelId = entry.getKey();
            String guideId = entry.getValue();
            if (channelId == null || guideId == null) continue;
            EpgWindow.Programme programme = byGuideChannel.get(guideId);
            if (programme == null) continue;
            String title = programme.title == null ? "" : programme.title.trim();
            if (title.isEmpty()) continue;
            if (channelNames.contains(ChannelGuideMatcher.normalize(title))) continue;
            String key = ChannelGuideMatcher.normalize(title);
            if (key.isEmpty()) continue;
            titles.putIfAbsent(key, title);
            List<String> list = channels.get(key);
            if (list == null) {
                list = new ArrayList<>();
                channels.put(key, list);
            }
            if (!list.contains(channelId)) list.add(channelId);
            Long known = starts.get(key);
            if (known == null || programme.startMillis < known) starts.put(key, programme.startMillis);
            Long ends = stops.get(key);
            if (ends == null || programme.stopMillis > ends) stops.put(key, programme.stopMillis);
        }

        for (Map.Entry<String, List<String>> entry : channels.entrySet()) {
            String key = entry.getKey();
            out.add(new Event(titles.get(key), entry.getValue(),
                    starts.get(key) == null ? 0 : starts.get(key),
                    stops.get(key) == null ? 0 : stops.get(key)));
        }
        // Ordered by when it started, which is how a guide reads; more channels first on a tie, because
        // the event carried by eight channels is the one worth watching.
        out.sort((a, b) -> {
            int byStart = Long.compare(a.startMillis, b.startMillis);
            if (byStart != 0) return byStart;
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
        return matching(events, query, null);
    }

    /**
     * The events a title query and a sport filter keep.
     *
     * @param sport the sport to keep, or null for all of them. An event whose title names no sport is
     *              only kept when no filter is set, because it cannot be shown under one.
     */
    public static List<Event> matching(List<Event> events, String query, EventSport.Kind sport) {
        List<Event> out = new ArrayList<>();
        if (events == null) return out;
        for (Event event : events) {
            if (!ChannelSearch.matches(event.title, query)) continue;
            if (sport != null && event.sport() != sport) continue;
            out.add(event);
        }
        return out;
    }

    /** The sports present in a list of events, in the order the filter offers them. */
    public static List<EventSport.Kind> sports(List<Event> events) {
        List<EventSport.Kind> out = new ArrayList<>();
        if (events == null) return out;
        for (EventSport.Kind kind : EventSport.Kind.values()) {
            for (Event event : events) {
                if (event.sport() == kind) {
                    out.add(kind);
                    break;
                }
            }
        }
        return out;
    }
}
