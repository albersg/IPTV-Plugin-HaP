#pragma once

#include <string>

namespace httpace {

/**
 * What the proxy can honestly say about one upstream session, derived only from facts it has.
 *
 * <p>This is the first half of the PlaybackCoordinator in `docs/architecture/BASELINE.md`: it names
 * the state and exposes it, and it does not decide anything yet. "Playing" means payload is flowing
 * through the transport, never that a player is rendering it; the diagreement is in
 * `odd/tasks/hap-playback-coordinator.md`.
 */
enum class PlaybackState {
    /** The session is open and no payload has arrived yet; the engine has not said it is prebuffering. */
    Starting,
    /** The engine reports a prebuffer state and no payload has arrived. */
    Prebuffering,
    /** Payload is flowing. */
    Playing,
    /** Payload was flowing and stopped long enough for the transport probe to call it stalled. */
    Degraded,
    /** A retry is running or waiting, and this session has not delivered anything yet. */
    Recovering,
    /** The retries are exhausted; nobody is going to reopen this upstream automatically. */
    Failed,
};

/**
 * The facts the classification reads. Kept separate so the rule is a pure function with a test.
 */
struct PlaybackFacts {
    bool gave_up = false;
    bool retrying = false;
    bool engine_prebuffering = false;
    bool has_payload = false;
    bool stalled = false;
};

PlaybackState classify_playback_state(const PlaybackFacts& facts);

/** Stable lowercase name, part of the `/status` and `/stat` contract. */
const char* playback_state_name(PlaybackState state);

} // namespace httpace
