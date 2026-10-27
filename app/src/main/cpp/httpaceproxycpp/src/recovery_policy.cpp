#include "httpaceproxycpp/recovery_policy.hpp"

#include <algorithm>
#include <limits>

namespace httpace {

namespace {
constexpr std::uint64_t kJitterMultiplier = 2654435761ULL;

std::int64_t capped(std::int64_t value, std::int64_t cap) {
    if (cap > 0 && value > cap) return cap;
    return value;
}
} // namespace

std::int64_t retry_backoff_ms(const RecoveryPolicy& policy, int attempt, std::int64_t elapsed_ms) {
    if (policy.max_attempts <= 0 || attempt <= 0 || attempt > policy.max_attempts) return 0;
    if (policy.base_backoff_ms <= 0) return 0;
    if (policy.window_ms > 0 && elapsed_ms >= policy.window_ms) return 0;

    // Exponential growth, stopping as soon as the cap is reached: an attempt counter that has
    // somehow grown huge must not shift the base into overflow.
    std::int64_t backoff = policy.base_backoff_ms;
    for (int step = 1; step < attempt; step++) {
        if (policy.max_backoff_ms > 0 && backoff >= policy.max_backoff_ms) break;
        if (backoff > std::numeric_limits<std::int64_t>::max() / 2) {
            backoff = std::numeric_limits<std::int64_t>::max();
            break;
        }
        backoff *= 2;
    }

    // Deterministic jitter: derived from the attempt number, not from a random device, so the same
    // policy always answers the same wait and a test can pin it.
    const std::uint64_t jitter_range = static_cast<std::uint64_t>(policy.base_backoff_ms / 2) + 1;
    const auto jitter = static_cast<std::int64_t>(
            (static_cast<std::uint64_t>(attempt) * kJitterMultiplier) % jitter_range);
    const std::int64_t total =
            backoff > std::numeric_limits<std::int64_t>::max() - jitter
                    ? std::numeric_limits<std::int64_t>::max()
                    : backoff + jitter;
    return capped(total, policy.max_backoff_ms);
}

int upstream_read_timeout_seconds(const RecoveryPolicy& policy, int configured_seconds) {
    if (policy.max_attempts <= 0) return configured_seconds;
    // 0 is the documented "no timeout"; lowering it would change a different setting's meaning.
    if (configured_seconds <= 0) return configured_seconds;
    return std::min(configured_seconds, kRecoverySilentTimeoutSeconds);
}

} // namespace httpace
