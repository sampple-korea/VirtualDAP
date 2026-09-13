#include "BridgeTransport.h"

#include <stdint.h>

#include <array>
#include <cstdio>

namespace {

constexpr uint32_t kSampleRate = 48000;
constexpr uint16_t kChannels = 2;
constexpr uint32_t kFramesPerChunk = 480;
constexpr uint32_t kChunkCount = 200;

void put_sample(uint8_t* output, int16_t sample) {
    output[0] = static_cast<uint8_t>(sample & 0xff);
    output[1] = static_cast<uint8_t>((static_cast<uint16_t>(sample) >> 8u) & 0xff);
}

}  // namespace

int main(int argc, char** argv) {
    const std::string endpoint = argc > 1 ? argv[1] : virtualdap::kDefaultSocketName;
    virtualdap::BridgeTransport transport(endpoint);
    const virtualdap::PcmConfig config{
        kSampleRate,
        kChannels,
        virtualdap::Encoding::kPcm16,
        kChannels * sizeof(int16_t),
        1,
        1,
    };
    std::array<uint8_t, kFramesPerChunk * kChannels * sizeof(int16_t)> pcm{};
    uint64_t frame_index = 0;
    for (uint32_t chunk = 0; chunk < kChunkCount; ++chunk) {
        for (uint32_t frame = 0; frame < kFramesPerChunk; ++frame, ++frame_index) {
            // Low-level 440 Hz square wave: audible enough for route checks and simple
            // enough that this tool has no floating-point or libm dependency.
            const int16_t sample = ((frame_index * 880u / kSampleRate) & 1u) == 0u
                                       ? static_cast<int16_t>(2500)
                                       : static_cast<int16_t>(-2500);
            const size_t offset = frame * kChannels * sizeof(int16_t);
            put_sample(pcm.data() + offset, sample);
            put_sample(pcm.data() + offset + sizeof(int16_t), sample);
        }
        if (!transport.write(config, pcm.data(), pcm.size())) {
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
    return transport.frames_written() == static_cast<uint64_t>(kFramesPerChunk) * kChunkCount &&
                   transport.dropped_bytes() == 0
               ? 0
               : 3;
}
