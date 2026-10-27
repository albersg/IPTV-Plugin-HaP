#pragma once

#include <cstdint>
#include <map>
#include <string>

namespace httpace {

/**
 * How long an engine session may show no progress at all before the proxy ends it.
 *
 * <p>Measured on the box (odd/tasks/hap-stream-recovery.md): a healthy cold channel sends its first
 * byte in 0.98-1.73 s, while a channel the engine accepts and never feeds sits in
 * {@code main:prebuf;0;0;...} for its own ~30 s window and then ends the session itself. Waiting for
 * the engine costs 30 s even when a retry would have worked; waiting 20 s is more than ten times the
 * slowest healthy start measured and ten seconds earlier than the engine.
 *
 * <p>Only the recovery flag turns this on, and the action is not a new mechanism: the session is
 * ended, and the bounded retry that already exists decides whether to try again.
 */
constexpr int kSilentPrebufferSeconds = 20;

/** True when an engine status line shows that the session is doing anything at all. */
bool status_indicates_progress(const std::map<std::string, std::string>& status);

/** True when the engine sees at least one peer for this session. */
bool status_has_peers(const std::map<std::string, std::string>& status);

/**
 * True when a session has been prebuffering without progress for longer than allowed.
 *
 * <p>A session with peers is left to the engine: a swarm that is connected but slow can still
 * deliver, and retrying it every 20 s would restart its prebuffer and starve it. Only a session with
 * nowhere to get data from is ended early.
 *
 * @param silent_seconds   0 disables the watchdog
 * @param prebuffering     the engine's last state was a prebuffer state
 * @param has_peers        the engine reported at least one peer
 * @param now_ms           monotonic milliseconds
 * @param last_progress_ms when progress was last seen; 0 means "not measured yet" and never aborts
 */
bool should_end_silent_session(int silent_seconds, bool prebuffering, bool has_peers,
                               std::int64_t now_ms, std::int64_t last_progress_ms);

} // namespace httpace
