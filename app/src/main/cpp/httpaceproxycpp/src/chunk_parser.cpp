#include "httpaceproxycpp/chunk_parser.hpp"

#include "httpaceproxycpp/util.hpp"

#include <cerrno>
#include <cstdlib>

namespace httpace {

bool parse_chunk_size(const std::string& line, std::size_t& size) {
    auto clean = trim(line);
    auto semicolon = clean.find(';');
    if (semicolon != std::string::npos) clean = clean.substr(0, semicolon);
    if (clean.empty()) return false;

    // strtoull saturates at ULLONG_MAX and sets ERANGE, and any size close to SIZE_MAX
    // makes the caller's `chunk_size + 2` wrap to 1: that skips the fill loop and hands a
    // huge length to on_chunk. Rejecting here is the only place the value is trusted.
    errno = 0;
    char* end = nullptr;
    auto value = std::strtoull(clean.c_str(), &end, 16);
    if (!end || *end != '\0') return false;
    if (errno == ERANGE || value > kMaxChunkBytes) return false;
    size = static_cast<std::size_t>(value);
    return true;
}

} // namespace httpace
