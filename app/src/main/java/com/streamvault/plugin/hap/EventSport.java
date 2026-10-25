package com.streamvault.plugin.hap;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Guesses the sport of a programme from its title.
 *
 * <p>The guide this app reads carries no genre at all - measured: zero {@code <genre>} elements in a file
 * with 29,170 programmes - so there is no field to filter on and the title is the only signal. That makes
 * this a heuristic, and it is treated as one: a title that matches nothing is simply "sin clasificar" and
 * still appears in the full list, never dropped.
 *
 * <p>Keywords are matched accent- and case-insensitively, on word boundaries where a short word could
 * otherwise match inside another ("nba" is not inside a channel name by accident).
 */
public final class EventSport {

    /** The filters the events screen offers, in the order it offers them. */
    public enum Kind {
        FUTBOL("Fútbol"),
        BALONCESTO("Baloncesto"),
        MOTOR("Motor"),
        TENIS("Tenis"),
        CICLISMO("Ciclismo"),
        OTROS("Otros");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** What the filter shows. The label lives here so the filter and the classifier cannot drift. */
        public String label() {
            return label;
        }
    }

    /** Sports the filter does not break out: they belong under "Otros", not under a wrong one. */
    private static final String[] OTHER_SPORTS = {
            "rugby", "balonmano", "hockey", "golf", "atletismo", "boxeo", "premier padel", "padel",
            "cricket", "béisbol",
            "beisbol", "voleibol", "voley", "waterpolo"
    };

    private static final Map<Kind, String[]> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put(Kind.FUTBOL, new String[]{
                "futbol", "fútbol", "laliga", "liga", "champions", "copa", "uefa", "europa league",
                "soccer", "premier", "serie a", "bundesliga", "ligue 1", "clasico", "derbi", "nfl"
        });
        KEYWORDS.put(Kind.BALONCESTO, new String[]{
                "baloncesto", "basket", "nba", "euroliga", "acb", "eurobasket", "wnba", "liga endesa"
        });
        KEYWORDS.put(Kind.MOTOR, new String[]{
                "motogp", "moto2", "moto3", "formula 1", "formula e", "f1", "f2", "f3",
                "grand prix", "gran premio", "rally", "dakar", "nascar", "indycar", "gp"
        });
        KEYWORDS.put(Kind.TENIS, new String[]{
                "tenis", "tennis", "atp", "wta", "wimbledon", "roland garros", "us open", "australian open"
        });
        KEYWORDS.put(Kind.CICLISMO, new String[]{
                "ciclismo", "tour de francia", "vuelta", "giro", "uci"
        });
    }

    /**
     * Words that name a broadcast rather than a sport. "Jornada" appears in football, basketball and
     * handball alike, so it cannot win over a sport that is actually named - but it is better than
     * nothing when nothing else in the title is.
     *
     * <p>This exists because the screen showed the failure: "Jornada 5 - Liga Endesa" came out as football
     * (jornada came first) and "Mundial F1 - GP de Estados Unidos" too (mundial came first).
     */
    private static final Map<String, Kind> GENERIC = new LinkedHashMap<>();

    static {
        GENERIC.put("jornada", Kind.FUTBOL);
    }

    private EventSport() {
    }

    static {
        KEYWORDS.put(Kind.OTROS, OTHER_SPORTS);
    }

    /**
     * The sport a title names, or null when the title says nothing recognisable. Callers must treat null
     * as "not classified", not as an error.
     */
    public static Kind classify(String title) {
        String normalized = squash(title);
        if (normalized.isEmpty()) return null;
        String haystack = " " + normalized + " ";

        // The most specific keyword wins, and position only breaks ties. Two corrections came from
        // watching the screen: "Euroliga - Jornada 12" was reading as football because a table order put
        // football first, and "Liga Endesa" was too, because the shorter "liga" matched earlier in the
        // string than the longer "liga endesa".
        Kind best = null;
        int bestLength = -1;
        int bestAt = Integer.MAX_VALUE;
        for (Map.Entry<Kind, String[]> entry : KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                String needle = squash(keyword);
                int at = haystack.indexOf(" " + needle + " ");
                if (at < 0) continue;
                int length = needle.length();
                if (length > bestLength || (length == bestLength && at < bestAt)) {
                    bestLength = length;
                    bestAt = at;
                    best = entry.getKey();
                }
            }
        }
        if (best != null) return best;

        // Nothing specific named: a generic word may still say what it is.
        for (Map.Entry<String, Kind> entry : GENERIC.entrySet()) {
            if (haystack.contains(" " + squash(entry.getKey()) + " ")) return entry.getValue();
        }

        // Two named sides and no competition: a sporting event, but nothing says which sport.
        if (haystack.contains(" vs ") || haystack.contains(" contra ")) return Kind.OTROS;
        return null;
    }

    /**
     * Lowercase, accent-free and punctuation-free, with single spaces between words. Punctuation has to
     * go so "NBA: Lakers" and "NBA Lakers" classify the same.
     */
    static String squash(String value) {
        if (value == null) return "";
        String normalized = ChannelSearch.normalize(value);
        StringBuilder out = new StringBuilder(normalized.length());
        boolean previousWasSpace = true;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
                previousWasSpace = false;
            } else if (!previousWasSpace) {
                out.append(' ');
                previousWasSpace = true;
            }
        }
        return out.toString().trim();
    }
}
