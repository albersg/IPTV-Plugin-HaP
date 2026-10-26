#pragma once

#include <cstdint>

namespace httpace {

/**
 * How many times one upstream may be reopened and how long to wait before each attempt.
 *
 * <p>Scope (odd/tasks/hap-stream-recovery.md): this is the bounded retry of the <b>same</b> source
 * after its reader ended while clients were still waiting. It is not source failover, engine
 * switching or a scheduler; those need evidence this one does not make things worse, and a player
 * may still need to reopen the stream because a new session restarts PIDs and continuity.
 *
 * <p>{@code max_attempts == 0} disables recovery completely, which is the shipped default until a
 * comparative run exists. The backoff is exponential with a bounded, deterministic jitter so two
 * retries never line up and a test can assert the exact wait.
 */
struct RecoveryPolicy {
    /** Retries allowed after the first reader end; 0 disables recovery. */
    int max_attempts = 0;
    /** Wait before the first retry, in milliseconds. */
    std::int64_t base_backoff_ms = 500;
    /** Upper bound for any wait; 0 means "no explicit cap". */
    std::int64_t max_backoff_ms = 8000;
};

/**
 * The wait before retry number {@code attempt} (1-based).
 *
 * @return milliseconds to wait, or 0 when no retry is allowed
 */
std::int64_t retry_backoff_ms(const RecoveryPolicy& policy, int attempt);

/**
 * Read timeout for an upstream session, in seconds.
 *
 * <p>Measured on the box (odd/tasks/hap-stream-recovery.md): a healthy cold channel sends its first
 * byte in about 1.4 s, but a channel the engine accepts and never feeds stays silent for the whole
 * configured timeout - 30 s by default. With recovery enabled a silent session ends at
 * {@code kRecoverySilentTimeoutSeconds} so the retry, which may succeed, is not delayed by a session
 * that is producing nothing. The configured value is never raised, and 0 keeps its "no timeout"
 * meaning.
 */
constexpr int kRecoverySilentTimeoutSeconds = 15;

int upstream_read_timeout_seconds(const RecoveryPolicy& policy, int configured_seconds);

} // namespace httpace
