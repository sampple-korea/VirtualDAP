#include "AAudioCapture.h"

#include "CapturedPcmStream.h"
#include "Dobby/dobby.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <time.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <unordered_map>
#include <vector>

namespace virtualdap {
namespace {

constexpr char kLogTag[] = "VirtualDAP-AAudio";
constexpr int64_t kCallbackWriteSliceNs = 5'000'000;

std::atomic<bool> enabled{false};
std::atomic<uint64_t> next_epoch{uint64_t{1} << 32};
std::mutex registry_mutex;
struct CapturedAAudioStream;
thread_local CapturedAAudioStream* callback_context = nullptr;

struct BuilderState {
    aaudio_direction_t direction = AAUDIO_DIRECTION_OUTPUT;
    aaudio_format_t requested_format = AAUDIO_FORMAT_UNSPECIFIED;
    AAudioStream_dataCallback data_callback = nullptr;
    void* data_user = nullptr;
    AAudioStream_errorCallback error_callback = nullptr;
    void* error_user = nullptr;
    int32_t requested_callback_frames = AAUDIO_UNSPECIFIED;
};

struct CapturedAAudioStream : std::enable_shared_from_this<CapturedAAudioStream> {
    AAudioStream* stream = nullptr;
    std::shared_ptr<CapturedPcmStream> capture;
    AAudioStream_dataCallback data_callback = nullptr;
    void* data_user = nullptr;
    AAudioStream_errorCallback error_callback = nullptr;
    void* error_user = nullptr;
    int32_t frame_size = 0;
    int32_t callback_frames = 0;
    std::vector<uint8_t> callback_buffer;
    std::mutex mutex;
    std::condition_variable changed;
    std::thread callback_worker;
    aaudio_stream_state_t state = AAUDIO_STREAM_STATE_OPEN;
    int64_t frames_written = 0;
    uint64_t position_base = 0;
    uint64_t last_raw_position = 0;
    uint64_t presented_frames = 0;
    bool callback_stopped = false;
    bool closing = false;
    bool error_reported = false;

    void launch_callback_worker() {
        if (!data_callback) return;
        auto self = shared_from_this();
        callback_worker = std::thread([self] { self->callback_loop(); });
    }

    aaudio_stream_state_t get_state() {
        std::lock_guard<std::mutex> lock(mutex);
        return state;
    }

    int64_t get_frames_written() {
        std::lock_guard<std::mutex> lock(mutex);
        return frames_written;
    }

    PlaybackPosition presented_position() {
        const PlaybackPosition observed = capture->position();
        std::lock_guard<std::mutex> lock(mutex);
        if (observed.source_frames < last_raw_position) position_base += last_raw_position;
        last_raw_position = observed.source_frames;
        presented_frames = std::max(presented_frames, position_base + observed.source_frames);
        presented_frames = std::min<uint64_t>(presented_frames, std::max<int64_t>(0, frames_written));
        return {presented_frames, observed.monotonic_ns};
    }

    void report_error(aaudio_result_t error) {
        AAudioStream_errorCallback callback = nullptr;
        void* user = nullptr;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (error_reported || closing) return;
            error_reported = true;
            callback_stopped = true;
            state = AAUDIO_STREAM_STATE_DISCONNECTED;
            callback = error_callback;
            user = error_user;
        }
        changed.notify_all();
        if (callback) {
            callback_context = this;
            callback(stream, user, error);
            callback_context = nullptr;
        }
    }

    void callback_loop() {
        pthread_setname_np(pthread_self(), "Vdap-AAudio");
        for (;;) {
            {
                std::unique_lock<std::mutex> lock(mutex);
                changed.wait(lock, [&] {
                    return closing || (state == AAUDIO_STREAM_STATE_STARTED && !callback_stopped);
                });
                if (closing) return;
            }

            std::fill(callback_buffer.begin(), callback_buffer.end(), 0);
            callback_context = this;
            const aaudio_data_callback_result_t callback_result =
                data_callback(stream, data_user, callback_buffer.data(), callback_frames);
            callback_context = nullptr;
            size_t offset = 0;
            bool discarded = false;
            while (offset < callback_buffer.size()) {
                {
                    std::unique_lock<std::mutex> lock(mutex);
                    changed.wait(lock, [&] {
                        return closing || state == AAUDIO_STREAM_STATE_STARTED ||
                            state == AAUDIO_STREAM_STATE_FLUSHED ||
                            state == AAUDIO_STREAM_STATE_STOPPED ||
                            state == AAUDIO_STREAM_STATE_CLOSING ||
                            state == AAUDIO_STREAM_STATE_CLOSED ||
                            state == AAUDIO_STREAM_STATE_DISCONNECTED;
                    });
                    if (closing || state == AAUDIO_STREAM_STATE_FLUSHED ||
                        state == AAUDIO_STREAM_STATE_STOPPED ||
                        state == AAUDIO_STREAM_STATE_CLOSING || state == AAUDIO_STREAM_STATE_CLOSED ||
                        state == AAUDIO_STREAM_STATE_DISCONNECTED) {
                        discarded = true;
                        break;
                    }
                }
                const int written = capture->write_timed(
                    callback_buffer.data() + offset, callback_buffer.size() - offset,
                    kCallbackWriteSliceNs);
                if (written < 0) {
                    report_error(AAUDIO_ERROR_DISCONNECTED);
                    return;
                }
                offset += static_cast<size_t>(written);
            }
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (!discarded) frames_written += static_cast<int64_t>(offset / frame_size);
                if (callback_result != AAUDIO_CALLBACK_RESULT_CONTINUE) callback_stopped = true;
            }
        }
    }

    void shutdown() {
        bool first = false;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!closing) {
                closing = true;
                state = AAUDIO_STREAM_STATE_CLOSING;
                first = true;
            }
        }
        changed.notify_all();
        if (first) capture->close();
        if (callback_worker.joinable()) {
            if (callback_worker.get_id() == std::this_thread::get_id()) callback_worker.detach();
            else callback_worker.join();
        }
        {
            std::lock_guard<std::mutex> lock(mutex);
            state = AAUDIO_STREAM_STATE_CLOSED;
        }
        changed.notify_all();
    }
};

std::unordered_map<AAudioStreamBuilder*, BuilderState> builders;
std::unordered_map<AAudioStream*, std::shared_ptr<CapturedAAudioStream>> streams;

decltype(&AAudio_createStreamBuilder) original_create_builder = nullptr;
decltype(&AAudioStreamBuilder_setDataCallback) original_set_data_callback = nullptr;
decltype(&AAudioStreamBuilder_setErrorCallback) original_set_error_callback = nullptr;
decltype(&AAudioStreamBuilder_setFramesPerDataCallback) original_set_callback_frames = nullptr;
decltype(&AAudioStreamBuilder_setDirection) original_set_direction = nullptr;
decltype(&AAudioStreamBuilder_setFormat) original_set_format = nullptr;
decltype(&AAudioStreamBuilder_openStream) original_open_stream = nullptr;
decltype(&AAudioStreamBuilder_delete) original_delete_builder = nullptr;
decltype(&AAudioStream_requestStart) original_request_start = nullptr;
decltype(&AAudioStream_requestPause) original_request_pause = nullptr;
decltype(&AAudioStream_requestFlush) original_request_flush = nullptr;
decltype(&AAudioStream_requestStop) original_request_stop = nullptr;
decltype(&AAudioStream_write) original_write = nullptr;
decltype(&AAudioStream_getState) original_get_state = nullptr;
decltype(&AAudioStream_waitForStateChange) original_wait_state = nullptr;
decltype(&AAudioStream_getFramesWritten) original_get_frames_written = nullptr;
decltype(&AAudioStream_getFramesRead) original_get_frames_read = nullptr;
decltype(&AAudioStream_getFramesPerDataCallback) original_get_callback_frames = nullptr;
decltype(&AAudioStream_getTimestamp) original_get_timestamp = nullptr;
decltype(&AAudioStream_release) original_release = nullptr;
decltype(&AAudioStream_close) original_close = nullptr;

decltype(&AAudioStream_getSampleRate) native_get_sample_rate = nullptr;
decltype(&AAudioStream_getChannelCount) native_get_channel_count = nullptr;
decltype(&AAudioStream_getFormat) native_get_format = nullptr;
decltype(&AAudioStream_getDirection) native_get_direction = nullptr;
decltype(&AAudioStream_getBufferCapacityInFrames) native_get_capacity = nullptr;
decltype(&AAudioStream_getFramesPerBurst) native_get_frames_per_burst = nullptr;

std::shared_ptr<CapturedAAudioStream> find_stream(AAudioStream* stream) {
    if (callback_context && callback_context->stream == stream) {
        return callback_context->shared_from_this();
    }
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto found = streams.find(stream);
    return found == streams.end() ? nullptr : found->second;
}

bool supported_pcm(aaudio_format_t format) {
    return format == AAUDIO_FORMAT_PCM_I16 || format == AAUDIO_FORMAT_PCM_FLOAT ||
        format == AAUDIO_FORMAT_PCM_I24_PACKED || format == AAUDIO_FORMAT_PCM_I32;
}

bool to_pcm_encoding(aaudio_format_t format, Encoding* encoding, uint32_t* bytes) {
    switch (format) {
        case AAUDIO_FORMAT_PCM_I16: *encoding = Encoding::kPcm16; *bytes = 2; return true;
        case AAUDIO_FORMAT_PCM_FLOAT: *encoding = Encoding::kPcmFloat; *bytes = 4; return true;
        case AAUDIO_FORMAT_PCM_I24_PACKED: *encoding = Encoding::kPcm24Packed; *bytes = 3; return true;
        case AAUDIO_FORMAT_PCM_I32: *encoding = Encoding::kPcm32; *bytes = 4; return true;
        default: return false;
    }
}

void restore_callbacks(AAudioStreamBuilder* builder, const BuilderState& state) {
    original_set_data_callback(builder, state.data_callback, state.data_user);
    original_set_error_callback(builder, state.error_callback, state.error_user);
}

aaudio_result_t captured_create_builder(AAudioStreamBuilder** builder) {
    const aaudio_result_t result = original_create_builder(builder);
    if (enabled && result == AAUDIO_OK && builder && *builder) {
        try {
            std::lock_guard<std::mutex> lock(registry_mutex);
            builders.emplace(*builder, BuilderState{});
        } catch (...) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "Cannot track builder; leaving this AAudio stream untouched");
        }
    }
    return result;
}

void captured_set_data_callback(AAudioStreamBuilder* builder, AAudioStream_dataCallback callback,
                                void* user) {
    if (!enabled) {
        original_set_data_callback(builder, callback, user);
        return;
    }
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto found = builders.find(builder);
    if (found == builders.end()) {
        original_set_data_callback(builder, callback, user);
        return;
    }
    found->second.data_callback = callback;
    found->second.data_user = user;
    original_set_data_callback(builder, nullptr, nullptr);
}

void captured_set_error_callback(AAudioStreamBuilder* builder, AAudioStream_errorCallback callback,
                                 void* user) {
    if (!enabled) {
        original_set_error_callback(builder, callback, user);
        return;
    }
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto found = builders.find(builder);
    if (found == builders.end()) {
        original_set_error_callback(builder, callback, user);
        return;
    }
    found->second.error_callback = callback;
    found->second.error_user = user;
    original_set_error_callback(builder, nullptr, nullptr);
}

void captured_set_callback_frames(AAudioStreamBuilder* builder, int32_t frames) {
    original_set_callback_frames(builder, frames);
    if (!enabled) return;
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto found = builders.find(builder);
    if (found != builders.end()) found->second.requested_callback_frames = frames;
}

void captured_set_direction(AAudioStreamBuilder* builder, aaudio_direction_t direction) {
    original_set_direction(builder, direction);
    if (!enabled) return;
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto found = builders.find(builder);
    if (found != builders.end()) found->second.direction = direction;
}

void captured_set_format(AAudioStreamBuilder* builder, aaudio_format_t format) {
    original_set_format(builder, format);
    if (!enabled) return;
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto found = builders.find(builder);
    if (found != builders.end()) found->second.requested_format = format;
}

aaudio_result_t captured_open_stream(AAudioStreamBuilder* builder, AAudioStream** output) {
    if (!enabled) return original_open_stream(builder, output);
    BuilderState builder_state;
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        const auto found = builders.find(builder);
        if (found == builders.end()) {
            if (output) *output = nullptr;
            return AAUDIO_ERROR_INVALID_STATE;
        }
        builder_state = found->second;
    }
    const bool candidate = builder_state.direction == AAUDIO_DIRECTION_OUTPUT &&
        (builder_state.requested_format == AAUDIO_FORMAT_UNSPECIFIED ||
         supported_pcm(builder_state.requested_format));
    if (!candidate) {
        restore_callbacks(builder, builder_state);
        if (builder_state.direction == AAUDIO_DIRECTION_INPUT) return original_open_stream(builder, output);
        if (output) *output = nullptr;
        return AAUDIO_ERROR_INVALID_FORMAT;
    }
    original_set_data_callback(builder, nullptr, nullptr);
    original_set_error_callback(builder, nullptr, nullptr);
    const aaudio_result_t result = original_open_stream(builder, output);
    if (result != AAUDIO_OK || !output || !*output) return result;

    AAudioStream* native_stream = *output;
    const int32_t rate = native_get_sample_rate(native_stream);
    const int32_t channels = native_get_channel_count(native_stream);
    const aaudio_format_t format = native_get_format(native_stream);
    const aaudio_direction_t direction = native_get_direction(native_stream);
    Encoding encoding;
    uint32_t sample_bytes = 0;
    if (direction != AAUDIO_DIRECTION_OUTPUT || rate < 8000 || rate > 768000 ||
        channels < 1 || channels > 8 || !to_pcm_encoding(format, &encoding, &sample_bytes)) {
        original_close(native_stream);
        *output = nullptr;
        restore_callbacks(builder, builder_state);
        return AAUDIO_ERROR_INVALID_FORMAT;
    }

    const uint32_t frame_size = sample_bytes * static_cast<uint32_t>(channels);
    const int32_t native_capacity = native_get_capacity(native_stream);
    const size_t maximum_frames = (8 * 1024 * 1024) / frame_size;
    const size_t capacity_frames = std::min(
        std::max<int32_t>(1, native_capacity), static_cast<int32_t>(maximum_frames));
    int32_t callback_frames = original_get_callback_frames(native_stream);
    if (callback_frames <= 0) callback_frames = builder_state.requested_callback_frames;
    if (callback_frames <= 0) callback_frames = native_get_frames_per_burst(native_stream);
    if (callback_frames <= 0) callback_frames = std::max(1, rate / 100);
    if (static_cast<uint64_t>(callback_frames) * frame_size > 8 * 1024 * 1024) {
        original_close(native_stream);
        *output = nullptr;
        return AAUDIO_ERROR_OUT_OF_RANGE;
    }

    try {
        PcmConfig config{
            static_cast<uint32_t>(rate), static_cast<uint16_t>(channels), encoding, frame_size,
            0, next_epoch++,
        };
        auto state = std::make_shared<CapturedAAudioStream>();
        state->stream = native_stream;
        state->data_callback = builder_state.data_callback;
        state->data_user = builder_state.data_user;
        state->error_callback = builder_state.error_callback;
        state->error_user = builder_state.error_user;
        state->frame_size = static_cast<int32_t>(frame_size);
        state->callback_frames = callback_frames;
        if (state->data_callback) {
            state->callback_buffer.resize(static_cast<size_t>(callback_frames) * frame_size);
        }
        state->capture = CapturedPcmStream::create(config, capacity_frames);
        bool inserted = false;
        try {
            std::lock_guard<std::mutex> lock(registry_mutex);
            if (streams.size() >= 32) throw std::runtime_error("Too many captured AAudio streams");
            if (!streams.emplace(native_stream, state).second) {
                throw std::runtime_error("Duplicate captured AAudio stream");
            }
            inserted = true;
        } catch (...) {
            state->shutdown();
            throw;
        }
        try {
            state->launch_callback_worker();
        } catch (...) {
            if (inserted) {
                std::lock_guard<std::mutex> lock(registry_mutex);
                streams.erase(native_stream);
            }
            state->shutdown();
            throw;
        }
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                            "PCM stream %p: %d Hz, %d ch, format %d, %s",
                            native_stream, rate, channels, format,
                            state->data_callback ? "callback" : "blocking write");
        return AAUDIO_OK;
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Cannot capture stream: %s", error.what());
        original_close(native_stream);
        *output = nullptr;
        return AAUDIO_ERROR_NO_MEMORY;
    }
}

aaudio_result_t captured_delete_builder(AAudioStreamBuilder* builder) {
    if (enabled) {
        std::lock_guard<std::mutex> lock(registry_mutex);
        builders.erase(builder);
    }
    return original_delete_builder(builder);
}

aaudio_result_t captured_request_start(AAudioStream* stream) {
    auto state = find_stream(stream);
    if (!state) return original_request_start(stream);
    if (callback_context == state.get()) return AAUDIO_ERROR_INVALID_STATE;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        switch (state->state) {
            case AAUDIO_STREAM_STATE_OPEN:
            case AAUDIO_STREAM_STATE_PAUSED:
            case AAUDIO_STREAM_STATE_STOPPED:
            case AAUDIO_STREAM_STATE_FLUSHED:
                break;
            default:
                return AAUDIO_ERROR_INVALID_STATE;
        }
        state->state = AAUDIO_STREAM_STATE_STARTED;
        state->callback_stopped = false;
    }
    state->capture->control(PlaybackControl::kPlay);
    state->changed.notify_all();
    return AAUDIO_OK;
}

aaudio_result_t captured_request_pause(AAudioStream* stream) {
    auto state = find_stream(stream);
    if (!state) return original_request_pause(stream);
    if (callback_context == state.get()) return AAUDIO_ERROR_INVALID_STATE;
    bool pause_capture = false;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        switch (state->state) {
            case AAUDIO_STREAM_STATE_STARTED:
                pause_capture = true;
                break;
            case AAUDIO_STREAM_STATE_OPEN:
            case AAUDIO_STREAM_STATE_STOPPED:
            case AAUDIO_STREAM_STATE_FLUSHED:
                break;
            case AAUDIO_STREAM_STATE_PAUSED:
                return AAUDIO_OK;
            default:
                return AAUDIO_ERROR_INVALID_STATE;
        }
        state->state = AAUDIO_STREAM_STATE_PAUSED;
    }
    if (pause_capture) state->capture->control(PlaybackControl::kPause);
    state->changed.notify_all();
    return AAUDIO_OK;
}

aaudio_result_t captured_request_flush(AAudioStream* stream) {
    auto state = find_stream(stream);
    if (!state) return original_request_flush(stream);
    if (callback_context == state.get()) return AAUDIO_ERROR_INVALID_STATE;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        if (state->state != AAUDIO_STREAM_STATE_PAUSED) {
            return AAUDIO_ERROR_INVALID_STATE;
        }
        state->state = AAUDIO_STREAM_STATE_FLUSHED;
    }
    state->capture->control(PlaybackControl::kFlush);
    state->changed.notify_all();
    return AAUDIO_OK;
}

aaudio_result_t captured_request_stop(AAudioStream* stream) {
    auto state = find_stream(stream);
    if (!state) return original_request_stop(stream);
    if (callback_context == state.get()) return AAUDIO_ERROR_INVALID_STATE;
    bool stop_capture = false;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        switch (state->state) {
            case AAUDIO_STREAM_STATE_STARTED:
                stop_capture = true;
                break;
            case AAUDIO_STREAM_STATE_OPEN:
            case AAUDIO_STREAM_STATE_PAUSED:
            case AAUDIO_STREAM_STATE_FLUSHED:
                break;
            case AAUDIO_STREAM_STATE_STOPPED:
                return AAUDIO_OK;
            default:
                return AAUDIO_ERROR_INVALID_STATE;
        }
        state->state = AAUDIO_STREAM_STATE_STOPPED;
        state->callback_stopped = true;
    }
    if (stop_capture) state->capture->control(PlaybackControl::kStop);
    state->changed.notify_all();
    return AAUDIO_OK;
}

aaudio_result_t captured_write(AAudioStream* stream, const void* buffer, int32_t frames,
                               int64_t timeout_ns) {
    auto state = find_stream(stream);
    if (!state) return original_write(stream, buffer, frames, timeout_ns);
    if (!buffer && frames != 0) return AAUDIO_ERROR_NULL;
    if (frames < 0 || timeout_ns < 0) return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        if (state->closing || state->data_callback) return AAUDIO_ERROR_INVALID_STATE;
    }
    const uint64_t bytes = static_cast<uint64_t>(frames) * state->frame_size;
    if (bytes > static_cast<uint64_t>(std::numeric_limits<int32_t>::max())) {
        return AAUDIO_ERROR_OUT_OF_RANGE;
    }
    const int written = state->capture->write_timed(
        static_cast<const uint8_t*>(buffer), static_cast<size_t>(bytes), timeout_ns);
    if (written < 0) return written == -2 ? AAUDIO_ERROR_ILLEGAL_ARGUMENT : AAUDIO_ERROR_DISCONNECTED;
    const int32_t written_frames = written / state->frame_size;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        state->frames_written += written_frames;
    }
    return written_frames;
}

aaudio_stream_state_t captured_get_state(AAudioStream* stream) {
    auto state = find_stream(stream);
    return state ? state->get_state() : original_get_state(stream);
}

aaudio_result_t captured_wait_state(AAudioStream* stream, aaudio_stream_state_t input,
                                    aaudio_stream_state_t* next, int64_t timeout_ns) {
    auto state = find_stream(stream);
    if (!state) return original_wait_state(stream, input, next, timeout_ns);
    if (!next) return AAUDIO_ERROR_NULL;
    if (timeout_ns < 0) return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    std::unique_lock<std::mutex> lock(state->mutex);
    if (state->state == input) {
        if (timeout_ns == 0 || !state->changed.wait_for(
                lock, std::chrono::nanoseconds(timeout_ns), [&] { return state->state != input; })) {
            *next = state->state;
            return AAUDIO_ERROR_TIMEOUT;
        }
    }
    *next = state->state;
    return AAUDIO_OK;
}

int64_t captured_get_frames_written(AAudioStream* stream) {
    auto state = find_stream(stream);
    return state ? state->get_frames_written() : original_get_frames_written(stream);
}

int64_t captured_get_frames_read(AAudioStream* stream) {
    auto state = find_stream(stream);
    return state ? static_cast<int64_t>(state->presented_position().source_frames)
                 : original_get_frames_read(stream);
}

int32_t captured_get_callback_frames(AAudioStream* stream) {
    auto state = find_stream(stream);
    return state ? state->callback_frames : original_get_callback_frames(stream);
}

aaudio_result_t captured_get_timestamp(AAudioStream* stream, clockid_t clock_id,
                                       int64_t* frame_position, int64_t* time_ns) {
    auto state = find_stream(stream);
    if (!state) return original_get_timestamp(stream, clock_id, frame_position, time_ns);
    if (!frame_position || !time_ns) return AAUDIO_ERROR_NULL;
    if (clock_id != CLOCK_MONOTONIC && clock_id != CLOCK_BOOTTIME) {
        return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    }
    if (state->get_state() != AAUDIO_STREAM_STATE_STARTED) return AAUDIO_ERROR_INVALID_STATE;
    PlaybackPosition position = state->presented_position();
    if (!position.monotonic_ns) return AAUDIO_ERROR_UNAVAILABLE;
    if (clock_id == CLOCK_BOOTTIME) {
        timespec monotonic{}, boottime{};
        clock_gettime(CLOCK_MONOTONIC, &monotonic);
        clock_gettime(CLOCK_BOOTTIME, &boottime);
        const int64_t mono_now = int64_t(monotonic.tv_sec) * 1'000'000'000 + monotonic.tv_nsec;
        const int64_t boot_now = int64_t(boottime.tv_sec) * 1'000'000'000 + boottime.tv_nsec;
        position.monotonic_ns += boot_now - mono_now;
    }
    *frame_position = static_cast<int64_t>(position.source_frames);
    *time_ns = static_cast<int64_t>(position.monotonic_ns);
    return AAUDIO_OK;
}

aaudio_result_t captured_release(AAudioStream* stream) {
    auto state = find_stream(stream);
    if (!state) return original_release(stream);
    if (callback_context == state.get()) return AAUDIO_ERROR_INVALID_STATE;
    state->shutdown();
    const aaudio_result_t result = original_release(stream);
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        streams.erase(stream);
    }
    return result;
}

aaudio_result_t captured_close(AAudioStream* stream) {
    auto state = find_stream(stream);
    if (!state) return original_close(stream);
    if (callback_context == state.get()) return AAUDIO_ERROR_INVALID_STATE;
    state->shutdown();
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        streams.erase(stream);
    }
    return original_close(stream);
}

template<typename Function>
bool resolve(void* library, const char* name, Function* output) {
    *output = reinterpret_cast<Function>(dlsym(library, name));
    return *output != nullptr;
}

template<typename Function, typename Replacement>
bool hook(void* library, const char* name, Replacement replacement, Function* original) {
    void* target = dlsym(library, name);
    return target && DobbyHook(target, reinterpret_cast<dobby_dummy_func_t>(replacement),
                               reinterpret_cast<dobby_dummy_func_t*>(original)) == 0 &&
        *original != nullptr;
}

} // namespace

bool install_aaudio_capture() {
    static std::mutex install_mutex;
    static bool attempted = false;
    static void* library = nullptr;
    std::lock_guard<std::mutex> lock(install_mutex);
    if (enabled) return true;
    if (attempted) return false;
    attempted = true;
    library = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Cannot load libaaudio.so: %s", dlerror());
        return false;
    }

    bool complete = true;
#define RESOLVE(name, field) complete = resolve(library, name, &field) && complete
    RESOLVE("AAudioStream_getSampleRate", native_get_sample_rate);
    RESOLVE("AAudioStream_getChannelCount", native_get_channel_count);
    RESOLVE("AAudioStream_getFormat", native_get_format);
    RESOLVE("AAudioStream_getDirection", native_get_direction);
    RESOLVE("AAudioStream_getBufferCapacityInFrames", native_get_capacity);
    RESOLVE("AAudioStream_getFramesPerBurst", native_get_frames_per_burst);
#undef RESOLVE
#define HOOK(name, replacement, original) complete = hook(library, name, replacement, &original) && complete
    HOOK("AAudio_createStreamBuilder", captured_create_builder, original_create_builder);
    HOOK("AAudioStreamBuilder_setDataCallback", captured_set_data_callback, original_set_data_callback);
    HOOK("AAudioStreamBuilder_setErrorCallback", captured_set_error_callback, original_set_error_callback);
    HOOK("AAudioStreamBuilder_setFramesPerDataCallback", captured_set_callback_frames, original_set_callback_frames);
    HOOK("AAudioStreamBuilder_setDirection", captured_set_direction, original_set_direction);
    HOOK("AAudioStreamBuilder_setFormat", captured_set_format, original_set_format);
    HOOK("AAudioStreamBuilder_openStream", captured_open_stream, original_open_stream);
    HOOK("AAudioStreamBuilder_delete", captured_delete_builder, original_delete_builder);
    HOOK("AAudioStream_requestStart", captured_request_start, original_request_start);
    HOOK("AAudioStream_requestPause", captured_request_pause, original_request_pause);
    HOOK("AAudioStream_requestFlush", captured_request_flush, original_request_flush);
    HOOK("AAudioStream_requestStop", captured_request_stop, original_request_stop);
    HOOK("AAudioStream_write", captured_write, original_write);
    HOOK("AAudioStream_getState", captured_get_state, original_get_state);
    HOOK("AAudioStream_waitForStateChange", captured_wait_state, original_wait_state);
    HOOK("AAudioStream_getFramesWritten", captured_get_frames_written, original_get_frames_written);
    HOOK("AAudioStream_getFramesRead", captured_get_frames_read, original_get_frames_read);
    HOOK("AAudioStream_getFramesPerDataCallback", captured_get_callback_frames, original_get_callback_frames);
    HOOK("AAudioStream_getTimestamp", captured_get_timestamp, original_get_timestamp);
    HOOK("AAudioStream_release", captured_release, original_release);
    HOOK("AAudioStream_close", captured_close, original_close);
#undef HOOK
    enabled = complete;
    __android_log_print(complete ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kLogTag,
                        "AAudio PCM capture hooks %s", complete ? "ready" : "unavailable");
    return complete;
}

}
