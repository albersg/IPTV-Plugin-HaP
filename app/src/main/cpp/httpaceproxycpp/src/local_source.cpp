#include "httpaceproxycpp/local_source.hpp"

#include <algorithm>
#include <cctype>
#include <fstream>
#include <iterator>
#include <stdexcept>

namespace httpaceproxy {
namespace {

constexpr std::size_t kSchemeLength = sizeof(kLocalSourceScheme) - 1;

bool has_local_prefix(const std::string& url) {
    if (url.size() < kSchemeLength) return false;
    for (std::size_t i = 0; i < kSchemeLength; ++i) {
        unsigned char a = static_cast<unsigned char>(url[i]);
        unsigned char b = static_cast<unsigned char>(kLocalSourceScheme[i]);
        if (std::tolower(a) != std::tolower(b)) return false;
    }
    return true;
}

}  // namespace

bool is_local_source(const std::string& url) {
    return has_local_prefix(url);
}

std::string local_source_path(const std::string& url) {
    if (!has_local_prefix(url)) return std::string();
    return url.substr(kSchemeLength);
}

std::string read_local_source(const std::string& path, std::size_t max_bytes) {
    if (path.empty()) throw std::runtime_error("local source has an empty path");

    std::ifstream file(path, std::ios::binary);
    if (!file) throw std::runtime_error("cannot open local source: " + path);

    std::string body;
    // One byte past the cap is enough to know the file does not fit, and keeps a large file from
    // filling memory before the check.
    body.reserve(std::min<std::size_t>(max_bytes, 1u << 20));
    char buffer[8192];
    while (file) {
        file.read(buffer, sizeof(buffer));
        std::streamsize got = file.gcount();
        if (got <= 0) break;
        if (body.size() + static_cast<std::size_t>(got) > max_bytes) {
            throw std::runtime_error("local source is larger than the limit: " + path);
        }
        body.append(buffer, static_cast<std::size_t>(got));
    }
    if (file.bad()) throw std::runtime_error("cannot read local source: " + path);
    return body;
}

}  // namespace httpaceproxy
