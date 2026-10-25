package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.io.IOException;
import java.net.MalformedURLException;

/**
 * Pins F9 in the Java half: no log line may carry a provider URL, a query string or a value that
 * came from a stored source.
 *
 * <p>Stored sources are provider URLs, and those routinely embed {@code user:password} or a token
 * in the query. The plugin service logs the operation it performs, so what it logs has to survive
 * a bug report: a scheme and a host are enough to know where a stream came from, and the rest is
 * the credential. The same rule applies to an HTTP response (its headers carry cookies) and to an
 * exception message, because {@link java.net.MalformedURLException} quotes the whole URL it failed
 * to parse.
 *
 * <p>The redaction is plain Java with no android.* imports, so it is pinned here instead of on a
 * device.
 */
public final class LogRedactionTest {

    // ------------------------------------------------------------------ provider URLs

    /** A stored source with a user, a password and a token never reaches a log line. */
    @Test
    public void keepsOnlySchemeAndHostOfAProviderUrl() {
        String logged = LogRedaction.url("http://user:secret@provider.example:8080/live/stream.ts?token=abc&user=42");
        assertEquals("http://provider.example", logged);
        assertFalse(logged.contains("secret"));
        assertFalse(logged.contains("token"));
        assertFalse(logged.contains("/live"));
    }

    /** A URL without credentials is redacted exactly the same way: the rule does not guess. */
    @Test
    public void dropsThePathAndQueryOfAUrlWithoutCredentials() {
        assertEquals("https://provider.example", LogRedaction.url("https://provider.example/api/list.m3u?key=1"));
    }

    /** An acestream id sits in the path, and a path is never logged. */
    @Test
    public void dropsAnAceStreamId() {
        String logged = LogRedaction.url("http://127.0.0.1:6878/ace/getstream?id=0123456789abcdef");
        assertEquals("http://127.0.0.1", logged);
        assertFalse(logged.contains("0123456789abcdef"));
    }

    /** An IPv6 host stays readable instead of being cut at its own colons. */
    @Test
    public void keepsAnIpv6Host() {
        assertEquals("http://[2001:db8::1]", LogRedaction.url("http://[2001:db8::1]:8080/live.ts"));
    }

    /** Nothing to redact is not an error: the log says so in words instead of crashing. */
    @Test
    public void reportsMissingAndUnparseableInput() {
        assertEquals("(none)", LogRedaction.url(null));
        assertEquals("(none)", LogRedaction.url("   "));
        assertEquals("(unparseable)", LogRedaction.url("provider.example/live.ts"));
    }

    // ------------------------------------------------------------------ HTTP responses

    /** Headers can carry a cookie or a token, so only the status code of a response is logged. */
    @Test
    public void keepsOnlyTheStatusCodeOfAResponse() {
        String logged = LogRedaction.httpStatus("HTTP/1.1 302 Found\r\nLocation: http://user:pw@h.example/x?t=1\r\nSet-Cookie: s=abc\r\n\r\n");
        assertEquals("302", logged);
        assertFalse(logged.contains("pw"));
        assertFalse(logged.contains("abc"));
    }

    /** A response that cannot be read is reported as such, without its content. */
    @Test
    public void reportsAnUnreadableResponse() {
        assertEquals("(unreadable)", LogRedaction.httpStatus("not a response at all"));
        assertEquals("(unreadable)", LogRedaction.httpStatus(null));
        assertEquals("(unreadable)", LogRedaction.httpStatus(""));
    }

    // ------------------------------------------------------------------ exceptions

    /** MalformedURLException quotes the URL it refused, so a URL in a message is dropped. */
    @Test
    public void neverLogsAnExceptionMessageThatQuotesAUrl() throws Exception {
        MalformedURLException error = new MalformedURLException("no protocol: /ace/getstream?id=0123456789abcdef");
        assertEquals("MalformedURLException", LogRedaction.error(error));
        IOException withCredentials = new IOException("failed for http://user:pw@h.example/x?t=1");
        assertEquals("IOException", LogRedaction.error(withCredentials));
    }

    /** A message that carries no URL is the useful part of a diagnostic, so it is kept. */
    @Test
    public void keepsTheMessageOfASafeException() {
        assertEquals("IOException: Connection reset", LogRedaction.error(new IOException("Connection reset")));
    }

    /** A throwable without a message is still identified by its type. */
    @Test
    public void reportsTheTypeWhenThereIsNoMessage() {
        assertEquals("IOException", LogRedaction.error(new IOException()));
        assertEquals("(none)", LogRedaction.error(null));
    }
}
