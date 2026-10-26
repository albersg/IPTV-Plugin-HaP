// Host-side tests for the transport probe (odd/tasks/hap-transport-health.md, T1).
//
// Every packet here is synthetic: the point is the arithmetic of continuity, PCR, resync and the
// arrival-rate window, not a real broadcast. The probe never looks at the clock, so each test feeds
// the milliseconds it wants and the result is deterministic.

#include "httpaceproxycpp/ts_probe.hpp"

#include <gtest/gtest.h>

#include <array>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace {

using Packet = std::array<unsigned char, 188>;

constexpr std::uint16_t kVideoPid = 0x0100;
constexpr std::uint16_t kAudioPid = 0x0101;
constexpr std::uint16_t kPcrPid = 0x0200;

struct PacketOptions {
    std::uint16_t pid = kVideoPid;
    std::uint8_t continuity = 0;
    bool payload = true;
    bool adaptation = false;
    bool discontinuity = false;
    bool pcr = false;
    std::int64_t pcr_ms = 0;
};

Packet make_packet(const PacketOptions& options) {
    Packet packet{};
    packet.fill(0x11);
    packet[0] = 0x47;
    packet[1] = static_cast<unsigned char>((options.pid >> 8) & 0x1F);
    packet[2] = static_cast<unsigned char>(options.pid & 0xFF);
    bool has_adaptation = options.adaptation || options.pcr || options.discontinuity;
    packet[3] = static_cast<unsigned char>(((has_adaptation ? 0x20 : 0x00) |
                                            (options.payload ? 0x10 : 0x00) |
                                            (options.continuity & 0x0F)));
    packet[4] = 0;
    if (has_adaptation) {
        std::size_t length = 1;
        if (options.pcr) length += 6;
        packet[4] = static_cast<unsigned char>(length);
        packet[5] = static_cast<unsigned char>((options.discontinuity ? 0x80 : 0x00) |
                                               (options.pcr ? 0x10 : 0x00));
        if (options.pcr) {
            std::uint64_t base = static_cast<std::uint64_t>(options.pcr_ms) * 90;
            base &= 0x1FFFFFFFFULL;
            packet[6] = static_cast<unsigned char>((base >> 25) & 0xFF);
            packet[7] = static_cast<unsigned char>((base >> 17) & 0xFF);
            packet[8] = static_cast<unsigned char>((base >> 9) & 0xFF);
            packet[9] = static_cast<unsigned char>((base >> 1) & 0xFF);
            packet[10] = static_cast<unsigned char>(((base & 1) << 7) | 0x7E);
            packet[11] = 0;
        }
    }
    return packet;
}

std::vector<char> bytes_of(const std::vector<Packet>& packets) {
    std::vector<char> bytes;
    bytes.reserve(packets.size() * 188);
    for (const auto& packet : packets) {
        bytes.insert(bytes.end(), packet.begin(), packet.end());
    }
    return bytes;
}

std::vector<Packet> sequence(std::uint16_t pid, std::size_t count, std::uint8_t first_continuity = 0) {
    std::vector<Packet> packets;
    packets.reserve(count);
    for (std::size_t i = 0; i < count; i++) {
        PacketOptions options;
        options.pid = pid;
        options.continuity = static_cast<std::uint8_t>((first_continuity + i) & 0x0F);
        packets.push_back(make_packet(options));
    }
    return packets;
}

void feed(httpace::TsProbe& probe, const std::vector<char>& bytes, std::int64_t now_ms) {
    probe.feed(bytes.data(), bytes.size(), now_ms);
}

// A stream that parses cleanly: one packet per millisecond keeps the rate arithmetic simple.
constexpr std::int64_t kPacketIntervalMs = 1;

} // namespace

TEST(TsProbe, CleanStreamHasNoErrorsAndReportsRate) {
    httpace::TsProbe probe;
    auto bytes = bytes_of(sequence(kVideoPid, 10));
    feed(probe, bytes, 1000);

    auto counters = probe.snapshot(1000);
    EXPECT_EQ(counters.packets, 10u);
    EXPECT_EQ(counters.payload_packets, 10u);
    EXPECT_EQ(counters.bytes, 1880u);
    EXPECT_EQ(counters.continuity_errors, 0u);
    EXPECT_EQ(counters.sync_errors, 0u);
    EXPECT_TRUE(counters.synchronized);
    EXPECT_FALSE(counters.stalled);
    // 1880 bytes inside a one-second window: 1880 * 8 bits per second.
    EXPECT_DOUBLE_EQ(counters.bitrate_bps, 15040.0);
    EXPECT_EQ(counters.last_payload_age_ms, 0);
}

TEST(TsProbe, BitrateWindowAgesOutAfterOneSecond) {
    httpace::TsProbe probe;
    auto bytes = bytes_of(sequence(kVideoPid, 10));
    feed(probe, bytes, 0);
    EXPECT_DOUBLE_EQ(probe.snapshot(500).bitrate_bps, 15040.0);
    EXPECT_DOUBLE_EQ(probe.snapshot(1000).bitrate_bps, 15040.0);
    EXPECT_DOUBLE_EQ(probe.snapshot(1001).bitrate_bps, 0.0);
}

TEST(TsProbe, DroppedPacketIsExactlyOneContinuityError) {
    httpace::TsProbe probe;
    std::vector<Packet> packets;
    packets.push_back(make_packet({kVideoPid, 0}));
    packets.push_back(make_packet({kVideoPid, 1}));
    // continuity 2 is missing
    packets.push_back(make_packet({kVideoPid, 3}));
    auto bytes = bytes_of(packets);
    feed(probe, bytes, 10);

    auto counters = probe.snapshot(10);
    EXPECT_EQ(counters.continuity_errors, 1u);
    EXPECT_EQ(counters.duplicate_packets, 0u);
}

TEST(TsProbe, DuplicatedPayloadPacketIsNotAnError) {
    httpace::TsProbe probe;
    std::vector<Packet> packets;
    packets.push_back(make_packet({kVideoPid, 0}));
    packets.push_back(make_packet({kVideoPid, 1}));
    packets.push_back(make_packet({kVideoPid, 1})); // legal duplicate
    packets.push_back(make_packet({kVideoPid, 2}));
    auto bytes = bytes_of(packets);
    feed(probe, bytes, 10);

    auto counters = probe.snapshot(10);
    EXPECT_EQ(counters.duplicate_packets, 1u);
    EXPECT_EQ(counters.continuity_errors, 0u);
}

TEST(TsProbe, AdaptationOnlyPacketDoesNotAdvanceTheCounter) {
    httpace::TsProbe probe;
    std::vector<Packet> packets;
    packets.push_back(make_packet({kVideoPid, 4}));
    PacketOptions gap;
    gap.pid = kVideoPid;
    gap.continuity = 5;
    gap.payload = false;
    gap.adaptation = true;
    packets.push_back(make_packet(gap));
    packets.push_back(make_packet({kVideoPid, 5}));
    auto bytes = bytes_of(packets);
    feed(probe, bytes, 10);

    auto counters = probe.snapshot(10);
    EXPECT_EQ(counters.continuity_errors, 0u);
    EXPECT_EQ(counters.payload_packets, 2u);
}

TEST(TsProbe, DiscontinuityIndicatorResetsWithoutCountingAnError) {
    httpace::TsProbe probe;
    std::vector<Packet> packets;
    packets.push_back(make_packet({kVideoPid, 0}));
    PacketOptions jump;
    jump.pid = kVideoPid;
    jump.continuity = 9;
    jump.discontinuity = true;
    packets.push_back(make_packet(jump));
    packets.push_back(make_packet({kVideoPid, 10}));
    auto bytes = bytes_of(packets);
    feed(probe, bytes, 10);

    auto counters = probe.snapshot(10);
    EXPECT_EQ(counters.discontinuity_flags, 1u);
    EXPECT_EQ(counters.continuity_errors, 0u);
}

TEST(TsProbe, PcrIsDecodedInMillisecondsAndBackwardsJumpIsCounted) {
    httpace::TsProbe probe;
    std::vector<Packet> packets;
    PacketOptions first;
    first.pid = kPcrPid;
    first.continuity = 0;
    first.pcr = true;
    first.pcr_ms = 5000;
    packets.push_back(make_packet(first));
    PacketOptions second = first;
    second.continuity = 1;
    second.pcr_ms = 6000;
    packets.push_back(make_packet(second));
    auto bytes = bytes_of(packets);
    feed(probe, bytes, 10);

    auto forward = probe.snapshot(10);
    EXPECT_TRUE(forward.has_pcr);
    EXPECT_EQ(forward.pcr_pid, kPcrPid);
    EXPECT_EQ(forward.last_pcr_ms, 6000);
    EXPECT_EQ(forward.pcr_discontinuities, 0u);

    PacketOptions back = second;
    back.continuity = 2;
    back.pcr_ms = 4000;
    auto backwards = bytes_of({make_packet(back)});
    feed(probe, backwards, 11);
    EXPECT_EQ(probe.snapshot(11).pcr_discontinuities, 1u);
    EXPECT_EQ(probe.snapshot(11).last_pcr_ms, 4000);
}

TEST(TsProbe, NullPacketsCountTowardsBytesButNotContinuity) {
    httpace::TsProbe probe;
    auto bytes = bytes_of(sequence(0x1FFF, 5));
    feed(probe, bytes, 10);

    auto counters = probe.snapshot(10);
    EXPECT_EQ(counters.null_packets, 5u);
    EXPECT_EQ(counters.packets, 5u);
    EXPECT_EQ(counters.continuity_errors, 0u);
    EXPECT_EQ(counters.tracked_pids, 0u);
    EXPECT_FALSE(counters.stalled);
}

TEST(TsProbe, TracksAtMostSixtyFourPids) {
    httpace::TsProbe probe;
    std::vector<Packet> packets;
    for (std::uint16_t pid = 0x20; pid < 0x20 + 100; pid++) {
        packets.push_back(make_packet({pid, 0}));
    }
    auto bytes = bytes_of(packets);
    feed(probe, bytes, 10);

    auto counters = probe.snapshot(10);
    EXPECT_EQ(counters.tracked_pids, 64u);
    EXPECT_EQ(counters.untracked_packets, 36u);
}

TEST(TsProbe, ChunkBoundariesDoNotSplitTheAccounting) {
    httpace::TsProbe probe;
    auto bytes = bytes_of(sequence(kVideoPid, 6));

    // One byte at a time: every packet is completed by the carried-over tail.
    for (std::size_t i = 0; i < bytes.size(); i++) {
        probe.feed(&bytes[i], 1, static_cast<std::int64_t>(i));
    }

    auto counters = probe.snapshot(1000);
    EXPECT_EQ(counters.packets, 6u);
    EXPECT_EQ(counters.continuity_errors, 0u);
    EXPECT_EQ(counters.sync_errors, 0u);
}

TEST(TsProbe, GarbageThenValidStreamResynchronises) {
    httpace::TsProbe probe;
    std::vector<char> garbage(300, 'x');
    auto packets = bytes_of(sequence(kVideoPid, 4));
    garbage.insert(garbage.end(), packets.begin(), packets.end());

    probe.feed(garbage.data(), garbage.size(), 10);

    auto counters = probe.snapshot(10);
    EXPECT_EQ(counters.packets, 4u);
    EXPECT_GE(counters.sync_errors, 300u);
    EXPECT_EQ(counters.resyncs, 1u);
    EXPECT_TRUE(counters.synchronized);
}

TEST(TsProbe, GarbageOnlyNeverClaimsSynchronisationOrStall) {
    httpace::TsProbe probe;
    std::vector<char> garbage(2000, 'x');
    probe.feed(garbage.data(), garbage.size(), 10);

    auto counters = probe.snapshot(60000);
    EXPECT_FALSE(counters.synchronized);
    EXPECT_FALSE(counters.stalled);
    EXPECT_EQ(counters.packets, 0u);
    EXPECT_EQ(counters.sync_errors, 2000u);
}

TEST(TsProbe, PayloadStallAfterThresholdAndRecoveryOnNextPayload) {
    httpace::TsProbe probe(5000);
    auto bytes = bytes_of(sequence(kVideoPid, 2));
    feed(probe, bytes, 1000);
    EXPECT_FALSE(probe.snapshot(5999).stalled);
    EXPECT_TRUE(probe.snapshot(6000).stalled);
    EXPECT_EQ(probe.snapshot(6000).last_payload_age_ms, 5000);

    auto more = bytes_of(sequence(kVideoPid, 1, 2));
    feed(probe, more, 7000);
    EXPECT_FALSE(probe.snapshot(7000).stalled);
    EXPECT_EQ(probe.snapshot(7000).last_payload_age_ms, 0);
}

TEST(TsProbe, SnapshotDoesNotMutateTheCounters) {
    httpace::TsProbe probe;
    auto bytes = bytes_of(sequence(kVideoPid, 3));
    feed(probe, bytes, 10);
    auto first = probe.snapshot(20);
    auto second = probe.snapshot(30);
    EXPECT_EQ(first.packets, second.packets);
    EXPECT_EQ(first.bytes, second.bytes);
    EXPECT_DOUBLE_EQ(first.bitrate_bps, second.bitrate_bps);
    EXPECT_EQ(first.last_payload_age_ms, 10);
    EXPECT_EQ(second.last_payload_age_ms, 20);
}

TEST(TsProbe, ResetForgetsEverything) {
    httpace::TsProbe probe;
    auto bytes = bytes_of(sequence(kVideoPid, 5));
    feed(probe, bytes, 10);
    probe.reset();

    auto counters = probe.snapshot(10);
    EXPECT_EQ(counters.packets, 0u);
    EXPECT_EQ(counters.bytes, 0u);
    EXPECT_FALSE(counters.synchronized);
    EXPECT_FALSE(counters.stalled);
    EXPECT_EQ(counters.last_payload_age_ms, -1);
}

TEST(TsProbe, TenMegabytesInSmallChunksStaysBounded) {
    httpace::TsProbe probe;
    auto packet = make_packet({kVideoPid, 0});
    std::vector<char> block;
    block.reserve(64 * 1024);
    std::uint8_t continuity = 0;
    std::int64_t now = 0;
    std::uint64_t fed = 0;
    while (fed < 10u * 1024u * 1024u) {
        block.clear();
        while (block.size() < 64 * 1024) {
            packet[3] = static_cast<unsigned char>((packet[3] & 0xF0) | (continuity & 0x0F));
            continuity++;
            block.insert(block.end(), packet.begin(), packet.end());
        }
        probe.feed(block.data(), block.size(), now);
        fed += block.size();
        now += 1000;
    }

    auto counters = probe.snapshot(now);
    EXPECT_EQ(counters.packets, fed / 188);
    EXPECT_EQ(counters.continuity_errors, 0u);
    EXPECT_EQ(counters.sync_errors, 0u);
    EXPECT_EQ(counters.tracked_pids, 1u);
}
