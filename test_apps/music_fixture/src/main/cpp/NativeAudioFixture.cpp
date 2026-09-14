#include <aaudio/AAudio.h>
#include <jni.h>
#include <time.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <string>

namespace {

constexpr int32_t kSampleRate = 88200;
constexpr int32_t kChannels = 2;
constexpr int32_t kCallbackFrames = 441;
constexpr int64_t kTargetFrames = kSampleRate;
constexpr int32_t kBlockingRate = 96000;
constexpr int32_t kBlockingChunkFrames = 480;

struct CallbackState {
    std::atomic<int64_t> generated_frames{0};
    std::atomic<aaudio_result_t> callback_control_result{AAUDIO_ERROR_UNAVAILABLE};
};

int64_t monotonic_ns() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return int64_t(now.tv_sec) * 1'000'000'000 + now.tv_nsec;
}

void keep_stream_observable() {
    // The host instrumentation polls every 100 ms. Keep a fully validated stream connected long
    // enough to observe the final frame count even when an emulator consumes its buffer instantly.
    timespec delay{0, 750'000'000};
    nanosleep(&delay, nullptr);
}

aaudio_data_callback_result_t render(AAudioStream* stream, void* user, void* audio_data,
                                     int32_t frames) {
    auto* state = static_cast<CallbackState*>(user);
    auto* output = static_cast<int16_t*>(audio_data);
    const int64_t start = state->generated_frames.load(std::memory_order_relaxed);
    if (start == 0) {
        state->callback_control_result.store(
            AAudioStream_requestPause(stream), std::memory_order_relaxed);
    }
    for (int32_t frame = 0; frame < frames; ++frame) {
        const int64_t absolute = start + frame;
        const double phase = 2.0 * 3.14159265358979323846 * 659.25 * absolute / kSampleRate;
        const int16_t sample = absolute < kTargetFrames
            ? static_cast<int16_t>(std::sin(phase) * 1600.0) : 0;
        for (int32_t channel = 0; channel < kChannels; ++channel) {
            output[frame * kChannels + channel] = sample;
        }
    }
    const int64_t completed = state->generated_frames.fetch_add(frames, std::memory_order_relaxed) + frames;
    return completed >= kTargetFrames ? AAUDIO_CALLBACK_RESULT_STOP : AAUDIO_CALLBACK_RESULT_CONTINUE;
}

std::string result_text(aaudio_result_t result) {
    return std::string(AAudio_convertResultToText(result)) + " (" + std::to_string(result) + ")";
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_virtualdap_fixture_music_MusicFixtureActivity_playAaudioNative(JNIEnv* env, jclass) {
    AAudioStreamBuilder* builder = nullptr;
    AAudioStream* stream = nullptr;
    CallbackState callback_state;
    std::string status;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        status = "AAudio builder: " + result_text(result);
    } else {
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSampleRate(builder, kSampleRate);
        AAudioStreamBuilder_setChannelCount(builder, kChannels);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
        AAudioStreamBuilder_setFramesPerDataCallback(builder, kCallbackFrames);
        AAudioStreamBuilder_setDataCallback(builder, render, &callback_state);
        result = AAudioStreamBuilder_openStream(builder, &stream);
        if (result != AAUDIO_OK) {
            status = "AAudio open: " + result_text(result);
        } else if (AAudioStream_getSampleRate(stream) != kSampleRate ||
                   AAudioStream_getChannelCount(stream) != kChannels ||
                   AAudioStream_getFormat(stream) != AAUDIO_FORMAT_PCM_I16) {
            status = "AAudio negotiated an unexpected PCM format";
        } else {
            result = AAudioStream_requestStart(stream);
            if (result != AAUDIO_OK) {
                status = "AAudio start: " + result_text(result);
            } else if (AAudioStream_requestStart(stream) != AAUDIO_ERROR_INVALID_STATE) {
                status = "AAudio duplicate start was not rejected";
            } else {
                const int64_t deadline = monotonic_ns() + 8'000'000'000LL;
                while ((AAudioStream_getFramesWritten(stream) < kTargetFrames ||
                        AAudioStream_getFramesRead(stream) < kTargetFrames) &&
                       monotonic_ns() < deadline) {
                    timespec delay{0, 10'000'000};
                    nanosleep(&delay, nullptr);
                }
                const int64_t written = AAudioStream_getFramesWritten(stream);
                const int64_t presented = AAudioStream_getFramesRead(stream);
                if (callback_state.callback_control_result.load(std::memory_order_relaxed) !=
                    AAUDIO_ERROR_INVALID_STATE) {
                    status = "AAudio callback allowed a colliding pause";
                } else if (written < kTargetFrames || presented < kTargetFrames) {
                    status = "AAudio timeout: written=" + std::to_string(written) +
                        ", presented=" + std::to_string(presented);
                } else {
                    status = "AAudio callback finished: " + std::to_string(presented) + " frames";
                    keep_stream_observable();
                }
                AAudioStream_requestStop(stream);
            }
        }
    }
    if (stream) AAudioStream_close(stream);
    if (builder) AAudioStreamBuilder_delete(builder);
    return env->NewStringUTF(status.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_virtualdap_fixture_music_MusicFixtureActivity_playAaudioBlockingNative(JNIEnv* env, jclass) {
    AAudioStreamBuilder* builder = nullptr;
    AAudioStream* stream = nullptr;
    std::string status;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        status = "AAudio builder: " + result_text(result);
    } else {
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSampleRate(builder, kBlockingRate);
        AAudioStreamBuilder_setChannelCount(builder, kChannels);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
        result = AAudioStreamBuilder_openStream(builder, &stream);
        if (result != AAUDIO_OK) {
            status = "AAudio blocking open: " + result_text(result);
        } else if (AAudioStream_getSampleRate(stream) != kBlockingRate ||
                   AAudioStream_getChannelCount(stream) != kChannels ||
                   AAudioStream_getFormat(stream) != AAUDIO_FORMAT_PCM_FLOAT) {
            status = "AAudio blocking negotiated an unexpected PCM format";
        } else if ((result = AAudioStream_requestPause(stream)) != AAUDIO_OK ||
                   AAudioStream_getState(stream) != AAUDIO_STREAM_STATE_PAUSED ||
                   AAudioStream_requestPause(stream) != AAUDIO_OK) {
            status = "AAudio blocking initial pause: " + result_text(result);
        } else if ((result = AAudioStream_requestFlush(stream)) != AAUDIO_OK ||
                   AAudioStream_getState(stream) != AAUDIO_STREAM_STATE_FLUSHED) {
            status = "AAudio blocking initial flush: " + result_text(result);
        } else if ((result = AAudioStream_requestStart(stream)) != AAUDIO_OK) {
            status = "AAudio blocking start: " + result_text(result);
        } else if (AAudioStream_requestStart(stream) != AAUDIO_ERROR_INVALID_STATE) {
            status = "AAudio blocking duplicate start was not rejected";
        } else {
            float samples[kBlockingChunkFrames * kChannels];
            int64_t submitted = 0;
            const int64_t deadline = monotonic_ns() + 8'000'000'000LL;
            while (submitted < kBlockingRate && monotonic_ns() < deadline) {
                const int32_t requested = static_cast<int32_t>(
                    std::min<int64_t>(kBlockingChunkFrames, kBlockingRate - submitted));
                for (int32_t frame = 0; frame < requested; ++frame) {
                    const float sample = static_cast<float>(
                        std::sin(2.0 * 3.14159265358979323846 * 997.0 * (submitted + frame) /
                                 kBlockingRate) * 0.04);
                    samples[frame * 2] = sample;
                    samples[frame * 2 + 1] = sample;
                }
                const aaudio_result_t written = AAudioStream_write(
                    stream, samples, requested, 100'000'000);
                if (written < 0) {
                    status = "AAudio blocking write: " + result_text(written);
                    break;
                }
                submitted += written;
                if (submitted >= kBlockingRate / 2 &&
                    submitted - written < kBlockingRate / 2) {
                    if (AAudioStream_requestPause(stream) != AAUDIO_OK) {
                        status = "AAudio blocking pause failed";
                        break;
                    }
                    aaudio_stream_state_t next = AAUDIO_STREAM_STATE_UNKNOWN;
                    if (AAudioStream_waitForStateChange(
                            stream, AAUDIO_STREAM_STATE_STARTED, &next, 100'000'000) != AAUDIO_OK ||
                        next != AAUDIO_STREAM_STATE_PAUSED ||
                        AAudioStream_requestStart(stream) != AAUDIO_OK) {
                        status = "AAudio blocking pause/resume state failed";
                        break;
                    }
                }
            }
            while (status.empty() && AAudioStream_getFramesRead(stream) < kBlockingRate &&
                   monotonic_ns() < deadline) {
                timespec delay{0, 10'000'000};
                nanosleep(&delay, nullptr);
            }
            if (status.empty()) {
                const int64_t written = AAudioStream_getFramesWritten(stream);
                const int64_t presented = AAudioStream_getFramesRead(stream);
                int64_t timestamp_frame = 0, timestamp_ns = 0;
                const aaudio_result_t timestamp = AAudioStream_getTimestamp(
                    stream, CLOCK_MONOTONIC, &timestamp_frame, &timestamp_ns);
                if (written < kBlockingRate || presented < kBlockingRate ||
                    timestamp != AAUDIO_OK || timestamp_frame <= 0 || timestamp_ns <= 0) {
                    status = "AAudio blocking timeout: written=" + std::to_string(written) +
                        ", presented=" + std::to_string(presented) +
                        ", timestamp=" + result_text(timestamp);
                } else {
                    status = "AAudio blocking finished: " + std::to_string(presented) + " frames";
                    keep_stream_observable();
                }
            }
            AAudioStream_requestStop(stream);
        }
    }
    if (stream) AAudioStream_close(stream);
    if (builder) AAudioStreamBuilder_delete(builder);
    return env->NewStringUTF(status.c_str());
}
