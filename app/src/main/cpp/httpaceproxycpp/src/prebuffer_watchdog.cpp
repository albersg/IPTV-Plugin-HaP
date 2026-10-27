#include "httpaceproxycpp/prebuffer_watchdog.hpp"

#include <cstdlib>

namespace httpace {

namespace {

bool starts_with(const std::string& value, const std::string& prefix) {
    return value.size() >= prefix.size() && value.compare(0, prefix.size(), prefix) == 0;
}

std::string field(const std::map<std::string, std::string>& status, const std::string& key) {
    auto it = status.find(key);
    return it == status.end() ? std::string() : it->second;
}

/** True when a numeric field is present and greater than zero. */
bool positive(const std::map<std::string, std::string>& status, const std::string& key) {
    const std::string raw = field(status, key);
    if (raw.empty()) return false;
    char* end = nullptr;
    const long long value = std::strtoll(raw.c_str(), &end, 10);
    return end != raw.c_str() && value > 0;
}

bool prebuffer_state(const std::string& state) {
    // The engine reports the prebuffer states with a main: prefix before parsing; both forms are
    // accepted so the function is true for either the raw or the parsed value.
    return state == "prebuf" || state == "main:prebuf" || state == "wait" || state == "main:wait" ||
           state == "seekprebuf" || state == "main:seekprebuf";
}

} // namespace

bool status_indicates_progress(const std::map<std::string, std::string>& status) {
    const std::string state = field(status, "status");
    // Anything that is not a prebuffer or wait state means the session moved on.
    if (!state.empty() && !prebuffer_state(state)) return true;
    // Peers and peers-only traffic are not progress: the engine can see a swarm and still receive
    // nothing. Only bytes and the counters that imply them count.
    return positive(status, "total_progress") || positive(status, "immediate_progress") ||
           positive(status, "downloaded") || positive(status, "http_downloaded") ||
           positive(status, "speed_down") || positive(status, "http_speed_down");
}

bool status_has_peers(const std::map<std::string, std::string>& status) {
    return positive(status, "peers") || positive(status, "http_peers");
}

bool should_end_silent_session(int silent_seconds, bool prebuffering, bool has_peers,
                               std::int64_t now_ms, std::int64_t last_progress_ms) {
    if (silent_seconds <= 0) return false;
    if (!prebuffering) return false;
    if (has_peers) return false;
    if (last_progress_ms <= 0) return false;
    if (now_ms < last_progress_ms) return false;
    return now_ms - last_progress_ms >= static_cast<std::int64_t>(silent_seconds) * 1000;
}

} // namespace httpace
