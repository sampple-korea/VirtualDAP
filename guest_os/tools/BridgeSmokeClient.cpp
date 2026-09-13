#include "BridgeTransport.h"

#include <stdint.h>

#include <cstdio>
#include <cstring>
#include <vector>

namespace {

constexpr uint16_t kChannels = 2;
constexpr uint32_t kChunksPerFormat = 50;

uint32_t bytes_per_sample(virtualdap::Encoding encoding) {
    switch (encoding) {
        case virtualdap::Encoding::kPcm16:
            return 2;
        case virtualdap::Encoding::kPcm24Packed:
            return 3;
        case virtualdap::Encoding::kPcm32:
        case virtualdap::Encoding::kPcmFloat:
            return 4;
    }
    return 0;
}

void put_sample(uint8_t* output, virtualdap::Encoding encoding, bool positive) {
    switch (encoding) {
        case virtualdap::Encoding::kPcm16: {
            const int16_t sample = positive ? 2048 : -2048;
            virtualdap::put_u16_le(output, static_cast<uint16_t>(sample));
            break;
        }
        case virtualdap::Encoding::kPcm24Packed: {
            const int32_t sample = positive ? 524288 : -524288;
            const uint32_t raw = static_cast<uint32_t>(sample);
            output[0] = static_cast<uint8_t>(raw);
            output[1] = static_cast<uint8_t>(raw >> 8u);
            output[2] = static_cast<uint8_t>(raw >> 16u);
            break;
        }
        case virtualdap::Encoding::kPcm32: {
            const int32_t sample = positive ? 134217728 : -134217728;
            virtualdap::put_u32_le(output, static_cast<uint32_t>(sample));
            break;
        }
        case virtualdap::Encoding::kPcmFloat: {
            static_assert(sizeof(float) == sizeof(uint32_t));
            const float sample = positive ? 0.0625f : -0.0625f;
            uint32_t bits = 0;
            std::memcpy(&bits, &sample, sizeof(bits));
            virtualdap::put_u32_le(output, bits);
            break;
        }
    }
}

bool send_format(virtualdap::BridgeTransport* transport, const virtualdap::PcmConfig& config) {
    const uint32_t frames_per_chunk = config.sample_rate / 100u;
    std::vector<uint8_t> pcm(static_cast<size_t>(frames_per_chunk) * config.frame_size);
    uint64_t frame_index = 0;
    for (uint32_t chunk = 0; chunk < kChunksPerFormat; ++chunk) {
        for (uint32_t frame = 0; frame < frames_per_chunk; ++frame, ++frame_index) {
            const bool positive = ((frame_index * 880u / config.sample_rate) & 1u) == 0u;
            for (uint16_t channel = 0; channel < config.channel_count; ++channel) {
                const size_t offset = static_cast<size_t>(frame) * config.frame_size +
                                      channel * bytes_per_sample(config.encoding);
                put_sample(pcm.data() + offset, config.encoding, positive);
            }
        }
        if (!transport->write(config, pcm.data(), pcm.size())) return false;
    }
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    const std::string endpoint = argc > 1 ? argv[1] : virtualdap::kDefaultSocketName;
    virtualdap::BridgeTransport transport(endpoint);
    const std::vector<virtualdap::PcmConfig> formats{
        {48000, kChannels, virtualdap::Encoding::kPcm16, 4, 0, 1},
        {96000, kChannels, virtualdap::Encoding::kPcm24Packed, 6, 1, 2},
        {192000, kChannels, virtualdap::Encoding::kPcm32, 8, 1, 3},
        {44100, kChannels, virtualdap::Encoding::kPcmFloat, 8, 1, 4},
    };
    uint64_t expected_frames = 0;
    for (const auto& format : formats) {
        expected_frames += static_cast<uint64_t>(format.sample_rate / 100u) * kChunksPerFormat;
        if (!send_format(&transport, format)) {
            std::fprintf(
                stderr,
                "bridge write failed after %llu frames (%llu bytes dropped, %llu connects)\n",
                static_cast<unsigned long long>(transport.frames_written()),
                static_cast<unsigned long long>(transport.dropped_bytes()),
                static_cast<unsigned long long>(transport.reconnect_count()));
            return 2;
        }
    }
    transport.disconnect();
    std::printf(
        "bridge smoke test completed: %llu frames, %llu dropped bytes, %llu connects\n",
        static_cast<unsigned long long>(transport.frames_written()),
        static_cast<unsigned long long>(transport.dropped_bytes()),
        static_cast<unsigned long long>(transport.reconnect_count()));
    return transport.frames_written() == expected_frames && transport.dropped_bytes() == 0
               ? 0
               : 3;
}
