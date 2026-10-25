#pragma once

#include <chrono>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace httpace {

std::string trim(std::string_view value);
std::string lower(std::string_view value);
bool starts_with(std::string_view value, std::string_view prefix);
bool ends_with(std::string_view value, std::string_view suffix);
std::vector<std::string> split(std::string_view value, char delimiter, bool keep_empty = true);
std::string join(const std::vector<std::string>& values, std::string_view delimiter);
std::string url_decode(std::string_view value);
std::string url_encode(std::string_view value, std::string_view safe = "");
std::map<std::string, std::string> parse_query(std::string_view query);
std::string query_get(std::string_view query, std::string_view key, std::string_view fallback = "");
std::string shell_quote_for_log(std::string_view value);

// Redacts the part of an HTTP request target that can carry data: the path beyond its first
// segment (a provider URL lives there, percent-encoded) and the whole query string. Keeps the
// first segment so a log still says which endpoint was called.
std::string redact_request_target(std::string_view target);

// True when a value carries CR, LF or another C0 control byte (tab excluded: it is legal inside a
// channel name). Such a value would be read as a second line by the AceStream control protocol.
bool has_control_characters(std::string_view value);

// The address to attribute a request to. A forwarded header is respected only when the request
// really came from a local reverse proxy, because otherwise the client writes it itself.
std::string effective_client_address(std::string_view peer_address, std::string_view forwarded);

// Keeps scheme, host and port of a URL and drops the rest, which is where tokens and credentials
// travel. Returns "<redacted>" when there is no scheme to keep.
std::string redact_url(std::string_view url);
std::string basename_no_ext(std::string_view path);
std::string extension_of(std::string_view path);
std::string mime_type_for_path(std::string_view path);
std::string md5_hex(std::string_view value);
std::string sha1_hex(std::string_view value);
std::string http_date_now();
std::int64_t unix_time();
std::string format_duration(std::chrono::seconds seconds);
std::string read_file_binary(const std::string& path);
bool path_is_safe_relative(std::string_view path);
std::string replace_all(std::string value, std::string_view needle, std::string_view replacement);
std::string regex_escape(std::string_view value);

struct ParsedUrl {
    std::string scheme;
    std::string host;
    std::string port;
    std::string path;
    std::string query;
    std::string authority;
};

ParsedUrl parse_url(std::string_view url);
std::string build_url(const ParsedUrl& parsed);
std::string rewrite_url_host_port(std::string_view url, std::string_view host, std::string_view port);

void log_line(const std::string& level, const std::string& message);

} // namespace httpace
