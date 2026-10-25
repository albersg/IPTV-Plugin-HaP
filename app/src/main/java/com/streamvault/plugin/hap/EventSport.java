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

    private static final Map<Kind, String[]> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put(Kind.FUTBOL, new String[]{
                "futbol", "fútbol", "laliga", "liga", "champions", "copa", "uefa", "europa league",
                "mundial", "soccer", "premier", "serie a", "bundesliga", "ligue 1", "clasico", "derbi",
                "jornada"
        });
        KEYWORDS.put(Kind.BALONCESTO, new String[]{
                "baloncesto", "basket", "nba", "euroliga", "acb", "eurobasket", "wnba"
        });
        KEYWORDS.put(Kind.MOTOR, new String[]{
                "motogp", "moto2", "moto3", "formula 1", "formula e", "f1", "f2", "f3",
                "grand prix", "rally", "dakar", "nascar", "indycar"
        });
        KEYWORDS.put(Kind.TENIS, new String[]{
                "tenis", "tennis", "atp", "wta", "wimbledon", "roland garros", "us open", "australian open"
        });
        KEYWORDS.put(Kind.CICLISMO, new String[]{
                "ciclismo", "tour de francia", "vuelta", "giro", "uci"
        });
    }

    private EventSport() {
    }

    /**
     * The sport a title names, or null when the title says nothing recognisable. Callers must treat null
     * as "not classified", not as an error.
     */
    public static Kind classify(String title) {
        String normalized = squash(title);
        if (normalized.isEmpty()) return null;
        String haystack = " " + normalized + " ";

        // The earliest keyword wins, not the first kind in the table: "Euroliga · Jornada 12" says
        // euroliga at position one and jornada later, and it is basketball, not football.
        Kind best = null;
        int bestAt = Integer.MAX_VALUE;
        for (Map.Entry<Kind, String[]> entry : KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                int at = haystack.indexOf(" " + squash(keyword) + " ");
                if (at >= 0 && at < bestAt) {
                    bestAt = at;
                    best = entry.getKey();
                }
            }
        }
        if (best != null) return best;

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
