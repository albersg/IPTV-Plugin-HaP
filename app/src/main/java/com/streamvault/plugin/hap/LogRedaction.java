package com.streamvault.plugin.hap;

/**
 * What a log line may say about a provider, an HTTP response and a failure (audit finding F9).
 *
 * <p>HaP stores provider URLs as sources, and those routinely embed {@code user:password} or a
 * token in the query. Logcat and a bug report are therefore the worst place to repeat one: the
 * plugin service logs the operation it performs, and when a reference is genuinely needed the
 * redacted form is a scheme and a host — never a path, never a query, never a value that came from
 * a source. An HTTP response is redacted the same way because its headers carry cookies, and an
 * exception message is redacted because {@link java.net.MalformedURLException} quotes the whole URL
 * it refused to parse.
 *
 * <p>Plain Java with no android.* imports, so every rule here is pinned by
 * {@code LogRedactionTest} on the JVM.
 */
final class LogRedaction {

    static final String NONE = "(none)";
    static final String UNPARSEABLE = "(unparseable)";
    static final String UNREADABLE = "(unreadable)";

    private LogRedaction() {
    }

    /**
     * The scheme and the host of a URL, and nothing else: the user info, the path and the query are
     * dropped, because each of them can carry a credential or a content id.
     */
    static String url(String url) {
        if (url == null) return NONE;
        String value = url.trim();
        if (value.isEmpty()) return NONE;

        int schemeEnd = value.indexOf("://");
        if (schemeEnd <= 0) return UNPARSEABLE;
        String scheme = value.substring(0, schemeEnd);
        if (!isScheme(scheme)) return UNPARSEABLE;

        String rest = value.substring(schemeEnd + 3);
        int authorityEnd = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char character = rest.charAt(i);
            if (character == '/' || character == '?' || character == '#') {
                authorityEnd = i;
                break;
            }
        }
        String authority = rest.substring(0, authorityEnd);
        // user:password@ is the credential this exists for.
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        authority = stripPort(authority);
        return authority.isEmpty() ? UNPARSEABLE : scheme + "://" + authority;
    }

    /** The status code of an HTTP response; its headers stay out of the log. */
    static String httpStatus(String headerBlock) {
        if (headerBlock == null) return UNREADABLE;
        String value = headerBlock.trim();
        if (value.isEmpty()) return UNREADABLE;
        int lineEnd = value.indexOf('\n');
        String statusLine = (lineEnd < 0 ? value : value.substring(0, lineEnd)).trim();
        String[] parts = statusLine.split("\\s+");
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) return UNREADABLE;
        String code = parts[1];
        if (code.isEmpty()) return UNREADABLE;
        for (int i = 0; i < code.length(); i++) {
            if (!Character.isDigit(code.charAt(i))) return UNREADABLE;
        }
        return code;
    }

    /**
     * A failure, identified by type. Its message is kept only when it cannot quote a URL, a query
     * or an address, because a connection error is useful and a URL is not.
     */
    static String error(Throwable error) {
        if (error == null) return NONE;
        String name = error.getClass().getSimpleName();
        if (name.isEmpty()) name = error.getClass().getName();
        String message = error.getMessage();
        if (message == null) return name;
        String value = message.trim();
        if (value.isEmpty()) return name;
        if (value.contains("://") || value.contains("?") || value.contains("@")) return name;
        return name + ": " + value;
    }

    private static boolean isScheme(String scheme) {
        if (scheme.isEmpty()) return false;
        for (int i = 0; i < scheme.length(); i++) {
            char character = scheme.charAt(i);
            boolean alpha = (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
            boolean digit = character >= '0' && character <= '9';
            if (i == 0 && !alpha) return false;
            if (!alpha && !digit && character != '+' && character != '-' && character != '.') return false;
        }
        return true;
    }

    /** Drops {@code :port}, keeping a bracketed IPv6 literal intact. */
    private static String stripPort(String authority) {
        if (authority.startsWith("[")) {
            int bracket = authority.indexOf(']');
            return bracket < 0 ? authority : authority.substring(0, bracket + 1);
        }
        int colon = authority.lastIndexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }
}
