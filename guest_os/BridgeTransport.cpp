#include "include/BridgeTransport.h"

#include <errno.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <utility>

#if defined(__linux__)
#include <linux/vm_sockets.h>
#endif

namespace virtualdap {
namespace {

constexpr int64_t kReconnectIntervalNs = 250LL * 1000LL * 1000LL;
constexpr suseconds_t kSendTimeoutMicros = 500 * 1000;
constexpr suseconds_t kReceiveTimeoutMicros = 2 * 1000 * 1000;
constexpr int kSocketBufferBytes = 32 * 1024;

int64_t monotonic_time_ns() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<int64_t>(now.tv_sec) * 1000000000LL + now.tv_nsec;
}

uint32_t get_u32_le(const uint8_t* input) {
    return static_cast<uint32_t>(input[0]) | (static_cast<uint32_t>(input[1]) << 8u) |
           (static_cast<uint32_t>(input[2]) << 16u) | (static_cast<uint32_t>(input[3]) << 24u);
}

uint64_t get_u64_le(const uint8_t* input) {
    return static_cast<uint64_t>(get_u32_le(input)) |
           (static_cast<uint64_t>(get_u32_le(input + 4)) << 32u);
}

}  // namespace

bool parse_vsock_endpoint(const std::string& endpoint, uint32_t* cid, uint32_t* port) {
    constexpr char kPrefix[] = "vsock:";
    if (cid == nullptr || port == nullptr || endpoint.rfind(kPrefix, 0) != 0) return false;
    const size_t separator = endpoint.find(':', sizeof(kPrefix) - 1);
    if (separator == std::string::npos || separator + 1 >= endpoint.size()) return false;
    const std::string cid_text = endpoint.substr(sizeof(kPrefix) - 1, separator - (sizeof(kPrefix) - 1));
    const std::string port_text = endpoint.substr(separator + 1);
    if (cid_text.empty() || port_text.empty() ||
        cid_text.find_first_not_of("0123456789") != std::string::npos ||
        port_text.find_first_not_of("0123456789") != std::string::npos) {
        return false;
    }
    errno = 0;
    char* end = nullptr;
    const unsigned long parsed_cid = strtoul(cid_text.c_str(), &end, 10);
    if (errno != 0 || end == nullptr || *end != '\0' || parsed_cid > UINT32_MAX) return false;
    errno = 0;
    const unsigned long parsed_port = strtoul(port_text.c_str(), &end, 10);
    if (errno != 0 || end == nullptr || *end != '\0' || parsed_port == 0 ||
        parsed_port > UINT32_MAX) {
        return false;
    }
    *cid = static_cast<uint32_t>(parsed_cid);
    *port = static_cast<uint32_t>(parsed_port);
    return true;
}

bool parse_bridge_token(const std::string& token_hex, std::array<uint8_t, 32>* token) {
    if (token == nullptr || token_hex.size() != token->size() * 2) return false;
    auto nibble = [](char value) -> int {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        return -1;
    };
    for (size_t index = 0; index < token->size(); ++index) {
        const int high = nibble(token_hex[index * 2]);
        const int low = nibble(token_hex[index * 2 + 1]);
        if (high < 0 || low < 0) return false;
        (*token)[index] = static_cast<uint8_t>((high << 4) | low);
    }
    return true;
}

BridgeTransport::BridgeTransport(std::string endpoint, std::string bridge_token_hex,
                                 uint16_t protocol_version)
    : endpoint_(std::move(endpoint)),
      protocol_version_(protocol_version),
      has_bridge_token_(parse_bridge_token(bridge_token_hex, &bridge_token_)) {}

BridgeTransport::~BridgeTransport() { disconnect(); }

bool BridgeTransport::write(const PcmConfig& config, const void* pcm, size_t byte_count) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (pcm == nullptr || config.frame_size == 0 || byte_count % config.frame_size != 0) {
        dropped_bytes_ += byte_count;
        return false;
    }
    if (socket_fd_ < 0 && !connect_locked(config)) {
        dropped_bytes_ += byte_count;
        return false;
    }
    if (!has_config_ || current_config_ != config) {
        if (!send_format_locked(config)) {
            dropped_bytes_ += byte_count;
            disconnect_locked();
            return false;
        }
        current_config_ = config;
        has_config_ = true;
    }

    const auto* bytes = static_cast<const uint8_t*>(pcm);
    size_t sent = 0;
    const size_t maximum_chunk = kMaximumPayloadBytes - (kMaximumPayloadBytes % config.frame_size);
    while (sent < byte_count) {
        const uint32_t chunk = static_cast<uint32_t>(std::min(byte_count - sent, maximum_chunk));
        const uint64_t packet_sequence = ++sequence_;
        if (!send_message_locked(MessageType::kAudio, bytes + sent, chunk, packet_sequence)) {
            dropped_bytes_ += byte_count - sent;
            disconnect_locked();
            return false;
        }
        if (!receive_ack_locked(packet_sequence)) {
            dropped_bytes_ += byte_count - sent;
            disconnect_locked();
            return false;
        }
        sent += chunk;
        frames_written_ += chunk / config.frame_size;
        if ((packet_sequence & 0x3fu) == 0 && !send_stats_locked()) {
            disconnect_locked();
            return false;
        }
    }
    return true;
}

bool BridgeTransport::control(const PcmConfig& config, PlaybackControl command) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (protocol_version_ != kControlledProtocolVersion) return false;
    if (command == PlaybackControl::kVolume) return false;
    if (socket_fd_ < 0 && !connect_locked(config)) return false;
    std::array<uint8_t, 4> payload{};
    put_u32_le(payload.data(), static_cast<uint32_t>(command));
    const uint64_t sequence = ++sequence_;
    if (!send_message_locked(MessageType::kControl, payload.data(), payload.size(), sequence) ||
        !receive_ack_locked(sequence)) {
        disconnect_locked();
        return false;
    }
    return true;
}

bool BridgeTransport::set_volume(const PcmConfig& config, float left, float right) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (protocol_version_ != kControlledProtocolVersion ||
        !std::isfinite(left) || !std::isfinite(right) || left < 0 || left > 1 || right < 0 || right > 1) return false;
    if (socket_fd_ < 0 && !connect_locked(config)) return false;
    uint32_t left_bits, right_bits;
    memcpy(&left_bits, &left, sizeof(left));
    memcpy(&right_bits, &right, sizeof(right));
    std::array<uint8_t, 8> payload{};
    put_u32_le(payload.data(), left_bits);
    put_u32_le(payload.data() + 4, right_bits);
    const uint64_t sequence = ++sequence_;
    if (!send_message_locked(MessageType::kVolume, payload.data(), payload.size(), sequence) ||
        !receive_ack_locked(sequence)) {
        disconnect_locked();
        return false;
    }
    return true;
}

bool BridgeTransport::query_position() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (protocol_version_ != kControlledProtocolVersion || socket_fd_ < 0) return false;
    const uint64_t sequence = ++sequence_;
    if (!send_message_locked(MessageType::kPing, nullptr, 0, sequence) ||
        !receive_ack_locked(sequence)) {
        disconnect_locked();
        return false;
    }
    return true;
}

PlaybackPosition BridgeTransport::position() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return position_;
}

void BridgeTransport::note_dropped(size_t byte_count) {
    std::lock_guard<std::mutex> lock(mutex_);
    dropped_bytes_ += byte_count;
}

void BridgeTransport::disconnect() {
    std::lock_guard<std::mutex> lock(mutex_);
    disconnect_locked();
}

uint64_t BridgeTransport::frames_written() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return frames_written_;
}

uint64_t BridgeTransport::dropped_bytes() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return dropped_bytes_;
}

uint64_t BridgeTransport::reconnect_count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return reconnect_count_;
}

bool BridgeTransport::connect_locked(const PcmConfig& config) {
    if (protocol_version_ != kProtocolVersion && protocol_version_ != kControlledProtocolVersion) return false;
    const int64_t now = monotonic_time_ns();
    if (now - last_connect_attempt_ns_ < kReconnectIntervalNs) return false;
    last_connect_attempt_ns_ = now;
    if (endpoint_.empty()) return false;
    uint32_t vsock_cid = 0;
    uint32_t vsock_port = 0;
    const bool use_vsock = parse_vsock_endpoint(endpoint_, &vsock_cid, &vsock_port);
    if (!use_vsock && endpoint_.size() >= sizeof(sockaddr_un::sun_path)) return false;
    if (use_vsock && !has_bridge_token_) return false;

    const int fd = socket(use_vsock ? AF_VSOCK : AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return false;
    const int send_buffer = kSocketBufferBytes;
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &send_buffer, sizeof(send_buffer));
    const timeval timeout{0, kSendTimeoutMicros};
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    const timeval receive_timeout{static_cast<time_t>(
                                      protocol_version_ == kControlledProtocolVersion ? 5 :
                                      kReceiveTimeoutMicros / 1000000),
                                  static_cast<suseconds_t>(kReceiveTimeoutMicros % 1000000)};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &receive_timeout, sizeof(receive_timeout));

    int connect_result = -1;
    if (use_vsock) {
#if defined(__linux__)
        sockaddr_vm address{};
        address.svm_family = AF_VSOCK;
        address.svm_cid = vsock_cid;
        address.svm_port = vsock_port;
        connect_result = connect(fd, reinterpret_cast<const sockaddr*>(&address), sizeof(address));
#endif
    } else {
        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        address.sun_path[0] = '\0';
        memcpy(address.sun_path + 1, endpoint_.data(), endpoint_.size());
        const socklen_t address_size = static_cast<socklen_t>(
            offsetof(sockaddr_un, sun_path) + 1 + endpoint_.size());
        connect_result = connect(fd, reinterpret_cast<const sockaddr*>(&address), address_size);
    }
    if (connect_result != 0) {
        close(fd);
        return false;
    }

    socket_fd_ = fd;
    has_config_ = false;
    ++reconnect_count_;
    if (use_vsock && !send_all_locked(bridge_token_.data(), bridge_token_.size())) {
        disconnect_locked();
        return false;
    }
    if (!send_handshake_locked(config)) {
        disconnect_locked();
        return false;
    }
    current_config_ = config;
    has_config_ = true;
    return true;
}

bool BridgeTransport::send_handshake_locked(const PcmConfig& config) {
    std::array<uint8_t, kHandshakeBytes> handshake{};
    encode_handshake(handshake.data(), config);
    put_u16_le(handshake.data() + 4, protocol_version_);
    return send_all_locked(handshake.data(), handshake.size());
}

bool BridgeTransport::send_format_locked(const PcmConfig& config) {
    std::array<uint8_t, 16> payload{};
    put_u32_le(payload.data(), config.sample_rate);
    put_u16_le(payload.data() + 4, config.channel_count);
    put_u16_le(payload.data() + 6, static_cast<uint16_t>(config.encoding));
    put_u64_le(payload.data() + 8, config.stream_epoch);
    const uint64_t sequence = ++sequence_;
    return send_message_locked(MessageType::kFormat, payload.data(), payload.size(), sequence) &&
        (protocol_version_ != kControlledProtocolVersion || receive_ack_locked(sequence));
}

bool BridgeTransport::send_stats_locked() {
    std::array<uint8_t, 24> payload{};
    put_u64_le(payload.data(), frames_written_);
    put_u64_le(payload.data() + 8, dropped_bytes_);
    put_u64_le(payload.data() + 16, reconnect_count_);
    const uint64_t sequence = ++sequence_;
    return send_message_locked(MessageType::kStats, payload.data(), payload.size(), sequence) &&
        (protocol_version_ != kControlledProtocolVersion || receive_ack_locked(sequence));
}

bool BridgeTransport::send_message_locked(MessageType type, const void* payload,
                                          uint32_t payload_size, uint64_t sequence) {
    std::array<uint8_t, kMessageHeaderBytes> header{};
    encode_message_header(header.data(), type, payload_size, sequence);
    if (!send_all_locked(header.data(), header.size())) return false;
    return payload_size == 0 || send_all_locked(payload, payload_size);
}

bool BridgeTransport::send_all_locked(const void* data, size_t byte_count) {
    const auto* bytes = static_cast<const uint8_t*>(data);
    size_t sent = 0;
    while (sent < byte_count) {
        const ssize_t result = send(socket_fd_, bytes + sent, byte_count - sent, MSG_NOSIGNAL);
        if (result > 0) {
            sent += static_cast<size_t>(result);
            continue;
        }
        if (result < 0 && errno == EINTR) continue;
        return false;
    }
    return true;
}

bool BridgeTransport::receive_ack_locked(uint64_t expected_sequence) {
    std::array<uint8_t, kPositionAckBytes> ack{};
    const size_t size = protocol_version_ == kControlledProtocolVersion ? kPositionAckBytes : kAckBytes;
    if (!receive_all_locked(ack.data(), size)) return false;
    const bool valid = get_u32_le(ack.data()) == kAckMagic &&
           static_cast<uint16_t>(ack[4] | (static_cast<uint16_t>(ack[5]) << 8u)) ==
               protocol_version_ &&
           ack[6] == 0 && ack[7] == 0 && get_u64_le(ack.data() + 8) == expected_sequence;
    if (valid && protocol_version_ == kControlledProtocolVersion) {
        position_ = {get_u64_le(ack.data() + 16), get_u64_le(ack.data() + 24)};
        if (position_.source_frames > INT64_MAX || position_.monotonic_ns > INT64_MAX) return false;
    }
    return valid;
}

bool BridgeTransport::receive_all_locked(void* data, size_t byte_count) {
    auto* bytes = static_cast<uint8_t*>(data);
    size_t received = 0;
    while (received < byte_count) {
        const ssize_t result = recv(socket_fd_, bytes + received, byte_count - received, 0);
        if (result > 0) {
            received += static_cast<size_t>(result);
            continue;
        }
        if (result < 0 && errno == EINTR) continue;
        return false;
    }
    return true;
}

void BridgeTransport::disconnect_locked() {
    if (socket_fd_ >= 0) {
        shutdown(socket_fd_, SHUT_RDWR);
        close(socket_fd_);
    }
    socket_fd_ = -1;
    has_config_ = false;
}

}  // namespace virtualdap
