// Host-side tests for the bounded retry policy (odd/tasks/hap-stream-recovery.md, T1).
//
// The policy is deliberately arithmetic: how many retries are allowed and how long to wait before
// each one. What actually reconnects lives in Broadcast and needs the engine, so the decisions stay
// here where they can be pinned without a box.

#include "httpaceproxycpp/recovery_policy.hpp"

#include <gtest/gtest.h>

namespace {

httpace::RecoveryPolicy policy(int attempts, std::int64_t base = 500, std::int64_t cap = 8000) {
    httpace::RecoveryPolicy result;
    result.max_attempts = attempts;
    result.base_backoff_ms = base;
    result.max_backoff_ms = cap;
    return result;
}

} // namespace

TEST(RecoveryPolicy, DisabledMeansNoRetryAtAll) {
    EXPECT_EQ(httpace::retry_backoff_ms(policy(0), 1), 0);
    EXPECT_EQ(httpace::retry_backoff_ms(policy(-3), 1), 0);
}

TEST(RecoveryPolicy, NoBackoffOutsideTheAllowedAttempts) {
    auto configured = policy(3);
    EXPECT_EQ(httpace::retry_backoff_ms(configured, 0), 0);
    EXPECT_EQ(httpace::retry_backoff_ms(configured, 4), 0);
    EXPECT_EQ(httpace::retry_backoff_ms(configured, 100), 0);
}

TEST(RecoveryPolicy, FirstWaitIsTheBasePlusBoundedJitter) {
    auto configured = policy(3);
    std::int64_t first = httpace::retry_backoff_ms(configured, 1);
    EXPECT_GE(first, 500);
    EXPECT_LE(first, 750);
}

TEST(RecoveryPolicy, EachAttemptWaitsLongerAndIsDeterministic) {
    auto configured = policy(4);
    std::int64_t first = httpace::retry_backoff_ms(configured, 1);
    std::int64_t second = httpace::retry_backoff_ms(configured, 2);
    std::int64_t third = httpace::retry_backoff_ms(configured, 3);
    EXPECT_LT(first, second);
    EXPECT_LT(second, third);
    EXPECT_EQ(second, httpace::retry_backoff_ms(configured, 2));
}

TEST(RecoveryPolicy, TheCapIsNeverExceeded) {
    auto configured = policy(10, 500, 3000);
    for (int attempt = 1; attempt <= 10; attempt++) {
        EXPECT_LE(httpace::retry_backoff_ms(configured, attempt), 3000);
    }
    EXPECT_EQ(httpace::retry_backoff_ms(configured, 10), 3000);
}

TEST(RecoveryPolicy, HugeAttemptsDoNotOverflow) {
    auto configured = policy(10000, 500, 8000);
    EXPECT_EQ(httpace::retry_backoff_ms(configured, 10000), 8000);
    EXPECT_EQ(httpace::retry_backoff_ms(configured, 2000000000), 0);
}

TEST(RecoveryPolicy, AZeroBaseStillWaitsNothing) {
    EXPECT_EQ(httpace::retry_backoff_ms(policy(3, 0, 8000), 1), 0);
}
