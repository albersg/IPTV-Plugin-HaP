package com.streamvault.plugin.hap;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * The slice of an XMLTV guide worth keeping: the channels, and the programmes that are on now or start
 * soon.
 *
 * <p>A guide is tens of megabytes - the one this app points at is 34.7 MB and 83,073 programmes - and the
 * device has 2 GB of RAM. Parsing it with a tree parser would end the app. This runs SAX over the stream
 * and drops every programme outside the window as it goes, so what stays is a few hundred entries no
 * matter how big the file is.
 *
 * <p>It also refuses to read more than {@link #MAX_BYTES}: a guide that suddenly doubles in size must not
 * become a way to exhaust the device.
 */
public final class EpgWindow {

    /** Hard ceiling on the bytes read from one guide. */
    public static final long MAX_BYTES = 96L * 1024 * 1024;

    /** A programme of the guide. */
    public static final class Programme {
        public final String channelId;
        public final String title;
        public final long startMillis;
        public final long stopMillis;

        Programme(String channelId, String title, long startMillis, long stopMillis) {
            this.channelId = channelId;
            this.title = title;
            this.startMillis = startMillis;
            this.stopMillis = stopMillis;
        }

        /** True when the programme is on at {@code nowMillis}. */
        public boolean isOnAt(long nowMillis) {
            return startMillis <= nowMillis && nowMillis < stopMillis;
        }
    }

    private final List<ChannelGuideMatcher.GuideChannel> channels;
    private final List<Programme> programmes;

    private EpgWindow(List<ChannelGuideMatcher.GuideChannel> channels, List<Programme> programmes) {
        this.channels = channels;
        this.programmes = programmes;
    }

    /** The channels the guide declares. */
    public List<ChannelGuideMatcher.GuideChannel> channels() {
        return channels;
    }

    /** Every programme kept, in the order the guide listed them. */
    public List<Programme> programmes() {
        return programmes;
    }

    /** The programmes on at {@code nowMillis}, by channel. */
    public Map<String, Programme> onNow(long nowMillis) {
        Map<String, Programme> out = new LinkedHashMap<>();
        for (Programme programme : programmes) {
            if (programme.isOnAt(nowMillis)) out.put(programme.channelId, programme);
        }
        return out;
    }

    /**
     * Reads a guide, keeping only what is on around {@code nowMillis}.
     *
     * @param input       the guide, typically gzipped content already opened by the caller
     * @param nowMillis   the instant the window is centred on
     * @param behindMillis how far back a programme may have started and still be kept
     * @param aheadMillis  how far forward a programme may start and still be kept
     */
    public static EpgWindow parse(InputStream input, long nowMillis, long behindMillis, long aheadMillis)
            throws IOException {
        if (input == null) throw new IOException("guide stream is null");
        Handler handler = new Handler(nowMillis - Math.max(0, behindMillis),
                nowMillis + Math.max(0, aheadMillis));
        try (InputStream bounded = new BoundedStream(input, MAX_BYTES)) {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // An older parser: the size cap and the null external-entity resolver still apply.
            }
            factory.setNamespaceAware(false);
            SAXParser parser = factory.newSAXParser();
            parser.getXMLReader().setEntityResolver((publicId, systemId) -> new InputSource(
                    new java.io.StringReader("")));
            parser.parse(new InputSource(bounded), handler);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("guide is not valid XMLTV: " + e.getMessage(), e);
        }
        return new EpgWindow(handler.channels, handler.programmes);
    }

    /** Parses the XMLTV timestamp form {@code 20261025180000 +0000}. */
    static long parseTimestamp(String value) {
        if (value == null) return 0;
        String text = value.trim();
        if (text.length() < 14) return 0;
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date parsed = format.parse(text.substring(0, 14), new ParsePosition(0));
        if (parsed == null) return 0;
        long millis = parsed.getTime();
        // A trailing offset means the stamp is local time, not UTC.
        if (text.length() >= 20) {
            String offset = text.substring(text.length() - 5);
            if (offset.charAt(0) == '+' || offset.charAt(0) == '-') {
                try {
                    int hours = Integer.parseInt(offset.substring(1, 3));
                    int minutes = Integer.parseInt(offset.substring(3, 5));
                    long delta = (hours * 60L + minutes) * 60_000L;
                    millis += offset.charAt(0) == '+' ? -delta : delta;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return millis;
    }

    private static final class Handler extends DefaultHandler {

        private final long fromMillis;
        private final long toMillis;
        private final List<ChannelGuideMatcher.GuideChannel> channels = new ArrayList<>();
        private final List<Programme> programmes = new ArrayList<>();

        private String channelId;
        private List<String> channelNames;
        private String programmeChannel;
        private long programmeStart;
        private long programmeStop;
        private String programmeTitle;
        private final StringBuilder text = new StringBuilder();

        Handler(long fromMillis, long toMillis) {
            this.fromMillis = fromMillis;
            this.toMillis = toMillis;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            switch (qName) {
                case "channel":
                    channelId = attributes.getValue("id");
                    channelNames = new ArrayList<>();
                    break;
                case "display-name":
                    text.setLength(0);
                    break;
                case "programme":
                    programmeChannel = attributes.getValue("channel");
                    programmeStart = parseTimestamp(attributes.getValue("start"));
                    programmeStop = parseTimestamp(attributes.getValue("stop"));
                    programmeTitle = null;
                    break;
                case "title":
                    text.setLength(0);
                    break;
                default:
                    break;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            switch (qName) {
                case "display-name":
                    if (channelNames != null) {
                        String name = text.toString().trim();
                        if (!name.isEmpty()) channelNames.add(name);
                    }
                    break;
                case "channel":
                    if (channelId != null && !channelId.trim().isEmpty()) {
                        channels.add(new ChannelGuideMatcher.GuideChannel(channelId.trim(), channelNames));
                    }
                    channelId = null;
                    channelNames = null;
                    break;
                case "title":
                    // The first title of a programme is its name; later ones are episode titles.
                    if (programmeTitle == null) programmeTitle = text.toString().trim();
                    break;
                case "programme":
                    // Outside the window it is dropped here, which is what keeps a 34 MB guide small.
                    if (programmeChannel != null && programmeTitle != null && programmeStop > 0
                            && programmeStop >= fromMillis && programmeStart <= toMillis) {
                        programmes.add(new Programme(programmeChannel.trim(), programmeTitle,
                                programmeStart, programmeStop));
                    }
                    programmeChannel = null;
                    programmeTitle = null;
                    break;
                default:
                    break;
            }
        }
    }

    /** Cuts the stream off past a byte budget, so a hostile or broken guide cannot exhaust the device. */
    private static final class BoundedStream extends FilterInputStream {

        private final long limit;
        private long read;

        BoundedStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++read > limit) throw new IOException("guide exceeds " + limit + " bytes");
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int got = super.read(buffer, offset, length);
            if (got > 0) {
                read += got;
                if (read > limit) throw new IOException("guide exceeds " + limit + " bytes");
            }
            return got;
        }
    }
}
