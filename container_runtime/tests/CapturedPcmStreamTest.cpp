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

void test_static_capture() {
    const std::string name = "virtualdap_static_capture_test_" + std::to_string(getpid());
    const int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    CHECK(server >= 0);
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path + 1, name.data(), name.size());
    CHECK(bind(server, reinterpret_cast<sockaddr*>(&address),
               offsetof(sockaddr_un, sun_path) + name.size() + 1) == 0);
    CHECK(listen(server, 1) == 0);
    std::atomic<bool> connected{false}, playing{false};
    std::atomic<unsigned> audio_bytes{0}, pauses{0}, flushes{0}, maximum_packet{0};
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
                case MessageType::kAudio: {
                    CHECK(playing && size % 4 == 0);
                    received.insert(received.end(), payload.begin(), payload.end());
                    frames += size / 4;
                    audio_bytes += size;
                    unsigned observed = maximum_packet;
                    while (observed < size && !maximum_packet.compare_exchange_weak(observed, size)) {}
                    std::this_thread::sleep_for(1ms);
                    break;
                }
                case MessageType::kControl:
                    CHECK(size == 4);
                    switch (static_cast<PlaybackControl>(u32(payload.data()))) {
                        case PlaybackControl::kPlay: playing = true; break;
                        case PlaybackControl::kPause: playing = false; ++pauses; break;
                        case PlaybackControl::kFlush: frames = 0; ++flushes; break;
                        case PlaybackControl::kStop: CHECK(false); break;
                        case PlaybackControl::kVolume: CHECK(false); break;
                    }
                    break;
                case MessageType::kPing: CHECK(size == 0); break;
                case MessageType::kVolume: CHECK(size == 8); break;
                default: CHECK(false);
            }
            std::array<uint8_t, kPositionAckBytes> ack{};
            put_u32_le(ack.data(), kAckMagic);
            put_u16_le(ack.data() + 4, kControlledProtocolVersion);
            put_u64_le(ack.data() + 8, sequence);
            put_u64_le(ack.data() + 16, frames);
            put_u64_le(ack.data() + 24, 2000000000 + sequence * 1000000);
            CHECK(send(client, ack.data(), ack.size(), MSG_NOSIGNAL) == ssize_t(ack.size()));
        }
        close(client);
    });

    PcmConfig config;
    config.sample_rate = 8000;
    auto stream = CapturedPcmStream::create(
        config, 8, name, CapturedDataMode::kStatic);
    std::vector<uint8_t> source(40);
    for (size_t index = 0; index < source.size(); ++index) source[index] = static_cast<uint8_t>(index);
    CHECK(stream->is_static());
    CHECK(stream->maximum_write_bytes() == 32);
    CHECK(stream->write(source.data(), source.size(), false) == 32);
    CHECK(stream->write(source.data(), 3, false) == -2);
    CHECK(!connected);
    CHECK(stream->set_static_loop(2, 5, 2) == 0);
    CHECK(stream->set_static_loop(5, 2, 1) == -2);
    stream->control(PlaybackControl::kPlay);
    await([&] { return audio_bytes == 56 && stream->position().source_frames == 14; });

    std::vector<uint8_t> expected;
    const auto append_frames = [&](size_t begin, size_t end) {
        expected.insert(expected.end(), source.begin() + begin * 4, source.begin() + end * 4);
    };
    append_frames(0, 5);
    append_frames(2, 5);
    append_frames(2, 5);
    append_frames(5, 8);
    CHECK(received == expected);

    stream->control(PlaybackControl::kPause);
    await([&] { return pauses >= 1; });
    CHECK(stream->set_static_position(6) == 0);
    await([&] { return flushes >= 1; });
    stream->control(PlaybackControl::kPlay);
    await([&] { return audio_bytes == 64 && stream->position().source_frames == 2; });
    append_frames(6, 8);
    stream->control(PlaybackControl::kPause);
    await([&] { return pauses >= 3; });

    const std::vector<uint8_t> replacement{0xf0, 0xf1, 0xf2, 0xf3};
    CHECK(stream->write(replacement.data(), replacement.size(), false) == 4);
    CHECK(stream->set_static_loop(0, 0, 0) == 0);
    CHECK(stream->reload_static() == 0);
    await([&] { return flushes >= 2; });
    stream->control(PlaybackControl::kPlay);
    await([&] { return audio_bytes == 96 && stream->position().source_frames == 8; });
    expected.insert(expected.end(), replacement.begin(), replacement.end());
    append_frames(1, 8); // A short static write overwrites from zero and preserves the old tail.
    CHECK(std::equal(expected.begin(), expected.end(), received.begin(), received.begin() + expected.size()));

    stream->control(PlaybackControl::kPause);
    await([&] { return pauses >= 5; });
    CHECK(stream->set_static_loop(0, 1, -1) == 0);
    CHECK(stream->reload_static() == 0);
    await([&] { return flushes >= 3; });
    const unsigned before_infinite = audio_bytes;
    stream->control(PlaybackControl::kPlay);
    await([&] { return audio_bytes >= before_infinite + 320; });
    stream->control(PlaybackControl::kPause);
    await([&] { return pauses >= 7; });
    const unsigned after_pause = audio_bytes;
    std::this_thread::sleep_for(20ms);
    CHECK(audio_bytes == after_pause);
    CHECK(maximum_packet <= 320); // An infinite one-frame loop stays in one bounded 10 ms packet.
    stream->control(PlaybackControl::kStop);
    await([&] { return flushes >= 4 && stream->position().source_frames == 0; });
    CHECK(!stream->failed());
    stream->close();
    stream.reset();
    receiver.join();
    close(server);
    std::puts("Captured static PCM: exact buffer, finite/infinite loops, seek, reload and stop OK");
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
    stream->control(PlaybackControl::kFlush);
    std::this_thread::sleep_for(20ms);
    CHECK(!connected); // A local pre-play flush must not open an otherwise idle output.
    CHECK(stream->write(first.data(), first.size(), false) == 64);
    CHECK(stream->write(last.data(), 4, false) == 0);
    CHECK(stream->write_timed(last.data(), 4, 2'000'000) == 0);
    CHECK(stream->write_timed(last.data(), 4, -2) == -2);
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
    std::weak_ptr<CapturedPcmStream> idle_lifetime;
    {
        auto idle = CapturedPcmStream::create(config, 16, name + "_idle");
        idle_lifetime = idle;
        idle->control(PlaybackControl::kPause);
        idle->control(PlaybackControl::kStop);
        idle->set_volume(0.5f, 0.5f);
        std::this_thread::sleep_for(20ms);
        CHECK(!idle->failed()); // Pre-play controls and gain must not attempt a socket connection.
    }
    CHECK(idle_lifetime.expired()); // Destruction closes an idle worker without a self-reference leak.
    std::puts("Captured PCM: bounded prebuffer, exact bytes, pause/flush/resume/drain, position and release OK");
    test_static_capture();
}
