#include "httpaceproxycpp/ts_probe.hpp"

#include <algorithm>

namespace httpace {

namespace {
constexpr int kContinuityMask = 0x0F;
constexpr unsigned char kAdaptationOnlyFlag = 0x20;
constexpr unsigned char kPayloadFlag = 0x10;
constexpr unsigned char kDiscontinuityFlag = 0x80;
constexpr unsigned char kPcrFlag = 0x10;
constexpr std::size_t kPcrAdaptationLength = 7;
constexpr std::size_t kHeaderSize = 4;
// Ten 100 ms buckets cover 0.9-1.0 s of arrival, quantised at the trailing edge.
constexpr std::int64_t kRateWindowBuckets = 10;
// More than one second of PCR regression is a discontinuity; the spec allows a small reorder.
constexpr std::int64_t kPcrToleranceMs = 1000;
} // namespace

TsProbe::TsProbe(std::int64_t stall_threshold_ms)
    : stall_threshold_ms_(std::max<std::int64_t>(0, stall_threshold_ms)) {}

void TsProbe::reset() {
    buffer_.clear();
    buffer_.shrink_to_fit();
    cursor_ = 0;
    unsynced_ = false;
    pids_.fill(PidState{});
    tracked_pids_ = 0;
    rates_.fill(RateBucket{});
    packets_ = 0;
    bytes_ = 0;
    payload_packets_ = 0;
    payload_bytes_ = 0;
    null_packets_ = 0;
    resyncs_ = 0;
    continuity_errors_ = 0;
    discontinuity_flags_ = 0;
    duplicate_packets_ = 0;
    pcr_discontinuities_ = 0;
    untracked_packets_ = 0;
    has_pcr_ = false;
    pcr_pid_ = 0;
    last_pcr_ms_ = 0;
    has_payload_ = false;
    last_payload_ms_ = 0;
}

void TsProbe::feed(const char* data, std::size_t size, std::int64_t now_ms) {
    if (data == nullptr || size == 0) return;
    bytes_ += size;
    record_rate(size, now_ms);
    const auto* first = reinterpret_cast<const unsigned char*>(data);
    buffer_.insert(buffer_.end(), first, first + size);
    process(now_ms);
    if (cursor_ > 0) {
        buffer_.erase(buffer_.begin(), buffer_.begin() + static_cast<std::ptrdiff_t>(cursor_));
        cursor_ = 0;
    }
}

void TsProbe::process(std::int64_t now_ms) {
    std::size_t index = cursor_;
    const std::size_t size = buffer_.size();

    if (unsynced_) {
        const std::size_t offset = find_sync(buffer_.data() + index, size - index);
        if (offset == kNoOffset) {
            if (size - index > kMaxUnsyncedBytes) index = size - kMaxUnsyncedBytes;
            cursor_ = index;
            return;
        }
        index += offset;
        resyncs_++;
        unsynced_ = false;
    }

    while (size - index >= kPacketSize) {
        if (buffer_[index] != kSyncByte) {
            unsynced_ = true;
            const std::size_t offset = find_sync(buffer_.data() + index, size - index);
            if (offset == kNoOffset) {
                if (size - index > kMaxUnsyncedBytes) index = size - kMaxUnsyncedBytes;
                cursor_ = index;
                return;
            }
            index += offset;
            resyncs_++;
            unsynced_ = false;
            continue;
        }
        parse_packet(buffer_.data() + index, now_ms);
        index += kPacketSize;
    }
    cursor_ = index;
}

std::size_t TsProbe::find_sync(const unsigned char* data, std::size_t size) {
    if (size < kPacketSize) return kNoOffset;
    // Three packets at the 188 stride: two confirmations after a random 0x47 are already rare, and
    // a third keeps a payload that happens to contain 0x47 from looking like the start of a frame.
    for (std::size_t offset = 0; offset + kPacketSize < size; offset++) {
        if (data[offset] != kSyncByte) continue;
        if (data[offset + kPacketSize] != kSyncByte) continue;
        if (offset + 2 * kPacketSize < size && data[offset + 2 * kPacketSize] != kSyncByte) continue;
        return offset;
    }
    return kNoOffset;
}

void TsProbe::parse_packet(const unsigned char* packet, std::int64_t now_ms) {
    packets_++;
    const std::uint16_t pid =
            static_cast<std::uint16_t>(((packet[1] & 0x1F) << 8) | packet[2]);
    const bool has_payload = (packet[3] & kPayloadFlag) != 0;
    const bool has_adaptation = (packet[3] & kAdaptationOnlyFlag) != 0;
    const std::uint8_t continuity = static_cast<std::uint8_t>(packet[3] & kContinuityMask);
    const std::uint8_t adaptation_length = has_adaptation ? packet[4] : 0;

    bool discontinuity = false;
    bool pcr_flag = false;
    std::int64_t pcr_ms = 0;
    if (has_adaptation && adaptation_length > 0) {
        const std::uint8_t flags = packet[5];
        discontinuity = (flags & kDiscontinuityFlag) != 0;
        pcr_flag = (flags & kPcrFlag) != 0;
        if (pcr_flag && adaptation_length >= kPcrAdaptationLength) {
            const std::uint64_t base =
                    (static_cast<std::uint64_t>(packet[6]) << 25) |
                    (static_cast<std::uint64_t>(packet[7]) << 17) |
                    (static_cast<std::uint64_t>(packet[8]) << 9) |
                    (static_cast<std::uint64_t>(packet[9]) << 1) |
                    (static_cast<std::uint64_t>(packet[10]) >> 7);
            pcr_ms = static_cast<std::int64_t>(base / 90);
        }
    }
    if (discontinuity) discontinuity_flags_++;

    if (pid == kNullPid) {
        null_packets_++;
        return;
    }

    if (pcr_flag) {
        if (has_pcr_ && pcr_pid_ == pid && pcr_ms + kPcrToleranceMs < last_pcr_ms_) {
            pcr_discontinuities_++;
        }
        has_pcr_ = true;
        pcr_pid_ = pid;
        last_pcr_ms_ = pcr_ms;
    }

    PidState* state = pid_state(pid);
    if (state == nullptr) {
        untracked_packets_++;
    } else if (discontinuity) {
        state->has_continuity = false;
    }

    if (!has_payload) {
        // An adaptation-only packet must not advance the continuity expectation.
        return;
    }

    if (state != nullptr && !discontinuity) {
        if (state->has_continuity) {
            const std::uint8_t expected =
                    static_cast<std::uint8_t>((state->last_continuity + 1) & kContinuityMask);
            if (continuity == state->last_continuity) {
                duplicate_packets_++;
            } else if (continuity != expected) {
                continuity_errors_++;
            }
        }
    }
    if (state != nullptr) {
        state->last_continuity = continuity;
        state->has_continuity = true;
    }

    std::size_t header_offset = kHeaderSize;
    if (has_adaptation) header_offset += static_cast<std::size_t>(1) + adaptation_length;
    if (header_offset > kPacketSize) header_offset = kPacketSize;
    payload_packets_++;
    payload_bytes_ += kPacketSize - header_offset;
    has_payload_ = true;
    last_payload_ms_ = now_ms;
}

TsProbe::PidState* TsProbe::pid_state(std::uint16_t pid) {
    const std::size_t start = pid % kSlotCount;
    for (std::size_t probe = 0; probe < kSlotCount; probe++) {
        PidState& slot = pids_[(start + probe) % kSlotCount];
        if (!slot.used) {
            if (tracked_pids_ >= kMaxPids) return nullptr;
            slot = PidState{};
            slot.used = true;
            slot.pid = pid;
            tracked_pids_++;
            return &slot;
        }
        if (slot.pid == pid) return &slot;
    }
    return nullptr;
}

void TsProbe::record_rate(std::uint64_t bytes, std::int64_t now_ms) {
    const std::int64_t index = now_ms / kRateBucketMs;
    RateBucket& bucket = rates_[static_cast<std::size_t>(index % kRateBuckets)];
    if (bucket.index != index) {
        bucket.index = index;
        bucket.bytes = 0;
    }
    bucket.bytes += bytes;
}

double TsProbe::window_bitrate(std::int64_t now_ms) const {
    const std::int64_t newest = now_ms / kRateBucketMs;
    std::uint64_t window_bytes = 0;
    for (const RateBucket& bucket : rates_) {
        if (bucket.index < 0) continue;
        const std::int64_t age = newest - bucket.index;
        if (age >= 0 && age < kRateWindowBuckets) window_bytes += bucket.bytes;
    }
    // The buckets cover one second, so a byte in them is one bit per second times eight.
    return static_cast<double>(window_bytes) * 8.0;
}

TsProbe::Counters TsProbe::snapshot(std::int64_t now_ms) const {
    Counters counters;
    counters.packets = packets_;
    counters.bytes = bytes_;
    counters.payload_packets = payload_packets_;
    counters.payload_bytes = payload_bytes_;
    counters.null_packets = null_packets_;
    counters.resyncs = resyncs_;
    counters.continuity_errors = continuity_errors_;
    counters.discontinuity_flags = discontinuity_flags_;
    counters.duplicate_packets = duplicate_packets_;
    counters.pcr_discontinuities = pcr_discontinuities_;
    counters.tracked_pids = tracked_pids_;
    counters.untracked_packets = untracked_packets_;
    counters.bitrate_bps = window_bitrate(now_ms);
    counters.has_pcr = has_pcr_;
    counters.pcr_pid = pcr_pid_;
    counters.last_pcr_ms = last_pcr_ms_;
    const std::uint64_t framed = packets_ * kPacketSize;
    const std::uint64_t unframed = buffer_.size() - cursor_;
    // While synced, the trailing <188 bytes are a packet in progress, not an error. While unsynced
    // every held byte is a byte that has not framed, so it counts.
    const std::uint64_t exempt = unsynced_ ? 0 : unframed;
    counters.sync_errors = bytes_ > framed + exempt ? bytes_ - framed - exempt : 0;
    // Synchronised means "framing right now", not "framed something once". The unframed backlog is
    // exactly what the buffer still holds: a preamble that was skipped and then left behind by the
    // first good packet must not keep the stream looking broken for the rest of its life (the first
    // version derived this from bytes - packets * 188 and latched false forever).
    counters.synchronized = packets_ > 0 && unframed < 1000;

    if (has_payload_) {
        counters.last_payload_age_ms = std::max<std::int64_t>(0, now_ms - last_payload_ms_);
        counters.stalled = counters.last_payload_age_ms >= stall_threshold_ms_;
    }
    return counters;
}

} // namespace httpace
