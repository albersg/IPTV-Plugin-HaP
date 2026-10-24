package com.jopsis.httpaceserveproxy.update;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader used by the updater.
 *
 * <p>Deliberately hand-written instead of using {@code org.json}: the release and asset parsing
 * must stay in plain Java so it can be unit tested on the JVM, where Android's {@code org.json}
 * is only a stub. It supports the subset the GitHub Releases API returns: objects, arrays,
 * strings (with escapes), numbers, booleans and {@code null}.
 *
 * <p>Objects become {@link LinkedHashMap}, arrays {@link ArrayList}, strings {@link String},
 * integral numbers {@link Long}, other numbers {@link Double}, and literals {@link Boolean} or
 * {@code null}. Malformed input throws {@link IllegalArgumentException}.
 */
public final class Json {
    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("JSON text is null");
        Json parser = new Json(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.index < parser.text.length()) throw parser.error("Trailing content");
        return value;
    }

    /** Returns the value as a map, or an empty map when it is not an object. */
    public static Map<String, Object> asObject(Object value) {
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return map;
        }
        return new LinkedHashMap<>();
    }

    /** Returns the value as a list, or an empty list when it is not an array. */
    public static List<Object> asArray(Object value) {
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            return list;
        }
        return new ArrayList<>();
    }

    /** String member, or the empty string when missing or of another type. */
    public static String string(Map<String, Object> object, String key) {
        Object value = object == null ? null : object.get(key);
        if (value instanceof String) return (String) value;
        return "";
    }

    /** Integral member, or {@code fallback} when missing or of another type. */
    public static long number(Map<String, Object> object, String key, long fallback) {
        Object value = object == null ? null : object.get(key);
        if (value instanceof Number) return ((Number) value).longValue();
        return fallback;
    }

    /** Boolean member, or {@code false} when missing or of another type. */
    public static boolean bool(Map<String, Object> object, String key) {
        Object value = object == null ? null : object.get(key);
        return value instanceof Boolean && (Boolean) value;
    }

    private Object readValue() {
        skipWhitespace();
        if (index >= text.length()) throw error("Unexpected end of input");
        char next = text.charAt(index);
        switch (next) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> out = new LinkedHashMap<>();
        index++;
        skipWhitespace();
        if (peek() == '}') {
            index++;
            return out;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw error("Expected an object key");
            String key = readString();
            skipWhitespace();
            if (peek() != ':') throw error("Expected ':'");
            index++;
            out.put(key, readValue());
            skipWhitespace();
            char separator = peek();
            if (separator == ',') {
                index++;
                continue;
            }
            if (separator == '}') {
                index++;
                return out;
            }
            throw error("Expected ',' or '}'");
        }
    }

    private List<Object> readArray() {
        List<Object> out = new ArrayList<>();
        index++;
        skipWhitespace();
        if (peek() == ']') {
            index++;
            return out;
        }
        while (true) {
            out.add(readValue());
            skipWhitespace();
            char separator = peek();
            if (separator == ',') {
                index++;
                continue;
            }
            if (separator == ']') {
                index++;
                return out;
            }
            throw error("Expected ',' or ']'");
        }
    }

    private String readString() {
        index++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (index >= text.length()) throw error("Unterminated string");
            char current = text.charAt(index++);
            if (current == '"') return out.toString();
            if (current != '\\') {
                out.append(current);
                continue;
            }
            if (index >= text.length()) throw error("Unterminated escape");
            char escaped = text.charAt(index++);
            switch (escaped) {
                case '"':
                    out.append('"');
                    break;
                case '\\':
                    out.append('\\');
                    break;
                case '/':
                    out.append('/');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'u':
                    if (index + 4 > text.length()) throw error("Truncated unicode escape");
                    try {
                        out.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                    } catch (NumberFormatException error) {
                        throw error("Invalid unicode escape");
                    }
                    index += 4;
                    break;
                default:
                    throw error("Invalid escape");
            }
        }
    }

    private Object readNumber() {
        int start = index;
        boolean floating = false;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '-' || current == '+') {
                index++;
            } else if (current >= '0' && current <= '9') {
                index++;
            } else if (current == '.' || current == 'e' || current == 'E') {
                floating = true;
                index++;
            } else {
                break;
            }
        }
        String token = text.substring(start, index);
        if (token.isEmpty()) throw error("Invalid value");
        try {
            if (!floating) return Long.valueOf(Long.parseLong(token));
            return Double.valueOf(Double.parseDouble(token));
        } catch (NumberFormatException error) {
            throw error("Invalid number");
        }
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, index)) throw error("Expected " + literal);
        index += literal.length();
    }

    private char peek() {
        if (index >= text.length()) throw error("Unexpected end of input");
        return text.charAt(index);
    }

    private void skipWhitespace() {
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == ' ' || current == '\n' || current == '\r' || current == '\t') index++;
            else break;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at offset " + index);
    }
}
