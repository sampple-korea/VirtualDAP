#include "CapturedPcmStream.h"
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#define CHECK(value) do { if (!(value)) { \
    std::fprintf(stderr, "Check failed at line %d: %s\n", __LINE__, #value); std::abort(); \
} } while (false)

using namespace virtualdap;
using namespace std::chrono_literals;

uint32_t u32(const uint8_t* p) {
    return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
}
uint64_t u64(const uint8_t* p) { return u32(p) | (uint64_t(u32(p + 4)) << 32); }
bool read_exact(int fd, void* data, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        const auto read = recv(fd, static_cast<uint8_t*>(data) + offset, size - offset, 0);
        if (read <= 0) return false;
        offset += static_cast<size_t>(read);
    }
    return true;
}
template<typename F> void await(F condition) {
    const auto deadline = std::chrono::steady_clock::now() + 3s;
    while (!condition() && std::chrono::steady_clock::now() < deadline) std::this_thread::sleep_for(2ms);
    CHECK(condition());
}

int main() {
    const std::string name = "virtualdap_capture_test_" + std::to_string(getpid());
    const int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    CHECK(server >= 0);
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path + 1, name.data(), name.size());
    CHECK(bind(server, reinterpret_cast<sockaddr*>(&address),
               offsetof(sockaddr_un, sun_path) + name.size() + 1) == 0);
    CHECK(listen(server, 1) == 0);
    std::atomic<bool> connected{false}, playing{false};
    std::atomic<unsigned> audio_bytes{0}, pauses{0}, flushes{0}, stops{0};
    std::atomic<bool> muted{false};
    std::vector<uint8_t> received;
    std::thread receiver([&] {
        const int client = accept4(server, nullptr, nullptr, SOCK_CLOEXEC);
        CHECK(client >= 0);
        timeval timeout{3, 0};
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        std::array<uint8_t, kHandshakeBytes> handshake{};
        CHECK(read_exact(client, handshake.data(), handshake.size()));
        CHECK(u32(handshake.data()) == kMagic);
        CHECK(handshake[4] == kControlledProtocolVersion);
        connected = true;
        uint64_t frames = 0, last_sequence = 0;
        std::array<uint8_t, kMessageHeaderBytes> header{};
        while (read_exact(client, header.data(), header.size())) {
            const uint32_t size = u32(header.data() + 4);
            const uint64_t sequence = u64(header.data() + 8);
            CHECK(sequence > last_sequence && size <= kMaximumPayloadBytes);
            last_sequence = sequence;
            std::vector<uint8_t> payload(size);
            CHECK(read_exact(client, payload.data(), size));
            switch (static_cast<MessageType>(header[0])) {
                case MessageType::kAudio:
                    CHECK(playing && size % 4 == 0);
                    received.insert(received.end(), payload.begin(), payload.end());
                    frames += size / 4;
                    audio_bytes += size;
                    break;
                case MessageType::kControl:
                    CHECK(size == 4);
                    switch (static_cast<PlaybackControl>(u32(payload.data()))) {
                        case PlaybackControl::kPlay: playing = true; break;
                        case PlaybackControl::kPause: playing = false; ++pauses; break;
                        case PlaybackControl::kFlush: frames = 0; ++flushes; break;
                        case PlaybackControl::kStop: frames = 0; playing = false; ++stops; break;
                        case PlaybackControl::kVolume: CHECK(false); break;
                    }
                    break;
                case MessageType::kVolume:
                    CHECK(size == 8);
                    CHECK(u32(payload.data()) == 0 && u32(payload.data() + 4) == 0);
                    muted = true;
                    break;
                case MessageType::kPing: CHECK(size == 0); break;
                default: CHECK(false);
            }
            std::array<uint8_t, kPositionAckBytes> ack{};
            put_u32_le(ack.data(), kAckMagic);
            put_u16_le(ack.data() + 4, kControlledProtocolVersion);
            put_u64_le(ack.data() + 8, sequence);
            put_u64_le(ack.data() + 16, frames);
            put_u64_le(ack.data() + 24, 1000000000 + sequence * 1000000);
            CHECK(send(client, ack.data(), ack.size(), MSG_NOSIGNAL) == ssize_t(ack.size()));
        }
        close(client);
    });
    PcmConfig config;
    auto stream = CapturedPcmStream::create(config, 16, name);
    const std::vector<uint8_t> first(64, 0x45), discarded(32, 0x77), last(16, 0x23);
    CHECK(stream->write(first.data(), first.size(), false) == 64);
    CHECK(stream->write(last.data(), 4, false) == 0);
    CHECK(stream->write(last.data(), 3, false) == -2);
    CHECK(!connected); // Prebuffering neither opens nor plays the remote sink.
    stream->control(PlaybackControl::kPlay);
    await([&] { return audio_bytes == 64 && stream->position().source_frames == 16; });
    stream->set_volume(0, 0);
    await([&] { return muted.load(); });
    stream->control(PlaybackControl::kPause);
    await([&] { return pauses == 1; });
    CHECK(stream->write(discarded.data(), discarded.size(), false) == 32);
    stream->control(PlaybackControl::kFlush);
    await([&] { return flushes == 1 && stream->position().source_frames == 0; });
    stream->control(PlaybackControl::kPlay);
    CHECK(stream->write(last.data(), last.size(), true) == 16);
    stream->control(PlaybackControl::kStop);
    await([&] { return stops == 1 && stream->position().source_frames == 0; });
    CHECK(audio_bytes == 80 && !stream->failed());
    std::weak_ptr<CapturedPcmStream> lifetime = stream;
    stream->close();
    stream.reset();
    receiver.join();
    await([&] { return lifetime.expired(); });
    close(server);
    std::vector<uint8_t> expected(first);
    expected.insert(expected.end(), last.begin(), last.end());
    CHECK(received == expected);
    std::puts("Captured PCM: bounded prebuffer, exact bytes, pause/flush/resume/drain, position and release OK");
}
