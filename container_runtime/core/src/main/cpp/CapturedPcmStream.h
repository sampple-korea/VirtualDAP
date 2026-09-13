#pragma once

#include "BridgeTransport.h"
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

namespace virtualdap {

/** Bounded producer buffer. Only its worker touches the remote output; callbacks never play twice. */
class CapturedPcmStream : public std::enable_shared_from_this<CapturedPcmStream> {
public:
    static std::shared_ptr<CapturedPcmStream> create(
        PcmConfig config, size_t capacity_frames, std::string endpoint = kDefaultSocketName);
    ~CapturedPcmStream();
    int write(const uint8_t* data, size_t size, bool blocking);
    void control(PlaybackControl command);
    void set_volume(float left, float right);
    void close();
    PlaybackPosition position();
    bool failed();
    size_t frame_size() const { return config_.frame_size; }

private:
    CapturedPcmStream(PcmConfig config, size_t capacity_frames, std::string endpoint);
    void run();
    enum class State { kStopped, kPlaying, kPaused, kStopping };
    const PcmConfig config_;
    const size_t capacity_bytes_;
    const size_t packet_bytes_;
    BridgeTransport transport_;
    std::mutex mutex_;
    std::condition_variable changed_;
    std::deque<std::vector<uint8_t>> queue_;
    struct Command { PlaybackControl type; float left = 1, right = 1; };
    std::deque<Command> commands_;
    size_t queued_bytes_ = 0;
    State state_ = State::kStopped;
    PlaybackPosition position_{};
    uint64_t generation_ = 0;
    bool closed_ = false;
    bool failed_ = false;
    std::thread worker_;
};
}
