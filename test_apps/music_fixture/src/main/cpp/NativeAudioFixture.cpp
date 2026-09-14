#include <aaudio/AAudio.h>
#include <jni.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <time.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

namespace {

constexpr int32_t kSampleRate = 88200;
constexpr int32_t kChannels = 2;
constexpr int32_t kCallbackFrames = 441;
constexpr int64_t kTargetFrames = kSampleRate;
constexpr int32_t kBlockingRate = 96000;
constexpr int32_t kBlockingChunkFrames = 480;
constexpr int32_t kOpenSlRate = 48000;
constexpr int32_t kOpenSlBufferCount = 4;
constexpr int32_t kOpenSlFramesPerBuffer = kOpenSlRate / kOpenSlBufferCount;

struct CallbackState {
    std::atomic<int64_t> generated_frames{0};
    std::atomic<aaudio_result_t> callback_control_result{AAUDIO_ERROR_UNAVAILABLE};
};

struct OpenSlCallbackState {
    std::atomic<uint32_t> queue_callbacks{0};
    std::atomic<SLuint32> play_events{0};
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

std::string sl_result_text(SLresult result) {
    return std::to_string(static_cast<uint32_t>(result));
}

void SLAPIENTRY open_sl_queue_callback(SLAndroidSimpleBufferQueueItf, void* context) {
    static_cast<OpenSlCallbackState*>(context)->queue_callbacks.fetch_add(
        1, std::memory_order_relaxed);
}

void SLAPIENTRY open_sl_play_callback(SLPlayItf, void* context, SLuint32 event) {
    static_cast<OpenSlCallbackState*>(context)->play_events.fetch_or(
        event, std::memory_order_relaxed);
}

SLresult create_open_sl_engine(SLObjectItf* engine) {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    const SLresult result = slCreateEngine(engine, 0, nullptr, 0, nullptr, nullptr);
#pragma clang diagnostic pop
    return result;
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_virtualdap_fixture_music_MusicFixtureActivity_rejectUnsupportedNative(JNIEnv* env, jclass) {
    AAudioStreamBuilder* builder = nullptr;
    AAudioStream* stream = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
        return env->NewStringUTF("Could not create AAudio rejection-test builder");
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_IEC61937);
    const auto aaudio_result = AAudioStreamBuilder_openStream(builder, &stream);
    const bool aaudio_rejected = aaudio_result == AAUDIO_ERROR_INVALID_FORMAT && stream == nullptr;
    if (stream) AAudioStream_close(stream); // Never start a possibly un-intercepted stream.
    AAudioStreamBuilder_delete(builder);
    if (!aaudio_rejected) return env->NewStringUTF("AAudio compressed output was not explicitly rejected");

    SLObjectItf engine = nullptr;
    SLEngineItf engine_interface = nullptr;
    SLObjectItf output_mix = nullptr;
    SLObjectItf player = nullptr;
    bool rejected = false;
    do {
        if (create_open_sl_engine(&engine) != SL_RESULT_SUCCESS) break;
        if ((*engine)->Realize(engine, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) break;
        if ((*engine)->GetInterface(engine, SL_IID_ENGINE, &engine_interface) != SL_RESULT_SUCCESS) break;
        if ((*engine_interface)->CreateOutputMix(engine_interface, &output_mix, 0, nullptr, nullptr)
                != SL_RESULT_SUCCESS) break;
        if ((*output_mix)->Realize(output_mix, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) break;
        SLDataLocator_AndroidSimpleBufferQueue locator{SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
        SLDataFormat_PCM format{SL_DATAFORMAT_PCM, 2, SL_SAMPLINGRATE_48,
            SL_PCMSAMPLEFORMAT_FIXED_8, SL_PCMSAMPLEFORMAT_FIXED_8,
            SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT, SL_BYTEORDER_LITTLEENDIAN};
        SLDataSource source{&locator, &format};
        SLDataLocator_OutputMix output_locator{SL_DATALOCATOR_OUTPUTMIX, output_mix};
        SLDataSink sink{&output_locator, nullptr};
        const SLresult result = (*engine_interface)->CreateAudioPlayer(
            engine_interface, &player, &source, &sink, 0, nullptr, nullptr);
        rejected = result == SL_RESULT_CONTENT_UNSUPPORTED && player == nullptr;
    } while (false);
    if (player) (*player)->Destroy(player); // Never realize or start a rejected-output probe.
    if (output_mix) (*output_mix)->Destroy(output_mix);
    if (engine) (*engine)->Destroy(engine);
    return env->NewStringUTF(rejected ? "Native unsupported output rejected"
        : "OpenSL unsupported PCM was not explicitly rejected");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_virtualdap_fixture_music_MusicFixtureActivity_playOpenSlNative(JNIEnv* env, jclass) {
    SLObjectItf engine = nullptr;
    SLObjectItf engine_reference = nullptr;
    SLEngineItf engine_interface = nullptr;
    SLObjectItf output_mix = nullptr;
    SLObjectItf player = nullptr;
    SLPlayItf play = nullptr;
    SLAndroidSimpleBufferQueueItf queue = nullptr;
    SLVolumeItf volume = nullptr;
    OpenSlCallbackState callbacks;
    std::string status;

    const auto fail = [&](const char* operation, SLresult result) {
        if (status.empty()) {
            status = std::string("OpenSL ES ") + operation + ": " + sl_result_text(result);
        }
    };
    const auto cleanup = [&] {
        if (player) (*player)->Destroy(player);
        if (output_mix) (*output_mix)->Destroy(output_mix);
        if (engine) (*engine)->Destroy(engine);
    };

    SLresult result = create_open_sl_engine(&engine);
    if (result != SL_RESULT_SUCCESS) fail("engine", result);
    if (status.empty() &&
        (result = create_open_sl_engine(&engine_reference)) != SL_RESULT_SUCCESS) {
        fail("second engine reference", result);
    }
    if (engine_reference) {
        (*engine_reference)->Destroy(engine_reference);
        engine_reference = nullptr;
    }
    if (status.empty() && (result = (*engine)->Realize(engine, SL_BOOLEAN_FALSE)) != SL_RESULT_SUCCESS) {
        fail("engine realize", result);
    }
    if (status.empty() &&
        (result = (*engine)->GetInterface(engine, SL_IID_ENGINE, &engine_interface)) !=
            SL_RESULT_SUCCESS) {
        fail("engine interface", result);
    }
    if (status.empty() &&
        (result = (*engine_interface)->CreateOutputMix(
             engine_interface, &output_mix, 0, nullptr, nullptr)) != SL_RESULT_SUCCESS) {
        fail("output mix", result);
    }
    if (status.empty() &&
        (result = (*output_mix)->Realize(output_mix, SL_BOOLEAN_FALSE)) != SL_RESULT_SUCCESS) {
        fail("output mix realize", result);
    }

    SLDataLocator_AndroidSimpleBufferQueue source_locator{
        SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE,
        static_cast<SLuint32>(kOpenSlBufferCount),
    };
    SLDataFormat_PCM format{
        SL_DATAFORMAT_PCM,
        static_cast<SLuint32>(kChannels),
        SL_SAMPLINGRATE_48,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT,
        SL_BYTEORDER_LITTLEENDIAN,
    };
    SLDataSource source{&source_locator, &format};
    SLDataLocator_OutputMix sink_locator{SL_DATALOCATOR_OUTPUTMIX, output_mix};
    SLDataSink sink{&sink_locator, nullptr};
    const SLInterfaceID interfaces[] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE, SL_IID_VOLUME};
    const SLboolean required[] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
    if (status.empty() &&
        (result = (*engine_interface)->CreateAudioPlayer(
             engine_interface, &player, &source, &sink, 2, interfaces, required)) !=
            SL_RESULT_SUCCESS) {
        fail("player", result);
    }
    if (status.empty() &&
        (result = (*player)->Realize(player, SL_BOOLEAN_FALSE)) != SL_RESULT_SUCCESS) {
        fail("player realize", result);
    }
    if (status.empty() &&
        (result = (*player)->GetInterface(player, SL_IID_PLAY, &play)) != SL_RESULT_SUCCESS) {
        fail("play interface", result);
    }
    if (status.empty() &&
        (result = (*player)->GetInterface(
             player, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &queue)) != SL_RESULT_SUCCESS) {
        fail("buffer queue interface", result);
    }
    if (status.empty() &&
        (result = (*player)->GetInterface(player, SL_IID_VOLUME, &volume)) != SL_RESULT_SUCCESS) {
        fail("volume interface", result);
    }

    if (status.empty()) {
        SLuint32 state = 0;
        SLmillisecond duration = 0;
        SLmillisecond marker = 0;
        if ((result = (*play)->GetPlayState(play, &state)) != SL_RESULT_SUCCESS ||
            state != SL_PLAYSTATE_STOPPED) {
            fail("initial play state", result);
        } else if ((result = (*play)->GetDuration(play, &duration)) != SL_RESULT_SUCCESS ||
                   duration != SL_TIME_UNKNOWN) {
            fail("duration", result);
        } else if ((result = (*play)->GetMarkerPosition(play, &marker)) !=
                   SL_RESULT_PRECONDITIONS_VIOLATED) {
            fail("unset marker contract", result);
        } else if ((result = (*play)->SetPositionUpdatePeriod(play, 0)) !=
                   SL_RESULT_PARAMETER_INVALID) {
            fail("zero position period contract", result);
        } else if ((result = (*play)->SetPositionUpdatePeriod(play, 250)) != SL_RESULT_SUCCESS) {
            fail("position period", result);
        } else if ((result = (*play)->SetMarkerPosition(play, 500)) != SL_RESULT_SUCCESS ||
                   (*play)->GetMarkerPosition(play, &marker) != SL_RESULT_SUCCESS || marker != 500) {
            fail("marker", result);
        } else if ((result = (*play)->SetCallbackEventsMask(
                        play, SL_PLAYEVENT_HEADATEND | 0x80000000u)) !=
                   SL_RESULT_PARAMETER_INVALID) {
            fail("event mask contract", result);
        } else if ((result = (*play)->SetCallbackEventsMask(
                        play, SL_PLAYEVENT_HEADATEND | SL_PLAYEVENT_HEADATMARKER |
                                  SL_PLAYEVENT_HEADATNEWPOS | SL_PLAYEVENT_HEADSTALLED)) !=
                   SL_RESULT_SUCCESS) {
            fail("event mask", result);
        } else if ((result = (*play)->RegisterCallback(
                        play, open_sl_play_callback, &callbacks)) != SL_RESULT_SUCCESS) {
            fail("play callback", result);
        } else if ((result = (*queue)->RegisterCallback(
                        queue, open_sl_queue_callback, &callbacks)) != SL_RESULT_SUCCESS) {
            fail("queue callback", result);
        } else if ((result = (*volume)->SetVolumeLevel(volume, -600)) != SL_RESULT_SUCCESS ||
                   (result = (*volume)->SetMute(volume, SL_BOOLEAN_TRUE)) != SL_RESULT_SUCCESS ||
                   (result = (*volume)->SetMute(volume, SL_BOOLEAN_FALSE)) != SL_RESULT_SUCCESS ||
                   (result = (*volume)->EnableStereoPosition(volume, SL_BOOLEAN_TRUE)) !=
                       SL_RESULT_SUCCESS ||
                   (result = (*volume)->SetStereoPosition(volume, -250)) != SL_RESULT_SUCCESS ||
                   (result = (*volume)->SetStereoPosition(volume, 0)) != SL_RESULT_SUCCESS ||
                   (result = (*volume)->SetVolumeLevel(volume, 0)) != SL_RESULT_SUCCESS) {
            fail("volume controls", result);
        }
    }

    std::array<std::vector<int16_t>, kOpenSlBufferCount> buffers;
    for (int index = 0; index < kOpenSlBufferCount; ++index) {
        auto& buffer = buffers[static_cast<size_t>(index)];
        buffer.resize(kOpenSlFramesPerBuffer * kChannels);
        for (int frame = 0; frame < kOpenSlFramesPerBuffer; ++frame) {
            const int64_t absolute = int64_t(index) * kOpenSlFramesPerBuffer + frame;
            const int16_t sample = static_cast<int16_t>(
                std::sin(2.0 * 3.14159265358979323846 * 733.0 * absolute / kOpenSlRate) * 1400.0);
            buffer[static_cast<size_t>(frame) * 2] = sample;
            buffer[static_cast<size_t>(frame) * 2 + 1] = sample;
        }
    }

    const auto enqueue_all = [&]() -> SLresult {
        for (const auto& buffer : buffers) {
            const SLresult enqueue = (*queue)->Enqueue(
                queue, buffer.data(), static_cast<SLuint32>(buffer.size() * sizeof(int16_t)));
            if (enqueue != SL_RESULT_SUCCESS) return enqueue;
        }
        return SL_RESULT_SUCCESS;
    };
    if (status.empty() && (result = enqueue_all()) != SL_RESULT_SUCCESS) fail("enqueue", result);
    if (status.empty()) {
        const auto& extra = buffers.front();
        result = (*queue)->Enqueue(
            queue, extra.data(), static_cast<SLuint32>(extra.size() * sizeof(int16_t)));
        if (result != SL_RESULT_BUFFER_INSUFFICIENT) fail("queue capacity contract", result);
    }
    if (status.empty() && (result = (*queue)->Clear(queue)) != SL_RESULT_SUCCESS) {
        fail("clear", result);
    }
    if (status.empty()) {
        SLAndroidSimpleBufferQueueState queue_state{};
        result = (*queue)->GetState(queue, &queue_state);
        if (result != SL_RESULT_SUCCESS || queue_state.count != 0 || queue_state.index != 0) {
            fail("cleared queue state", result);
        }
    }
    if (status.empty() && (result = enqueue_all()) != SL_RESULT_SUCCESS) fail("reenqueue", result);
    if (status.empty() &&
        (result = (*play)->SetPlayState(play, SL_PLAYSTATE_PAUSED)) != SL_RESULT_SUCCESS) {
        fail("initial pause", result);
    }
    if (status.empty()) {
        SLuint32 state = 0;
        result = (*play)->GetPlayState(play, &state);
        if (result != SL_RESULT_SUCCESS || state != SL_PLAYSTATE_PAUSED) {
            fail("paused state", result);
        } else {
            result = (*queue)->RegisterCallback(queue, open_sl_queue_callback, &callbacks);
            if (result != SL_RESULT_PRECONDITIONS_VIOLATED) {
                fail("callback registration state contract", result);
            }
        }
    }
    if (status.empty() &&
        (result = (*play)->SetPlayState(play, SL_PLAYSTATE_PLAYING)) != SL_RESULT_SUCCESS) {
        fail("start", result);
    }

    SLmillisecond position = 0;
    SLAndroidSimpleBufferQueueState queue_state{};
    const int64_t deadline = monotonic_ns() + 8'000'000'000LL;
    while (status.empty() && monotonic_ns() < deadline) {
        result = (*play)->GetPosition(play, &position);
        if (result != SL_RESULT_SUCCESS) {
            fail("position", result);
            break;
        }
        result = (*queue)->GetState(queue, &queue_state);
        if (result != SL_RESULT_SUCCESS) {
            fail("queue state", result);
            break;
        }
        if (callbacks.queue_callbacks.load(std::memory_order_relaxed) >= kOpenSlBufferCount &&
            queue_state.count == 0 && queue_state.index == kOpenSlBufferCount &&
            position >= 1000 &&
            (callbacks.play_events.load(std::memory_order_relaxed) & SL_PLAYEVENT_HEADATEND) != 0) {
            break;
        }
        timespec delay{0, 10'000'000};
        nanosleep(&delay, nullptr);
    }
    if (status.empty()) {
        const uint32_t queue_callbacks = callbacks.queue_callbacks.load(std::memory_order_relaxed);
        const SLuint32 play_events = callbacks.play_events.load(std::memory_order_relaxed);
        if (queue_callbacks != kOpenSlBufferCount || queue_state.count != 0 ||
            queue_state.index != kOpenSlBufferCount || position < 1000 ||
            (play_events & SL_PLAYEVENT_HEADATEND) == 0) {
            status = "OpenSL ES timeout: callbacks=" + std::to_string(queue_callbacks) +
                ", count=" + std::to_string(queue_state.count) +
                ", index=" + std::to_string(queue_state.index) +
                ", position=" + std::to_string(position) +
                ", events=" + std::to_string(play_events);
        } else {
            status = "OpenSL ES finished: " + std::to_string(kOpenSlRate) +
                " frames, callbacks=" + std::to_string(queue_callbacks);
            keep_stream_observable();
        }
    }
    if (play) {
        (*play)->ClearMarkerPosition(play);
        (*play)->SetPlayState(play, SL_PLAYSTATE_STOPPED);
    }
    cleanup();
    return env->NewStringUTF(status.c_str());
}
