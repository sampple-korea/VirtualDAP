#ifndef VIRTUAL_DAP_BRIDGE_TRANSPORT_H
#define VIRTUAL_DAP_BRIDGE_TRANSPORT_H

#include <stdint.h>

#include <mutex>
#include <string>

#include "VirtualDapProtocol.h"

namespace virtualdap {

class BridgeTransport {
  public:
    explicit BridgeTransport(std::string socket_name = kDefaultSocketName);
    ~BridgeTransport();

    BridgeTransport(const BridgeTransport&) = delete;
    BridgeTransport& operator=(const BridgeTransport&) = delete;

    bool write(const PcmConfig& config, const void* pcm, size_t byte_count);
    void note_dropped(size_t byte_count);
    void disconnect();

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
    void disconnect_locked();

    const std::string socket_name_;
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
