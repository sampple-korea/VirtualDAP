#include "CapturedPcmStream.h"
#include <algorithm>
#include <chrono>
#include <stdexcept>

namespace virtualdap {

std::shared_ptr<CapturedPcmStream> CapturedPcmStream::create(
    PcmConfig config, size_t capacity_frames, std::string endpoint, CapturedDataMode data_mode) {
    if (config.frame_size == 0 || config.frame_size > 32 ||
        config.sample_rate < 8000 || config.sample_rate > 768000 ||
        capacity_frames == 0 || capacity_frames > 8 * 1024 * 1024 / config.frame_size) {
        throw std::invalid_argument("Invalid captured PCM buffer");
    }
    auto stream = std::shared_ptr<CapturedPcmStream>(
        new CapturedPcmStream(config, capacity_frames, std::move(endpoint), data_mode));
    stream->worker_ = std::thread([instance = stream.get()] { instance->run(); });
    return stream;
}

CapturedPcmStream::CapturedPcmStream(PcmConfig config, size_t capacity_frames, std::string endpoint,
                                     CapturedDataMode data_mode)
    : config_(config), capacity_bytes_(capacity_frames * config.frame_size),
      packet_bytes_(std::max(1u, config.sample_rate / 100) * config.frame_size),
      data_mode_(data_mode),
      transport_(std::move(endpoint), kControlledProtocolVersion),
      static_buffer_(data_mode == CapturedDataMode::kStatic ? capacity_bytes_ : 0, 0) {}

CapturedPcmStream::~CapturedPcmStream() {
    close();
    if (worker_.joinable()) {
        if (worker_.get_id() == std::this_thread::get_id()) worker_.detach();
        else worker_.join();
    }
}

int CapturedPcmStream::write(const uint8_t* data, size_t size, bool blocking) {
    return write_timed(data, size, blocking ? -1 : 0);
}

int CapturedPcmStream::write_timed(const uint8_t* data, size_t size, int64_t timeout_ns) {
    if ((!data && size != 0) || size > INT32_MAX || size % config_.frame_size != 0) return -2;
    if (timeout_ns < -1) return -2;
    std::unique_lock<std::mutex> lock(mutex_);
    if (closed_ || failed_) return -6;
    if (data_mode_ == CapturedDataMode::kStatic) {
        const size_t copied = std::min(size, capacity_bytes_);
        if (copied != 0) {
            std::copy(data, data + copied, static_buffer_.begin());
            if (!static_loaded_) reset_static_cursor_locked(0);
            static_loaded_ = true;
        }
        changed_.notify_all();
        return static_cast<int>(copied);
    }
    size_t copied = 0;
    const auto now = std::chrono::steady_clock::now();
    const auto timeout = std::chrono::nanoseconds(timeout_ns > 0 ? timeout_ns : 0);
    const auto deadline = timeout_ns > 0 && timeout < std::chrono::steady_clock::time_point::max() - now
        ? now + timeout : std::chrono::steady_clock::time_point::max();
    while (copied < size && !closed_ && !failed_) {
        if (queued_bytes_ == capacity_bytes_ || state_ == State::kStopping) {
            if (timeout_ns == 0) break;
            if (timeout_ns < 0) {
                changed_.wait(lock);
            } else if (changed_.wait_until(lock, deadline) == std::cv_status::timeout) {
                break;
            }
            continue;
        }
        const size_t chunk = std::min({size - copied, capacity_bytes_ - queued_bytes_, packet_bytes_});
        try {
            queue_.emplace_back(data + copied, data + copied + chunk);
        } catch (...) {
            failed_ = closed_ = true;
            changed_.notify_all();
            break;
        }
        copied += chunk;
        queued_bytes_ += chunk;
        changed_.notify_all();
    }
    return copied > 0 || (!closed_ && !failed_) ? static_cast<int>(copied) : -6;
}

bool CapturedPcmStream::enqueue_locked(Command command) {
    if (commands_.size() >= 64) {
        failed_ = closed_ = true;
        changed_.notify_all();
        return false;
    }
    try {
        commands_.push_back(command);
    } catch (...) {
        failed_ = closed_ = true;
        changed_.notify_all();
        return false;
    }
    return true;
}

void CapturedPcmStream::reset_static_cursor_locked(size_t frame) {
    static_cursor_frames_ = frame;
    static_loops_remaining_ = static_loop_count_;
    static_exhausted_ = frame >= capacity_bytes_ / config_.frame_size;
    position_ = {};
}

void CapturedPcmStream::discard_static_remote_locked() {
    ++generation_;
    commands_.erase(std::remove_if(commands_.begin(), commands_.end(), [](const Command& command) {
        return command.type != PlaybackControl::kVolume;
    }), commands_.end());
    if (remote_touched_) {
        enqueue_locked({PlaybackControl::kPause});
        enqueue_locked({PlaybackControl::kFlush});
    }
}

void CapturedPcmStream::control(PlaybackControl command) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_ || failed_) return;
    switch (command) {
        case PlaybackControl::kPlay:
            state_ = State::kPlaying;
            if (!remote_touched_ && pending_volume_) {
                enqueue_locked({PlaybackControl::kVolume, pending_left_, pending_right_});
            }
            remote_touched_ = true;
            break;
        case PlaybackControl::kPause:
            state_ = State::kPaused;
            if (!remote_touched_) {
                changed_.notify_all();
                return;
            }
            break;
        case PlaybackControl::kStop:
            if (data_mode_ == CapturedDataMode::kStatic) {
                state_ = State::kStopped;
                reset_static_cursor_locked(0);
                discard_static_remote_locked();
                changed_.notify_all();
                return;
            }
            if (!remote_touched_) {
                state_ = State::kStopped;
                changed_.notify_all();
                return;
            }
            state_ = State::kStopping;
            // Submitted source bytes must drain before STOP; PLAY resumes a paused remote sink.
            enqueue_locked({PlaybackControl::kPlay});
            changed_.notify_all();
            return;
        case PlaybackControl::kFlush:
            if (data_mode_ == CapturedDataMode::kStatic) return; // Android documents this as a no-op.
            if (state_ == State::kPlaying) return; // AudioTrack.flush is a no-op while playing.
            queue_.clear();
            queued_bytes_ = 0;
            ++generation_;
            position_ = {};
            if (!remote_touched_) {
                changed_.notify_all();
                return;
            }
            break;
        case PlaybackControl::kVolume: return;
    }
    enqueue_locked({command});
    changed_.notify_all();
}

void CapturedPcmStream::set_volume(float left, float right) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_ || failed_) return;
    pending_left_ = left;
    pending_right_ = right;
    pending_volume_ = true;
    if (!remote_touched_) return;
    enqueue_locked({PlaybackControl::kVolume, left, right});
    changed_.notify_all();
}

int CapturedPcmStream::reload_static() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (data_mode_ != CapturedDataMode::kStatic || state_ == State::kPlaying) return -3;
    if (closed_ || failed_) return -6;
    reset_static_cursor_locked(0);
    discard_static_remote_locked();
    changed_.notify_all();
    return 0;
}

int CapturedPcmStream::set_static_position(size_t frame) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (data_mode_ != CapturedDataMode::kStatic || state_ == State::kPlaying) return -3;
    if (closed_ || failed_) return -6;
    const size_t capacity_frames = capacity_bytes_ / config_.frame_size;
    if (frame > capacity_frames) return -2;
    if (static_loop_count_ != 0 && frame >= static_loop_end_frames_) {
        static_loop_count_ = 0;
    }
    reset_static_cursor_locked(frame);
    discard_static_remote_locked();
    changed_.notify_all();
    return 0;
}

int CapturedPcmStream::set_static_loop(size_t start_frame, size_t end_frame, int loop_count) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (data_mode_ != CapturedDataMode::kStatic || state_ == State::kPlaying) return -3;
    if (closed_ || failed_) return -6;
    const size_t capacity_frames = capacity_bytes_ / config_.frame_size;
    if (loop_count < -1 || start_frame > capacity_frames || end_frame > capacity_frames ||
        (loop_count != 0 && start_frame >= end_frame)) return -2;
    if (loop_count == 0) {
        static_loop_start_frames_ = static_loop_end_frames_ = 0;
        static_loop_count_ = static_loops_remaining_ = 0;
        return 0;
    }
    static_loop_start_frames_ = start_frame;
    static_loop_end_frames_ = end_frame;
    static_loop_count_ = loop_count;
    static_loops_remaining_ = loop_count;
    return 0;
}

void CapturedPcmStream::close() {
    std::lock_guard<std::mutex> lock(mutex_);
    closed_ = true;
    ++generation_;
    queue_.clear();
    queued_bytes_ = 0;
    changed_.notify_all();
}

PlaybackPosition CapturedPcmStream::position() {
    std::lock_guard<std::mutex> lock(mutex_);
    return state_ == State::kStopped ? PlaybackPosition{} : position_;
}

bool CapturedPcmStream::failed() {
    std::lock_guard<std::mutex> lock(mutex_);
    return failed_;
}

size_t CapturedPcmStream::maximum_write_bytes() const {
    return data_mode_ == CapturedDataMode::kStatic ? capacity_bytes_ : 1024 * 1024;
}

std::vector<uint8_t> CapturedPcmStream::next_static_packet_locked() {
    std::vector<uint8_t> packet;
    if (!static_loaded_ || static_exhausted_) return packet;
    const size_t capacity_frames = capacity_bytes_ / config_.frame_size;
    const size_t target_frames = std::max<size_t>(1, packet_bytes_ / config_.frame_size);
    packet.reserve(target_frames * config_.frame_size);
    size_t generated_frames = 0;
    while (generated_frames < target_frames && !static_exhausted_) {
        const bool loop_enabled = static_loops_remaining_ != 0 &&
            static_cursor_frames_ < static_loop_end_frames_;
        const size_t boundary = loop_enabled ? static_loop_end_frames_ : capacity_frames;
        if (static_cursor_frames_ >= boundary) {
            if (loop_enabled) {
                static_cursor_frames_ = static_loop_start_frames_;
                if (static_loops_remaining_ > 0) --static_loops_remaining_;
                continue;
            }
            static_exhausted_ = true;
            break;
        }
        const size_t frames = std::min(target_frames - generated_frames,
                                       boundary - static_cursor_frames_);
        const auto begin = static_buffer_.begin() + static_cursor_frames_ * config_.frame_size;
        packet.insert(packet.end(), begin, begin + frames * config_.frame_size);
        static_cursor_frames_ += frames;
        generated_frames += frames;
        if (static_cursor_frames_ == static_loop_end_frames_ && static_loops_remaining_ != 0) {
            static_cursor_frames_ = static_loop_start_frames_;
            if (static_loops_remaining_ > 0) --static_loops_remaining_;
        } else if (static_cursor_frames_ >= capacity_frames) {
            static_exhausted_ = true;
        }
    }
    return packet;
}

void CapturedPcmStream::run() {
    bool connected = false;
    for (;;) {
        std::vector<uint8_t> pcm;
        Command command{};
        bool has_command = false, static_pcm = false, stop = false;
        uint64_t generation;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            changed_.wait_for(lock, std::chrono::milliseconds(10), [&] {
                return closed_ || !commands_.empty() ||
                    ((state_ == State::kPlaying || state_ == State::kStopping) && !queue_.empty()) ||
                    (data_mode_ == CapturedDataMode::kStatic && state_ == State::kPlaying &&
                     static_loaded_ && !static_exhausted_) ||
                    state_ == State::kStopping;
            });
            if (closed_) break;
            generation = generation_;
            if (!commands_.empty()) {
                command = commands_.front();
                commands_.pop_front();
                has_command = true;
            } else if ((state_ == State::kPlaying || state_ == State::kStopping) && !queue_.empty()) {
                pcm = std::move(queue_.front());
                queue_.pop_front();
                // Count this in-flight packet against capacity until the host acknowledges it.
            } else if (data_mode_ == CapturedDataMode::kStatic && state_ == State::kPlaying &&
                       static_loaded_ && !static_exhausted_) {
                try {
                    pcm = next_static_packet_locked();
                } catch (...) {
                    failed_ = closed_ = true;
                    changed_.notify_all();
                    break;
                }
                static_pcm = true;
            } else if (state_ == State::kStopping) {
                command = {PlaybackControl::kStop};
                has_command = stop = true;
            } else if (state_ != State::kPlaying || !connected) {
                continue;
            }
        }
        bool success;
        if (has_command) {
            success = command.type == PlaybackControl::kVolume
                ? transport_.set_volume(config_, command.left, command.right)
                : transport_.control(config_, command.type);
            connected = success;
        } else if (!pcm.empty()) {
            success = transport_.write(config_, pcm.data(), pcm.size());
        } else {
            success = transport_.query_position();
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!pcm.empty() && !static_pcm && generation == generation_) queued_bytes_ -= pcm.size();
            if (!success) {
                failed_ = true;
                closed_ = true;
                connected = false;
            } else if (generation == generation_) {
                position_ = transport_.position();
                if (stop && state_ == State::kStopping) state_ = State::kStopped;
            }
            changed_.notify_all();
            if (closed_) break;
        }
    }
    // Release discards queued sound, as native AudioTrack.release does. No user thread waits here.
    if (connected) {
        transport_.control(config_, PlaybackControl::kPause);
        transport_.control(config_, PlaybackControl::kFlush);
    }
    transport_.disconnect();
}
}
