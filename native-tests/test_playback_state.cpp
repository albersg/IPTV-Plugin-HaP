#include "httpaceproxycpp/playback_state.hpp"

#include <gtest/gtest.h>

#include <string>

namespace {

using httpace::classify_playback_state;
using httpace::PlaybackFacts;
using httpace::PlaybackState;
using httpace::playback_state_name;

PlaybackFacts facts(bool gave_up = false, bool retrying = false, bool prebuffering = false,
                    bool payload = false, bool stalled = false) {
    PlaybackFacts result;
    result.gave_up = gave_up;
    result.retrying = retrying;
    result.engine_prebuffering = prebuffering;
    result.has_payload = payload;
    result.stalled = stalled;
    return result;
}

} // namespace

TEST(PlaybackState, aFreshSessionPrebufferingSaysSo) {
    EXPECT_EQ(classify_playback_state(facts(false, false, true, false, false)), PlaybackState::Prebuffering);
}

TEST(PlaybackState, aSessionWithNoPayloadAndNoEngineWordIsStarting) {
    EXPECT_EQ(classify_playback_state(facts()), PlaybackState::Starting);
}

TEST(PlaybackState, payloadFlowingIsPlaying) {
    EXPECT_EQ(classify_playback_state(facts(false, false, false, true, false)), PlaybackState::Playing);
}

TEST(PlaybackState, payloadThatStoppedIsDegraded) {
    EXPECT_EQ(classify_playback_state(facts(false, false, false, true, true)), PlaybackState::Degraded);
}

TEST(PlaybackState, aRetryWithoutPayloadIsRecoveringWhateverTheEngineSays) {
    EXPECT_EQ(classify_playback_state(facts(false, true, true, false, false)), PlaybackState::Recovering);
    EXPECT_EQ(classify_playback_state(facts(false, true, false, false, true)), PlaybackState::Recovering);
}

TEST(PlaybackState, aRetryThatDeliveredIsPlayingAgain) {
    EXPECT_EQ(classify_playback_state(facts(false, true, false, true, false)), PlaybackState::Playing);
}

TEST(PlaybackState, givingUpWinsOverEveryOtherFact) {
    EXPECT_EQ(classify_playback_state(facts(true, true, true, false, true)), PlaybackState::Failed);
    EXPECT_EQ(classify_playback_state(facts(true, false, false, true, false)), PlaybackState::Failed);
}

TEST(PlaybackState, aStallOnASessionWithoutPayloadIsNotDegraded) {
    // Nothing ever played, so "degraded" would claim a quality the session never had.
    EXPECT_EQ(classify_playback_state(facts(false, false, false, false, true)), PlaybackState::Starting);
}

TEST(PlaybackState, theNamesAreTheJsonContract) {
    EXPECT_STREQ(playback_state_name(PlaybackState::Starting), "starting");
    EXPECT_STREQ(playback_state_name(PlaybackState::Prebuffering), "prebuffering");
    EXPECT_STREQ(playback_state_name(PlaybackState::Playing), "playing");
    EXPECT_STREQ(playback_state_name(PlaybackState::Degraded), "degraded");
    EXPECT_STREQ(playback_state_name(PlaybackState::Recovering), "recovering");
    EXPECT_STREQ(playback_state_name(PlaybackState::Failed), "failed");
}
