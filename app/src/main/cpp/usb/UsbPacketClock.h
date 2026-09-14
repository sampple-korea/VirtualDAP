#pragma once
#include <cstddef>
#include <cstdint>
#include <stdexcept>

namespace virtualdap::usb {

/** Exact nominal rate and explicit 10.14/16.16 feedback; never changes a PCM/DSD sample. */
class PacketClock {
public:
    PacketClock(uint32_t rate, uint32_t bus_ticks_per_second, uint32_t interval_ticks)
        : rate_(rate), bus_ticks_(bus_ticks_per_second), interval_ticks_(interval_ticks),
          numerator_(uint64_t(rate) * 65536), denominator_(uint64_t(bus_ticks_per_second) * 65536) {
        if (rate < 8000 || rate > 6144000 || (bus_ticks_ != 1000 && bus_ticks_ != 8000) ||
            interval_ticks == 0 || interval_ticks > 32768 ||
            (interval_ticks & (interval_ticks - 1)) != 0) {
            throw std::invalid_argument("Invalid USB service clock");
        }
    }
    uint32_t next_frames() {
        phase_ += numerator_ * interval_ticks_;
        const auto frames = phase_ / denominator_;
        phase_ %= denominator_;
        return static_cast<uint32_t>(frames);
    }
    bool feedback(const uint8_t* bytes, size_t length) {
        if (!bytes || (length != 3 && length != 4)) return false;
        uint64_t value = uint64_t(bytes[0]) | uint64_t(bytes[1]) << 8 | uint64_t(bytes[2]) << 16;
        if (length == 4) value |= uint64_t(bytes[3]) << 24;
        else value <<= 2; // 10.14 -> 16.16.
        const uint64_t candidate = value * bus_ticks_;
        const uint64_t nominal = uint64_t(rate_) * 65536;
        // Reject zero, garbage and vendor-specific frame/microframe mismatches rather than
        // silently applying an undocumented factor-of-eight quirk.
        if (candidate < nominal * 98 / 100 || candidate > nominal * 102 / 100) return false;
        numerator_ = candidate;
        return true;
    }
    uint32_t maximum_frames(bool feedback = true) const {
        const uint64_t maximum = uint64_t(rate_) * (feedback ? 102 : 100) * interval_ticks_;
        return static_cast<uint32_t>((maximum + uint64_t(bus_ticks_) * 100 - 1) / (uint64_t(bus_ticks_) * 100));
    }
private:
    uint32_t rate_, bus_ticks_, interval_ticks_;
    uint64_t numerator_, denominator_, phase_ = 0;
};
}
