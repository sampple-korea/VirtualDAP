#include "BridgeTransport.h"

#include <stddef.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <future>
#include <string>
#include <thread>

namespace {

#define CHECK(condition)                                                                            \
    do {                                                                                            \
        if (!(condition)) {                                                                         \
            std::fprintf(stderr, "CHECK failed at %s:%d: %s\n", __FILE__, __LINE__, #condition);   \
            std::abort();                                                                           \
        }                                                                                           \
    } while (false)

uint16_t get_u16(const uint8_t* data) {
    return static_cast<uint16_t>(data[0]) |
           static_cast<uint16_t>(static_cast<uint16_t>(data[1]) << 8u);
}

uint32_t get_u32(const uint8_t* data) {
    return static_cast<uint32_t>(data[0]) | (static_cast<uint32_t>(data[1]) << 8u) |
           (static_cast<uint32_t>(data[2]) << 16u) | (static_cast<uint32_t>(data[3]) << 24u);
}

void send_ack(int fd, uint64_t sequence) {
    std::array<uint8_t, virtualdap::kAckBytes> ack{};
    virtualdap::put_u32_le(ack.data(), virtualdap::kAckMagic);
    virtualdap::put_u16_le(ack.data() + 4, virtualdap::kProtocolVersion);
    virtualdap::put_u64_le(ack.data() + 8, sequence);
    CHECK(send(fd, ack.data(), ack.size(), MSG_NOSIGNAL) == static_cast<ssize_t>(ack.size()));
}

bool read_exact(int fd, void* output, size_t size) {
    auto* bytes = static_cast<uint8_t*>(output);
    size_t received = 0;
    while (received < size) {
        const ssize_t count = recv(fd, bytes + received, size - received, 0);
        if (count <= 0) return false;
        received += static_cast<size_t>(count);
    }
    return true;
}

int create_server(const std::string& name) {
    const int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    CHECK(fd >= 0);
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    memcpy(address.sun_path + 1, name.data(), name.size());
    const auto size = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
    CHECK(bind(fd, reinterpret_cast<const sockaddr*>(&address), size) == 0);
    CHECK(listen(fd, 1) == 0);
    return fd;
}

}  // namespace

int main() {
    uint32_t cid = 0;
    uint32_t port = 0;
    CHECK(virtualdap::parse_vsock_endpoint("vsock:2:45000", &cid, &port));
    CHECK(cid == 2);
    CHECK(port == 45000);
    CHECK(!virtualdap::parse_vsock_endpoint("vsock:2:0", &cid, &port));
    CHECK(!virtualdap::parse_vsock_endpoint("vsock:host:45000", &cid, &port));
    CHECK(!virtualdap::parse_vsock_endpoint("virtualdap_audio_v1", &cid, &port));
    std::array<uint8_t, 32> token{};
    CHECK(virtualdap::parse_bridge_token(std::string(64, 'a'), &token));
    CHECK(token.front() == 0xaa && token.back() == 0xaa);
    CHECK(!virtualdap::parse_bridge_token(std::string(63, 'a'), &token));
    CHECK(!virtualdap::parse_bridge_token(std::string(64, 'x'), &token));

    const std::string socket_name = "virtualdap_test_" + std::to_string(getpid());
    const int server = create_server(socket_name);
    std::promise<void> ready;
    auto ready_future = ready.get_future();
    std::atomic<bool> verified{false};

    std::thread receiver([&] {
        ready.set_value();
        const int client = accept4(server, nullptr, nullptr, SOCK_CLOEXEC);
        CHECK(client >= 0);
        std::array<uint8_t, virtualdap::kHandshakeBytes> handshake{};
        CHECK(read_exact(client, handshake.data(), handshake.size()));
        CHECK(get_u32(handshake.data()) == virtualdap::kMagic);
        CHECK(get_u16(handshake.data() + 4) == virtualdap::kProtocolVersion);
        CHECK(get_u32(handshake.data() + 8) == 96000);
        CHECK(get_u16(handshake.data() + 12) == 2);
        CHECK(get_u16(handshake.data() + 14) == 2);
        CHECK(get_u32(handshake.data() + 16) == 6);

        std::array<uint8_t, virtualdap::kMessageHeaderBytes> header{};
        CHECK(read_exact(client, header.data(), header.size()));
        CHECK(get_u16(header.data()) == static_cast<uint16_t>(virtualdap::MessageType::kAudio));
        CHECK(get_u32(header.data() + 4) == 12);
        std::array<uint8_t, 12> received{};
        CHECK(read_exact(client, received.data(), received.size()));
        for (size_t index = 0; index < received.size(); ++index) {
            CHECK(received[index] == static_cast<uint8_t>(index));
        }
        send_ack(client, 1);
        verified.store(true);
        close(client);
    });

    ready_future.wait();
    virtualdap::BridgeTransport transport(socket_name);
    const virtualdap::PcmConfig config{
        96000, 2, virtualdap::Encoding::kPcm24Packed, 6, 1, 7,
    };
    std::array<uint8_t, 12> pcm{};
    for (size_t index = 0; index < pcm.size(); ++index) pcm[index] = static_cast<uint8_t>(index);
    CHECK(transport.write(config, pcm.data(), pcm.size()));
    receiver.join();
    close(server);
    CHECK(verified.load());
    CHECK(transport.frames_written() == 2);
    CHECK(transport.reconnect_count() == 1);

    CHECK(!transport.write(config, pcm.data(), 11));
    CHECK(transport.dropped_bytes() == 11);
    return 0;
}
