// Host-side unit tests for the pure helpers in httpaceproxycpp/src/util.cpp.
//
// These cover the rules that protect the HTTP surface, so a regression is
// caught on the build host instead of on the TV:
//   * path_is_safe_relative() is the only thing stopping /stat/<path> from
//     serving files outside the bundled http/ directory.
//   * parse_url()/url_decode() decide which upstream host a channel actually
//     talks to and how a channel name is recovered from a request path.

#include "httpaceproxycpp/util.hpp"

#include <gtest/gtest.h>

#include <filesystem>
#include <regex>
#include <string>
#include <vector>

using namespace httpace;

// ---------------------------------------------------------------------------
// path_is_safe_relative: traversal containment for static file serving.
// ---------------------------------------------------------------------------

TEST(PathIsSafeRelativeTest, AcceptsPlainRelativePaths) {
    EXPECT_TRUE(path_is_safe_relative("index.html"));
    EXPECT_TRUE(path_is_safe_relative("css/style.css"));
    EXPECT_TRUE(path_is_safe_relative("a/b/c.js"));
    EXPECT_TRUE(path_is_safe_relative("statplugin/index.html"));
}

TEST(PathIsSafeRelativeTest, RejectsEmptyAndAbsolutePaths) {
    EXPECT_FALSE(path_is_safe_relative(""));
    EXPECT_FALSE(path_is_safe_relative("/"));
    EXPECT_FALSE(path_is_safe_relative("/etc/passwd"));
    EXPECT_FALSE(path_is_safe_relative("/index.html"));
}

TEST(PathIsSafeRelativeTest, RejectsTraversalSegments) {
    EXPECT_FALSE(path_is_safe_relative(".."));
    EXPECT_FALSE(path_is_safe_relative("../"));
    EXPECT_FALSE(path_is_safe_relative("../etc/passwd"));
    EXPECT_FALSE(path_is_safe_relative("a/../b"));
    EXPECT_FALSE(path_is_safe_relative("a/../../etc/passwd"));
    EXPECT_FALSE(path_is_safe_relative("css/../../secret"));
}

TEST(PathIsSafeRelativeTest, RejectsCurrentDirectorySegments) {
    EXPECT_FALSE(path_is_safe_relative("."));
    EXPECT_FALSE(path_is_safe_relative("./index.html"));
    EXPECT_FALSE(path_is_safe_relative("a/./b"));
}

// The real invariant: whatever a hostile request supplies, joining it to the
// web root must never resolve above that root. A component comparison is used
// rather than a string prefix check, because a legitimate single filename may
// itself begin with ".." (for example "..%2f..%2fetc" is one filename whose
// literal characters are not separators).
TEST(PathIsSafeRelativeTest, AcceptedPathsNeverEscapeTheWebRoot) {
    const std::filesystem::path root = "/data/app/http";
    const std::vector<std::string> hostile = {
        "index.html",
        "a/b.css",
        "../../../etc/passwd",
        "a/../../etc/passwd",
        "./../../x",
        "/etc/passwd",
        "",
        "..%2f..%2fetc",
        "....//....//etc",
        "a/.. /b",
        "..\\..\\windows",
    };
    for (const auto& candidate : hostile) {
        if (!path_is_safe_relative(candidate)) continue;
        const auto resolved = (root / candidate).lexically_normal();
        for (const auto& component : resolved) {
            EXPECT_NE(component.string(), "..")
                << "accepted path escaped the web root: " << candidate
                << " -> " << resolved.string();
        }
        EXPECT_EQ(resolved.string().rfind(root.string(), 0), 0u)
            << "accepted path resolved outside the web root: " << candidate
            << " -> " << resolved.string();
    }
}

// ---------------------------------------------------------------------------
// parse_url
// ---------------------------------------------------------------------------

TEST(ParseUrlTest, ParsesSchemeHostPortAndPath) {
    auto parsed = parse_url("http://127.0.0.1:8888/aio");
    EXPECT_EQ(parsed.scheme, "http");
    EXPECT_EQ(parsed.host, "127.0.0.1");
    EXPECT_EQ(parsed.port, "8888");
    EXPECT_EQ(parsed.path, "/aio");
    EXPECT_EQ(parsed.query, "");
    EXPECT_EQ(parsed.authority, "127.0.0.1:8888");
}

TEST(ParseUrlTest, DefaultsPathToSlashWhenAuthorityOnly) {
    auto parsed = parse_url("https://example.com");
    EXPECT_EQ(parsed.scheme, "https");
    EXPECT_EQ(parsed.host, "example.com");
    EXPECT_EQ(parsed.port, "");
    EXPECT_EQ(parsed.path, "/");
}

TEST(ParseUrlTest, SplitsQueryFromPath) {
    auto parsed = parse_url("https://example.com/x?y=1&z=2");
    EXPECT_EQ(parsed.path, "/x");
    EXPECT_EQ(parsed.query, "y=1&z=2");
}

TEST(ParseUrlTest, LowercasesSchemeOnly) {
    auto parsed = parse_url("HTTP://Example.COM/Path");
    EXPECT_EQ(parsed.scheme, "http");
    // Host case is preserved; document it rather than silently rely on it.
    EXPECT_EQ(parsed.host, "Example.COM");
}

TEST(ParseUrlTest, ParsesAceStreamSchemeAsHost) {
    auto parsed = parse_url("acestream://abcdef0123456789");
    EXPECT_EQ(parsed.scheme, "acestream");
    EXPECT_EQ(parsed.host, "abcdef0123456789");
}

TEST(ParseUrlTest, TreatsSchemelessInputAsPath) {
    auto parsed = parse_url("just/a/path");
    EXPECT_TRUE(parsed.scheme.empty());
    EXPECT_EQ(parsed.path, "just/a/path");
}

TEST(ParseUrlTest, HandlesEmptyString) {
    auto parsed = parse_url("");
    EXPECT_TRUE(parsed.scheme.empty());
    EXPECT_EQ(parsed.path, "/");
}

// ---------------------------------------------------------------------------
// url_decode / url_encode
// ---------------------------------------------------------------------------

TEST(UrlDecodeTest, DecodesPercentEscapesAndPlus) {
    EXPECT_EQ(url_decode("hello%20world"), "hello world");
    EXPECT_EQ(url_decode("a+b"), "a b");
    EXPECT_EQ(url_decode("%2Fslash"), "/slash");
    EXPECT_EQ(url_decode("plain"), "plain");
}

TEST(UrlEncodeTest, EncodesReservedCharacters) {
    EXPECT_EQ(url_encode("a b"), "a%20b");
    EXPECT_EQ(url_encode("a/b", ""), "a%2Fb");
    // '/' is in the safe set here, so it survives.
    EXPECT_EQ(url_encode("a/b", "/"), "a/b");
}

TEST(UrlEncodeDecodeTest, RoundTripsThroughQueryValues) {
    const std::vector<std::string> values = {
        "http://example.com/a b?c=d&e=f",
        "acestream://abc",
        "caf\u00e9",
        "100%",
        "a+b",
    };
    for (const auto& value : values) {
        EXPECT_EQ(url_decode(url_encode(value)), value) << "roundtrip failed for: " << value;
    }
}

// ---------------------------------------------------------------------------
// split / trim / lower / starts_with / ends_with
// ---------------------------------------------------------------------------

TEST(SplitTest, KeepEmptyRetainsEmptyFields) {
    auto parts = split("a,,b", ',', true);
    ASSERT_EQ(parts.size(), 3u);
    EXPECT_EQ(parts[0], "a");
    EXPECT_EQ(parts[1], "");
    EXPECT_EQ(parts[2], "b");
}

TEST(SplitTest, DropEmptySkipsEmptyFields) {
    auto parts = split("a,,b", ',', false);
    ASSERT_EQ(parts.size(), 2u);
    EXPECT_EQ(parts[0], "a");
    EXPECT_EQ(parts[1], "b");
}

TEST(SplitTest, HandlesNoDelimiterAndEmptyInput) {
    auto single = split("abc", ',', false);
    ASSERT_EQ(single.size(), 1u);
    EXPECT_EQ(single[0], "abc");

    auto empty = split("", ',', false);
    EXPECT_TRUE(empty.empty());
}

TEST(StringHelperTest, TrimLowerStartsEnds) {
    EXPECT_EQ(trim("  x  "), "x");
    EXPECT_EQ(trim("\t\r\nx\n"), "x");
    EXPECT_EQ(lower("AbC"), "abc");
    EXPECT_TRUE(starts_with("HTTP/1.1", "HTTP"));
    EXPECT_FALSE(starts_with("HTTP/1.1", "http"));
    EXPECT_TRUE(ends_with("/aio/epg.xml", ".xml"));
    EXPECT_FALSE(ends_with("short", "longer"));
}

// ---------------------------------------------------------------------------
// mime_type_for_path
// ---------------------------------------------------------------------------

TEST(MimeTypeTest, MapsBundledAssetExtensions) {
    EXPECT_EQ(mime_type_for_path("index.html"), "text/html; charset=utf-8");
    EXPECT_EQ(mime_type_for_path("css/style.css"), "text/css; charset=utf-8");
    EXPECT_EQ(mime_type_for_path("js/app.js"), "text/javascript; charset=utf-8");
    EXPECT_EQ(mime_type_for_path("data.json"), "application/json; charset=utf-8");
    EXPECT_EQ(mime_type_for_path("list.m3u8"), "audio/mpegurl; charset=utf-8");
    EXPECT_EQ(mime_type_for_path("channel.ts"), "video/MP2T");
    EXPECT_EQ(mime_type_for_path("logo.png"), "image/png");
    EXPECT_EQ(mime_type_for_path("logo.svg"), "image/svg+xml");
}

TEST(MimeTypeTest, FallsBackToOctetStream) {
    EXPECT_EQ(mime_type_for_path("unknown.xyz"), "application/octet-stream");
    EXPECT_EQ(mime_type_for_path("no-extension"), "application/octet-stream");
}

// ---------------------------------------------------------------------------
// query_get
// ---------------------------------------------------------------------------

TEST(QueryGetTest, ReturnsValueOrFallback) {
    EXPECT_EQ(query_get("action=check_peers&max_wait=8", "action"), "check_peers");
    EXPECT_EQ(query_get("action=check_peers", "max_wait", "15"), "15");
    EXPECT_EQ(query_get("", "x", "fallback"), "fallback");
}

// ---------------------------------------------------------------------------
// digests: fixed vectors, so a broken digest is caught immediately
// ---------------------------------------------------------------------------

TEST(DigestTest, Sha1AndMd5KnownVectors) {
    EXPECT_EQ(sha1_hex("abc"), "a9993e364706816aba3e25717850c26c9cd0d89d");
    EXPECT_EQ(sha1_hex(""), "da39a3ee5e6b4b0d3255bfef95601890afd80709");
    EXPECT_EQ(md5_hex("abc"), "900150983cd24fb0d6963f7d28e17f72");
    EXPECT_EQ(md5_hex(""), "d41d8cd98f00b204e9800998ecf8427e");
}

// ---------------------------------------------------------------------------
// format_duration
// ---------------------------------------------------------------------------

TEST(FormatDurationTest, FormatsHoursMinutesSeconds) {
    EXPECT_EQ(format_duration(std::chrono::seconds(0)), "00:00:00");
    EXPECT_EQ(format_duration(std::chrono::seconds(59)), "00:00:59");
    EXPECT_EQ(format_duration(std::chrono::seconds(61)), "00:01:01");
    EXPECT_EQ(format_duration(std::chrono::seconds(3661)), "01:01:01");
}

// ---------------------------------------------------------------------------
// replace_all / regex_escape
// ---------------------------------------------------------------------------

TEST(ReplaceAllTest, ReplacesEveryOccurrence) {
    EXPECT_EQ(replace_all("a.b.c", ".", "-"), "a-b-c");
    EXPECT_EQ(replace_all("aaa", "aa", "b"), "ba");
    EXPECT_EQ(replace_all("none", "x", "y"), "none");
}

TEST(RegexEscapeTest, EscapesMetacharacters) {
    const auto escaped = regex_escape("a.b*c");
    EXPECT_NE(escaped, "a.b*c");
    // The escaped form must match itself literally.
    EXPECT_EQ(std::regex_match("a.b*c", std::regex(escaped)), true);
    EXPECT_EQ(std::regex_match("axbxc", std::regex(escaped)), false);
}
