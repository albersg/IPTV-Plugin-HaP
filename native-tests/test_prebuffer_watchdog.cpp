#include "httpaceproxycpp/prebuffer_watchdog.hpp"

#include <gtest/gtest.h>

#include <map>
#include <string>

namespace {

using httpace::should_end_silent_session;
using httpace::status_has_peers;
using httpace::status_indicates_progress;

std::map<std::string, std::string> status(std::initializer_list<std::pair<const std::string, std::string>> fields) {
    return std::map<std::string, std::string>(fields);
}

} // namespace

TEST(PrebufferProgress, aPrebufferWithOnlyZerosIsNotProgress) {
    EXPECT_FALSE(status_indicates_progress(status({
            {"status", "prebuf"}, {"total_progress", "0"}, {"immediate_progress", "0"},
            {"downloaded", "0"}, {"http_downloaded", "0"}, {"speed_down", "0"}, {"peers", "12"}})));
}

TEST(PrebufferProgress, downloadedBytesAreProgress) {
    EXPECT_TRUE(status_indicates_progress(status({
            {"status", "prebuf"}, {"total_progress", "0"}, {"downloaded", "4096"}})));
}

TEST(PrebufferProgress, aStatePastThePrebufferIsProgress) {
    EXPECT_TRUE(status_indicates_progress(status({{"status", "buf"}})));
    EXPECT_TRUE(status_indicates_progress(status({{"status", "idle"}})));
}

TEST(PrebufferProgress, aWaitStateIsStillAPrebuffer) {
    EXPECT_FALSE(status_indicates_progress(status({{"status", "wait"}, {"downloaded", "0"}})));
    EXPECT_FALSE(status_indicates_progress(status({{"status", "seekprebuf"}, {"downloaded", "0"}})));
}

TEST(PrebufferWatchdog, disabledNeverAborts) {
    EXPECT_FALSE(should_end_silent_session(0, true, false, 10'000'000, 1));
}

TEST(PrebufferWatchdog, aSessionThatIsNotPrebufferingIsLeftAlone) {
    EXPECT_FALSE(should_end_silent_session(20, false, false, 100'000, 1));
}

TEST(PrebufferWatchdog, silenceBelowTheThresholdDoesNotAbort) {
    EXPECT_FALSE(should_end_silent_session(20, true, false, 19'999, 0 + 1));
}

TEST(PrebufferWatchdog, silenceAtTheThresholdAborts) {
    EXPECT_TRUE(should_end_silent_session(20, true, false, 20'001, 0 + 1));
}

TEST(PrebufferWatchdog, anUnmeasuredSessionNeverAborts) {
    EXPECT_FALSE(should_end_silent_session(20, true, false, 100'000, 0));
}

TEST(PrebufferWatchdog, aSilentSessionWithPeersIsLeftToTheEngine) {
    EXPECT_FALSE(should_end_silent_session(20, true, true, 100'000, 1));
}

TEST(PrebufferWatchdog, peersAreReadFromEitherPeerCounter) {
    EXPECT_TRUE(status_has_peers(status({{"peers", "3"}})));
    EXPECT_TRUE(status_has_peers(status({{"http_peers", "1"}})));
    EXPECT_FALSE(status_has_peers(status({{"peers", "0"}, {"http_peers", "0"}})));
}

TEST(PrebufferWatchdog, aClockThatWentBackwardsNeverAborts) {
    EXPECT_FALSE(should_end_silent_session(20, true, false, 1'000, 5'000));
}
