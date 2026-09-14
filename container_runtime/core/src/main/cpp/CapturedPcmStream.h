#pragma once

#include "BridgeTransport.h"
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

namespace virtualdap {

enum class CapturedDataMode { kStatic, kStream };

/** Bounded producer buffer. Only its worker touches the remote output; callbacks never play twice. */
class CapturedPcmStream {
public:
    static std::shared_ptr<CapturedPcmStream> create(
        PcmConfig config, size_t capacity_frames, std::string endpoint = kDefaultSocketName,
        CapturedDataMode data_mode = CapturedDataMode::kStream);
    ~CapturedPcmStream();
    int write(const uint8_t* data, size_t size, bool blocking);
    int write_timed(const uint8_t* data, size_t size, int64_t timeout_ns);
    void control(PlaybackControl command);
    void set_volume(float left, float right);
    int reload_static();
    int set_static_position(size_t frame);
    int set_static_loop(size_t start_frame, size_t end_frame, int loop_count);
    void close();
    PlaybackPosition position();
    bool failed();
    size_t frame_size() const { return config_.frame_size; }
    size_t maximum_write_bytes() const;
    bool is_static() const { return data_mode_ == CapturedDataMode::kStatic; }

private:
    struct Command { PlaybackControl type; float left = 1, right = 1; };
    CapturedPcmStream(PcmConfig config, size_t capacity_frames, std::string endpoint,
                      CapturedDataMode data_mode);
    bool enqueue_locked(Command command);
    void reset_static_cursor_locked(size_t frame);
    void discard_static_remote_locked();
    std::vector<uint8_t> next_static_packet_locked();
    void run();
    enum class State { kStopped, kPlaying, kPaused, kStopping };
    const PcmConfig config_;
    const size_t capacity_bytes_;
    const size_t packet_bytes_;
    const CapturedDataMode data_mode_;
    BridgeTransport transport_;
    std::mutex mutex_;
    std::condition_variable changed_;
    std::deque<std::vector<uint8_t>> queue_;
    std::deque<Command> commands_;
    std::vector<uint8_t> static_buffer_;
    size_t queued_bytes_ = 0;
    size_t static_cursor_frames_ = 0;
    size_t static_loop_start_frames_ = 0;
    size_t static_loop_end_frames_ = 0;
    int static_loop_count_ = 0;
    int static_loops_remaining_ = 0;
    State state_ = State::kStopped;
    PlaybackPosition position_{};
    uint64_t generation_ = 0;
    bool static_loaded_ = false;
    bool static_exhausted_ = false;
    bool remote_touched_ = false;
    float pending_left_ = 1;
    float pending_right_ = 1;
    bool pending_volume_ = false;
    bool closed_ = false;
    bool failed_ = false;
    std::thread worker_;
};
}
