package com.streamvault.plugin.hap;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Matches a channel name against what the user typed.
 *
 * <p>Pure on purpose: no Android types, so the rules are pinned by plain JUnit tests. Two decisions
 * matter for a channel list. Matching is case-insensitive and accent-insensitive, because a remote has
 * no good way to type "ñ" and a provider's name may carry one the user cannot reproduce. And an empty
 * query matches everything, so the field starts by showing the whole list.
 */
public final class ChannelSearch {

    private ChannelSearch() {
    }

    /** True when {@code name} contains {@code query}, ignoring case, accents and surrounding spaces. */
    public static boolean matches(String name, String query) {
        String needle = normalize(query);
        if (needle.isEmpty()) return true;
        if (name == null) return false;
        return normalize(name).contains(needle);
    }

    /**
     * The name folded for comparison: lower case, accents removed, undefined width collapsed.
     *
     * <p>Normalising to NFD splits "ó" into "o" plus a combining mark, which is then dropped. Names
     * that do not decompose (CJK, Arabic) pass through unchanged apart from the lower-casing.
     */
    public static String normalize(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            out.append(c);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
