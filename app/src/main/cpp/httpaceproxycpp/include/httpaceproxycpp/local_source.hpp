#pragma once

#include <cstddef>
#include <string>

namespace httpaceproxy {

/**
 * Prefix that marks a user M3U source as a file on this device instead of a remote URL.
 *
 * Sources reach the native core as "name<TAB>url" lines, and the core fetches every one of them over
 * HTTP. A list the user imported from the device is not reachable that way, so it travels under this
 * prefix and is read from disk instead. The rest of the pipeline - parse, merge, EPG, picons - is
 * identical, which is why a local file behaves exactly like a remote one everywhere else.
 */
inline constexpr char kLocalSourceScheme[] = "localfile:";

/** Same ceiling as the Java-side validator, so both ends reject the same files. */
inline constexpr std::size_t kLocalSourceMaxBytes = 10u * 1024u * 1024u;

/** True when {@code url} names a file on this device rather than something to download. */
bool is_local_source(const std::string& url);

/** The absolute path of a {@code localfile:} source, or empty when it is not one. */
std::string local_source_path(const std::string& url);

/**
 * Reads a local source.
 *
 * @throws std::runtime_error when the file cannot be read, or when it is larger than
 *         {@code max_bytes}; the caller logs the failure against the source name and carries on with
 *         the remaining sources, the same way it already handles an unreachable URL.
 */
std::string read_local_source(const std::string& path, std::size_t max_bytes = kLocalSourceMaxBytes);

}  // namespace httpaceproxy
