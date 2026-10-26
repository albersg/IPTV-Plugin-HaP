#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace httpace {

/**
 * A bounded, allocation-free-per-packet probe over one MPEG-TS byte stream.
 *
 * <p>Purpose (odd/tasks/hap-transport-health.md, S1-S4): the proxy can say what the transport of an
 * active upstream is doing - bytes, arrival rate, continuity, PCR discontinuities, and whether
 * payload stopped arriving - instead of guessing from a socket that is still open. It is the input
 * of the recovery work, not a verdict: a clean transport says nothing about whether an external
 * player renders it (S4).
 *
 * <p>Contract:
 * <ul>
 *   <li>The caller owns the thread and the clock: {@link #feed} and {@link #snapshot} take a
 *       monotonic millisecond value, so the arithmetic is deterministic in a test.</li>
 *   <li>Cost is O(1) per 188-byte packet: no allocation per packet, at most 64 tracked PIDs in a
 *       fixed table, one buffer bounded by the largest HTTP chunk plus 4 KiB, and no thread.</li>
 *   <li>Nothing here is Android-specific: the same source compiles into the APK and into the host
 *       test suite.</li>
 *   <li>A stream that never frames a packet is not synchronized; a stream that frames packets but
 *       stops delivering payload is stalled. The two are different answers on purpose.</li>
 * </ul>
 */
class TsProbe {
public:
    /** Immutable view of the counters at one instant. */
    struct Counters {
        std::uint64_t packets = 0;
        std::uint64_t bytes = 0;
        std::uint64_t payload_packets = 0;
        std::uint64_t payload_bytes = 0;
        std::uint64_t null_packets = 0;
        /** Bytes that could not be framed as a packet (discarded or still held while unsynced). */
        std::uint64_t sync_errors = 0;
        std::uint64_t resyncs = 0;
        std::uint64_t continuity_errors = 0;
        std::uint64_t discontinuity_flags = 0;
        std::uint64_t duplicate_packets = 0;
        std::uint64_t pcr_discontinuities = 0;
        std::uint64_t tracked_pids = 0;
        std::uint64_t untracked_packets = 0;
        /** Bytes fed inside the last second, in bits per second. */
        double bitrate_bps = 0.0;
        /** Age of the last payload byte, or -1 when no payload has arrived. */
        std::int64_t last_payload_age_ms = -1;
        bool has_pcr = false;
        std::uint32_t pcr_pid = 0;
        std::int64_t last_pcr_ms = 0;
        bool stalled = false;
        bool synchronized = false;
    };

    /** @param stall_threshold_ms payload silence that counts as stalled for this stream */
    explicit TsProbe(std::int64_t stall_threshold_ms = 5000);

    /** Forgets counters, PID state and the arrival window. Used when a new upstream starts. */
    void reset();

    /** Consumes one chunk of the upstream, whatever its size. Never blocks, never throws. */
    void feed(const char* data, std::size_t size, std::int64_t now_ms);

    /** The counters as of {@code now_ms}. Const: taking a snapshot never changes the stream. */
    Counters snapshot(std::int64_t now_ms) const;

private:
    static constexpr std::size_t kPacketSize = 188;
    static constexpr unsigned char kSyncByte = 0x47;
    static constexpr std::uint16_t kNullPid = 0x1FFF;
    static constexpr std::size_t kMaxPids = 64;
    static constexpr std::size_t kSlotCount = 128;
    /** While unsynchronized, only this many trailing bytes are kept to look for the next frame. */
    static constexpr std::size_t kMaxUnsyncedBytes = 4096;
    static constexpr std::size_t kRateSlots = 64;
    static constexpr std::size_t kNoOffset = static_cast<std::size_t>(-1);

    struct PidState {
        bool used = false;
        std::uint16_t pid = 0;
        bool has_continuity = false;
        std::uint8_t last_continuity = 0;
        bool has_pcr = false;
        std::int64_t last_pcr_ms = 0;
    };

    struct RateSample {
        std::int64_t at = 0;
        std::uint64_t bytes = 0;
    };

    void process(std::int64_t now_ms);
    void parse_packet(const unsigned char* packet, std::int64_t now_ms);
    void record_rate(std::uint64_t bytes, std::int64_t now_ms);
    double window_bitrate(std::int64_t now_ms) const;
    PidState* pid_state(std::uint16_t pid);
    static std::size_t find_sync(const unsigned char* data, std::size_t size);

    std::int64_t stall_threshold_ms_;
    std::vector<unsigned char> buffer_;
    std::size_t cursor_ = 0;
    bool unsynced_ = false;

    std::array<PidState, kSlotCount> pids_{};
    std::size_t tracked_pids_ = 0;
    std::array<RateSample, kRateSlots> rates_{};
    std::size_t rate_head_ = 0;
    std::size_t rate_count_ = 0;

    std::uint64_t packets_ = 0;
    std::uint64_t bytes_ = 0;
    std::uint64_t payload_packets_ = 0;
    std::uint64_t payload_bytes_ = 0;
    std::uint64_t null_packets_ = 0;
    std::uint64_t resyncs_ = 0;
    std::uint64_t continuity_errors_ = 0;
    std::uint64_t discontinuity_flags_ = 0;
    std::uint64_t duplicate_packets_ = 0;
    std::uint64_t pcr_discontinuities_ = 0;
    std::uint64_t untracked_packets_ = 0;
    bool has_pcr_ = false;
    std::uint32_t pcr_pid_ = 0;
    std::int64_t last_pcr_ms_ = 0;
    bool has_payload_ = false;
    std::int64_t last_payload_ms_ = 0;
};

} // namespace httpace
