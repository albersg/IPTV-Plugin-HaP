package com.streamvault.plugin.hap;

import java.util.Objects;

/**
 * The row models of the six lists of the HaP screen.
 *
 * <p>Each renderer used to build its rows straight from the bridge objects it had at hand, which
 * made "did anything change" impossible to answer without touching the views. These are the same
 * rows as plain values: the strings a row shows, the flags that decide which of its controls are
 * reachable, and the colours it needs for the current state. Two rows that would look and behave
 * identically compare equal, which is what lets {@link InPlaceList} skip an unchanged update
 * entirely (S7).
 *
 * <p>Every field takes part in equality: a field that was left out would silently keep the old
 * value on screen. The id fields are not drawn, but they decide what a click does, so they are part
 * of the identity too.
 *
 * <p>Plain Java with no {@code android.*} import: the equality is pinned on the JVM by
 * {@code InPlaceUpdateTest}.
 */
final class HapRows {

    /** One endpoint row: the label pill, the URL and its copy button. */
    static final class Endpoint {
        final String label;
        final String url;

        Endpoint(String label, String url) {
            this.label = label;
            this.url = url;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Endpoint)) return false;
            Endpoint that = (Endpoint) other;
            return Objects.equals(label, that.label) && Objects.equals(url, that.url);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label, url);
        }

        @Override
        public String toString() {
            return "Endpoint{label=" + label + ", url=" + url + '}';
        }
    }

    /**
     * One M3U source row: the select checkbox, the label, the secondary line and the edit/delete
     * controls that only a removable source shows.
     */
    static final class Source {
        final String id;
        final String label;
        /** The stored value, which the edit form fills in; not drawn. */
        final String value;
        /** The line under the label: the URL, or the description of the custom source. */
        final String secondary;
        final boolean selected;
        final boolean removable;

        Source(String id, String label, String value, String secondary, boolean selected, boolean removable) {
            this.id = id;
            this.label = label;
            this.value = value;
            this.secondary = secondary;
            this.selected = selected;
            this.removable = removable;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Source)) return false;
            Source that = (Source) other;
            return selected == that.selected
                    && removable == that.removable
                    && Objects.equals(id, that.id)
                    && Objects.equals(label, that.label)
                    && Objects.equals(value, that.value)
                    && Objects.equals(secondary, that.secondary);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, label, value, secondary, selected, removable);
        }

        @Override
        public String toString() {
            return "Source{id=" + id + ", label=" + label + ", selected=" + selected
                    + ", removable=" + removable + '}';
        }
    }

    /** One custom channel row: the name, the AceStream id and its edit/delete controls. */
    static final class CustomChannel {
        final int index;
        final String name;
        final String contentId;

        CustomChannel(int index, String name, String contentId) {
            this.index = index;
            this.name = name;
            this.contentId = contentId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof CustomChannel)) return false;
            CustomChannel that = (CustomChannel) other;
            return index == that.index
                    && Objects.equals(name, that.name)
                    && Objects.equals(contentId, that.contentId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, name, contentId);
        }

        @Override
        public String toString() {
            return "CustomChannel{index=" + index + ", name=" + name + '}';
        }
    }

    /** One connected client row. The size is already formatted, so the model compares as text. */
    static final class Client {
        final String ip;
        final String device;
        final String channel;
        final String megabytes;

        Client(String ip, String device, String channel, String megabytes) {
            this.ip = ip;
            this.device = device;
            this.channel = channel;
            this.megabytes = megabytes;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Client)) return false;
            Client that = (Client) other;
            return Objects.equals(ip, that.ip)
                    && Objects.equals(device, that.device)
                    && Objects.equals(channel, that.channel)
                    && Objects.equals(megabytes, that.megabytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ip, device, channel, megabytes);
        }

        @Override
        public String toString() {
            return "Client{ip=" + ip + ", device=" + device + ", channel=" + channel
                    + ", megabytes=" + megabytes + '}';
        }
    }

    /** One plugin tab of the channel panel: its label, its count and whether it is the selected one. */
    static final class PluginTab {
        final String id;
        final String label;
        final int channelCount;
        final boolean selected;
        /** What a click selects as the current channel; null for a list without channels. */
        final String firstChannelId;

        PluginTab(String id, String label, int channelCount, boolean selected, String firstChannelId) {
            this.id = id;
            this.label = label;
            this.channelCount = channelCount;
            this.selected = selected;
            this.firstChannelId = firstChannelId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof PluginTab)) return false;
            PluginTab that = (PluginTab) other;
            return channelCount == that.channelCount
                    && selected == that.selected
                    && Objects.equals(id, that.id)
                    && Objects.equals(label, that.label)
                    && Objects.equals(firstChannelId, that.firstChannelId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, label, channelCount, selected, firstChannelId);
        }

        @Override
        public String toString() {
            return "PluginTab{id=" + id + ", label=" + label + ", channelCount=" + channelCount
                    + ", selected=" + selected + '}';
        }
    }

    /**
     * One channel test row: what the channel is, whether it is the selected one, and the result of
     * its last peer check. A {@code null} {@code detail} or {@code badge} means the channel has not
     * been tested yet, so those two views stay hidden.
     */
    static final class Channel {
        final String contentId;
        final String name;
        final boolean selected;
        final String detail;
        final int detailColor;
        final String badge;
        final int badgeBackground;
        final int badgeForeground;

        Channel(
                String contentId,
                String name,
                boolean selected,
                String detail,
                int detailColor,
                String badge,
                int badgeBackground,
                int badgeForeground
        ) {
            this.contentId = contentId;
            this.name = name;
            this.selected = selected;
            this.detail = detail;
            this.detailColor = detailColor;
            this.badge = badge;
            this.badgeBackground = badgeBackground;
            this.badgeForeground = badgeForeground;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Channel)) return false;
            Channel that = (Channel) other;
            return selected == that.selected
                    && detailColor == that.detailColor
                    && badgeBackground == that.badgeBackground
                    && badgeForeground == that.badgeForeground
                    && Objects.equals(contentId, that.contentId)
                    && Objects.equals(name, that.name)
                    && Objects.equals(detail, that.detail)
                    && Objects.equals(badge, that.badge);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    contentId,
                    name,
                    selected,
                    detail,
                    detailColor,
                    badge,
                    badgeBackground,
                    badgeForeground
            );
        }

        @Override
        public String toString() {
            return "Channel{contentId=" + contentId + ", name=" + name + ", selected=" + selected
                    + ", badge=" + badge + '}';
        }
    }

    private HapRows() {
    }
}
