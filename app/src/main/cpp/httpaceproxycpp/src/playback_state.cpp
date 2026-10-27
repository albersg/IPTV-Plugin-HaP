#include "httpaceproxycpp/playback_state.hpp"

namespace httpace {

PlaybackState classify_playback_state(const PlaybackFacts& facts) {
    // Giving up wins over everything: nothing is going to reopen this upstream by itself.
    if (facts.gave_up) return PlaybackState::Failed;
    // A retry that has not delivered yet is recovering whatever the engine says: it can be starting,
    // prebuffering or waiting for the backoff, and none of that is "the stream is fine".
    if (facts.retrying && !facts.has_payload) return PlaybackState::Recovering;
    if (!facts.has_payload) {
        return facts.engine_prebuffering ? PlaybackState::Prebuffering : PlaybackState::Starting;
    }
    if (facts.stalled) return PlaybackState::Degraded;
    return PlaybackState::Playing;
}

const char* playback_state_name(PlaybackState state) {
    switch (state) {
        case PlaybackState::Starting: return "starting";
        case PlaybackState::Prebuffering: return "prebuffering";
        case PlaybackState::Playing: return "playing";
        case PlaybackState::Degraded: return "degraded";
        case PlaybackState::Recovering: return "recovering";
        case PlaybackState::Failed: return "failed";
    }
    return "unknown";
}

} // namespace httpace
