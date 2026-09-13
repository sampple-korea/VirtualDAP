#ifndef VIRTUAL_DAP_BRIDGE_TRANSPORT_H
#define VIRTUAL_DAP_BRIDGE_TRANSPORT_H

#include <stdint.h>

#include <mutex>
#include <array>
#include <string>

#include "VirtualDapProtocol.h"

namespace virtualdap {

bool parse_vsock_endpoint(const std::string& endpoint, uint32_t* cid, uint32_t* port);
bool parse_bridge_token(const std::string& token_hex, std::array<uint8_t, 32>* token);

class BridgeTransport {
  public:
    explicit BridgeTransport(std::string endpoint = kDefaultSocketName,
                             std::string bridge_token_hex = "",
                             uint16_t protocol_version = kProtocolVersion);
    ~BridgeTransport();

    BridgeTransport(const BridgeTransport&) = delete;
    BridgeTransport& operator=(const BridgeTransport&) = delete;

    bool write(const PcmConfig& config, const void* pcm, size_t byte_count);
    void note_dropped(size_t byte_count);
    void disconnect();
    bool control(const PcmConfig& config, PlaybackControl command);
    bool query_position();
    bool set_volume(const PcmConfig& config, float left, float right);
    PlaybackPosition position() const;

    uint64_t frames_written() const;
    uint64_t dropped_bytes() const;
    uint64_t reconnect_count() const;

  private:
    bool connect_locked(const PcmConfig& config);
    bool send_handshake_locked(const PcmConfig& config);
    bool send_format_locked(const PcmConfig& config);
    bool send_stats_locked();
    bool send_message_locked(MessageType type, const void* payload, uint32_t payload_size,
                             uint64_t sequence);
    bool send_all_locked(const void* data, size_t byte_count);
    bool receive_ack_locked(uint64_t expected_sequence);
    bool receive_all_locked(void* data, size_t byte_count);
    void disconnect_locked();

    const std::string endpoint_;
    const uint16_t protocol_version_;
    PlaybackPosition position_{};
    std::array<uint8_t, 32> bridge_token_{};
    bool has_bridge_token_ = false;
    mutable std::mutex mutex_;
    int socket_fd_ = -1;
    PcmConfig current_config_{};
    bool has_config_ = false;
    uint64_t sequence_ = 0;
    uint64_t frames_written_ = 0;
    uint64_t dropped_bytes_ = 0;
    uint64_t reconnect_count_ = 0;
    int64_t last_connect_attempt_ns_ = 0;
};

}  // namespace virtualdap

#endif  // VIRTUAL_DAP_BRIDGE_TRANSPORT_H
