#include "CapturedPcmStream.h"
#include <algorithm>
#include <chrono>
#include <stdexcept>

namespace virtualdap {

std::shared_ptr<CapturedPcmStream> CapturedPcmStream::create(
    PcmConfig config, size_t capacity_frames, std::string endpoint) {
    if (config.frame_size == 0 || config.frame_size > 32 ||
        config.sample_rate < 8000 || config.sample_rate > 768000 ||
        capacity_frames == 0 || capacity_frames > 8 * 1024 * 1024 / config.frame_size) {
        throw std::invalid_argument("Invalid captured PCM buffer");
    }
    auto stream = std::shared_ptr<CapturedPcmStream>(
        new CapturedPcmStream(config, capacity_frames, std::move(endpoint)));
    stream->worker_ = std::thread([stream] { stream->run(); });
    return stream;
}

CapturedPcmStream::CapturedPcmStream(PcmConfig config, size_t capacity_frames, std::string endpoint)
    : config_(config), capacity_bytes_(capacity_frames * config.frame_size),
      packet_bytes_(std::max(1u, config.sample_rate / 100) * config.frame_size),
      transport_(std::move(endpoint), "", kControlledProtocolVersion) {}

CapturedPcmStream::~CapturedPcmStream() {
    if (worker_.joinable()) {
        if (worker_.get_id() == std::this_thread::get_id()) worker_.detach();
        else worker_.join();
    }
}

int CapturedPcmStream::write(const uint8_t* data, size_t size, bool blocking) {
    if ((!data && size != 0) || size > INT32_MAX || size % config_.frame_size != 0) return -2;
    std::unique_lock<std::mutex> lock(mutex_);
    size_t copied = 0;
    while (copied < size && !closed_ && !failed_) {
        if (queued_bytes_ == capacity_bytes_ || state_ == State::kStopping) {
            if (!blocking) break;
            changed_.wait(lock);
            continue;
        }
        const size_t chunk = std::min({size - copied, capacity_bytes_ - queued_bytes_, packet_bytes_});
        queue_.emplace_back(data + copied, data + copied + chunk);
        copied += chunk;
        queued_bytes_ += chunk;
        changed_.notify_all();
    }
    return copied > 0 || (!closed_ && !failed_) ? static_cast<int>(copied) : -6;
}

void CapturedPcmStream::control(PlaybackControl command) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_ || failed_) return;
    if (commands_.size() >= 64) {
        failed_ = closed_ = true;
        changed_.notify_all();
        return;
    }
    switch (command) {
        case PlaybackControl::kPlay: state_ = State::kPlaying; break;
        case PlaybackControl::kPause: state_ = State::kPaused; break;
        case PlaybackControl::kStop:
            state_ = State::kStopping;
            // Submitted source bytes must drain before STOP; PLAY resumes a paused remote sink.
            commands_.push_back({PlaybackControl::kPlay});
            changed_.notify_all();
            return;
        case PlaybackControl::kFlush:
            if (state_ == State::kPlaying) return; // AudioTrack.flush is a no-op while playing.
            queue_.clear();
            queued_bytes_ = 0;
            ++generation_;
            position_ = {};
            break;
        case PlaybackControl::kVolume: return;
    }
    commands_.push_back({command});
    changed_.notify_all();
}

void CapturedPcmStream::set_volume(float left, float right) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_ || failed_) return;
    if (commands_.size() >= 64) {
        failed_ = closed_ = true;
    } else {
        commands_.push_back({PlaybackControl::kVolume, left, right});
    }
    changed_.notify_all();
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

void CapturedPcmStream::run() {
    bool connected = false;
    for (;;) {
        std::vector<uint8_t> pcm;
        Command command{};
        bool has_command = false, stop = false;
        uint64_t generation;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            changed_.wait_for(lock, std::chrono::milliseconds(10), [&] {
                return closed_ || !commands_.empty() ||
                    ((state_ == State::kPlaying || state_ == State::kStopping) && !queue_.empty()) ||
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
            if (!pcm.empty() && generation == generation_) queued_bytes_ -= pcm.size();
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
