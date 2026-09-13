#ifndef VIRTUAL_DAP_PROTOCOL_H
#define VIRTUAL_DAP_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>

namespace virtualdap {

constexpr uint32_t kMagic = 0x56444150u;
constexpr uint32_t kAckMagic = 0x56444141u;
constexpr uint16_t kProtocolVersion = 2u;
constexpr uint16_t kControlledProtocolVersion = 3u;
constexpr uint16_t kHandshakeBytes = 32u;
constexpr uint16_t kMessageHeaderBytes = 16u;
constexpr uint16_t kAckBytes = 16u;
constexpr uint16_t kPositionAckBytes = 32u;
constexpr uint32_t kMaximumPayloadBytes = 1024u * 1024u;
constexpr const char* kDefaultSocketName = "virtualdap_audio_v1";

enum class Encoding : uint16_t {
    kPcm16 = 1,
    kPcm24Packed = 2,
    kPcm32 = 3,
    kPcmFloat = 4,
};

enum class MessageType : uint16_t {
    kAudio = 1,
    kFormat = 2,
    kStats = 3,
    kPing = 4,
    kControl = 5,
    kVolume = 6,
};

enum class PlaybackControl : uint32_t { kPlay = 1, kPause = 2, kFlush = 3, kStop = 4, kVolume = 5 };
struct PlaybackPosition {
    uint64_t source_frames = 0;
    uint64_t monotonic_ns = 0;
};

struct PcmConfig {
    uint32_t sample_rate = 48000;
    uint16_t channel_count = 2;
    Encoding encoding = Encoding::kPcm16;
    uint32_t frame_size = 4;
    uint32_t flags = 0;
    uint64_t stream_epoch = 0;

    bool operator==(const PcmConfig& other) const {
        return sample_rate == other.sample_rate && channel_count == other.channel_count &&
               encoding == other.encoding && frame_size == other.frame_size &&
               flags == other.flags && stream_epoch == other.stream_epoch;
    }
    bool operator!=(const PcmConfig& other) const { return !(*this == other); }
};

inline void put_u16_le(uint8_t* output, uint16_t value) {
    output[0] = static_cast<uint8_t>(value);
    output[1] = static_cast<uint8_t>(value >> 8u);
}

inline void put_u32_le(uint8_t* output, uint32_t value) {
    output[0] = static_cast<uint8_t>(value);
    output[1] = static_cast<uint8_t>(value >> 8u);
    output[2] = static_cast<uint8_t>(value >> 16u);
    output[3] = static_cast<uint8_t>(value >> 24u);
}

inline void put_u64_le(uint8_t* output, uint64_t value) {
    put_u32_le(output, static_cast<uint32_t>(value));
    put_u32_le(output + 4, static_cast<uint32_t>(value >> 32u));
}

inline void encode_handshake(uint8_t output[kHandshakeBytes], const PcmConfig& config) {
    put_u32_le(output, kMagic);
    put_u16_le(output + 4, kProtocolVersion);
    put_u16_le(output + 6, kHandshakeBytes);
    put_u32_le(output + 8, config.sample_rate);
    put_u16_le(output + 12, config.channel_count);
    put_u16_le(output + 14, static_cast<uint16_t>(config.encoding));
    put_u32_le(output + 16, config.frame_size);
    put_u32_le(output + 20, config.flags);
    put_u64_le(output + 24, config.stream_epoch);
}

inline void encode_message_header(uint8_t output[kMessageHeaderBytes], MessageType type,
                                  uint32_t payload_size, uint64_t sequence) {
    put_u16_le(output, static_cast<uint16_t>(type));
    put_u16_le(output + 2, 0);
    put_u32_le(output + 4, payload_size);
    put_u64_le(output + 8, sequence);
}

}  // namespace virtualdap

#endif  // VIRTUAL_DAP_PROTOCOL_H
