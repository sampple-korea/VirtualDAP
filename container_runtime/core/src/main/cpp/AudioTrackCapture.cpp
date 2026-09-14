#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <unordered_map>
#include <stdexcept>
#include <cmath>
#include "AAudioCapture.h"
#include "OpenSLCapture.h"
#include "JniHook/JniHook.h"
#include "CapturedPcmStream.h"

namespace {
using virtualdap::CapturedPcmStream;
using virtualdap::CapturedDataMode;
using virtualdap::PlaybackControl;
std::atomic<bool> enabled{false};
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<CapturedPcmStream>> streams;
std::unordered_map<jlong, std::pair<float, float>> volumes;
jfieldID native_track, data_mode, encoding;
jmethodID sample_rate, channels, capacity;
std::atomic<uint64_t> next_epoch{1};

jlong key(JNIEnv* env, jobject track) { return env->GetLongField(track, native_track); }

std::shared_ptr<CapturedPcmStream> unsupported(JNIEnv* env) {
    env->ThrowNew(env->FindClass("java/lang/UnsupportedOperationException"),
        "VirtualDAP cannot capture this audio format; ordinary Android output fallback is disabled");
    return nullptr;
}

std::shared_ptr<CapturedPcmStream> stream(JNIEnv* env, jobject track, bool create = true) {
    if (!enabled) return nullptr;
    const jlong id = key(env, track);
    if (id == 0) return nullptr;
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto found = streams.find(id);
    if (found != streams.end()) return found->second;
    if (!create) return nullptr;
    const jint android_data_mode = env->GetIntField(track, data_mode);
    if (android_data_mode != 0 && android_data_mode != 1) return unsupported(env);
    const int android_encoding = env->GetIntField(track, encoding);
    virtualdap::Encoding pcm;
    unsigned bytes;
    switch (android_encoding) {
        case 2: pcm = virtualdap::Encoding::kPcm16; bytes = 2; break;
        case 4: pcm = virtualdap::Encoding::kPcmFloat; bytes = 4; break;
        case 21: pcm = virtualdap::Encoding::kPcm24Packed; bytes = 3; break;
        case 22: pcm = virtualdap::Encoding::kPcm32; bytes = 4; break;
        default: return unsupported(env);
    }
    const jint rate = env->CallIntMethod(track, sample_rate);
    const jint channel_count = env->CallIntMethod(track, channels);
    const jint buffer_frames = env->CallIntMethod(track, capacity);
    if (env->ExceptionCheck()) return nullptr;
    if (channel_count < 1 || channel_count > 8 || buffer_frames < 1) return unsupported(env);
    try {
        if (streams.size() >= 32) throw std::runtime_error("Too many simultaneous captured audio tracks");
        virtualdap::PcmConfig config{
            static_cast<uint32_t>(rate), static_cast<uint16_t>(channel_count), pcm,
            bytes * static_cast<unsigned>(channel_count), 0, next_epoch++,
        };
        auto captured = CapturedPcmStream::create(config, std::min(
            static_cast<size_t>(buffer_frames), size_t(8 * 1024 * 1024 / config.frame_size)),
            virtualdap::kDefaultSocketName,
            android_data_mode == 0 ? CapturedDataMode::kStatic : CapturedDataMode::kStream);
        streams.emplace(id, captured);
        auto gain = volumes.find(id);
        if (gain != volumes.end()) captured->set_volume(gain->second.first, gain->second.second);
        __android_log_print(ANDROID_LOG_INFO, "VirtualDAP-Capture",
                            "%s PCM track %lld: %d Hz, %d ch, encoding %d",
                            android_data_mode == 0 ? "Static" : "Streaming",
                            static_cast<long long>(id), rate, channel_count, android_encoding);
        return captured;
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, "VirtualDAP-Capture", "Cannot capture track: %s", error.what());
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}

void remove(JNIEnv* env, jobject track) {
    if (!enabled) return;
    std::shared_ptr<CapturedPcmStream> removed;
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        const auto id = key(env, track);
        volumes.erase(id);
        auto found = streams.find(id);
        if (found == streams.end()) return;
        removed = std::move(found->second);
        streams.erase(found);
    }
    removed->close();
}

#define CONTROL_HOOK(name, command, create) \
    void (*original_##name)(JNIEnv*, jobject); \
    void captured_##name(JNIEnv* env, jobject object) { \
        auto value = stream(env, object, create); \
        if (env->ExceptionCheck()) return; \
        if (value) value->control(PlaybackControl::command); \
        else original_##name(env, object); \
    }
CONTROL_HOOK(start, kPlay, true)
// Non-emitting controls must remain available for un-captured tracks, especially stop() invoked
// by AudioTrack.release(). Rejecting cleanup would leak the original native handle.
CONTROL_HOOK(pause, kPause, false)
CONTROL_HOOK(stop, kStop, false)
CONTROL_HOOK(flush, kFlush, false)

void (*original_volume)(JNIEnv*, jobject, jfloat, jfloat);
void captured_volume(JNIEnv* env, jobject object, jfloat left, jfloat right) {
    // Native state still receives the volume for unsupported paths; intercepted tracks never start it.
    original_volume(env, object, left, right);
    if (!enabled || !std::isfinite(left) || !std::isfinite(right)) return;
    left = std::clamp(left, 0.f, 1.f);
    right = std::clamp(right, 0.f, 1.f);
    std::shared_ptr<CapturedPcmStream> captured;
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        const auto id = key(env, object);
        if (!id) return;
        if (volumes.size() < 256 || volumes.count(id)) volumes[id] = {left, right};
        auto found = streams.find(id);
        if (found != streams.end()) captured = found->second;
    }
    if (captured) captured->set_volume(left, right);
}

void (*original_release)(JNIEnv*, jobject);
void captured_release(JNIEnv* env, jobject object) {
    remove(env, object);
    original_release(env, object);
}
void (*original_finalize)(JNIEnv*, jobject);
void captured_finalize(JNIEnv* env, jobject object) {
    remove(env, object);
    original_finalize(env, object);
}
jint (*original_position)(JNIEnv*, jobject);
jint captured_position(JNIEnv* env, jobject object) {
    auto value = stream(env, object, false);
    return value ? static_cast<jint>(value->position().source_frames) : original_position(env, object);
}
jint (*original_timestamp)(JNIEnv*, jobject, jlongArray);
jint captured_timestamp(JNIEnv* env, jobject object, jlongArray output) {
    auto value = stream(env, object, false);
    if (!value) return original_timestamp(env, object, output);
    if (!output || env->GetArrayLength(output) < 2) return -2;
    const auto observed = value->position();
    if (!observed.monotonic_ns) return -3;
    const jlong pair[] = {static_cast<jlong>(observed.source_frames), static_cast<jlong>(observed.monotonic_ns)};
    env->SetLongArrayRegion(output, 0, 2, pair);
    return env->ExceptionCheck() ? -2 : 0;
}

jint (*original_reload_static)(JNIEnv*, jobject);
jint captured_reload_static(JNIEnv* env, jobject object) {
    auto value = stream(env, object);
    if (env->ExceptionCheck()) return -3;
    return value && value->is_static() ? value->reload_static() : original_reload_static(env, object);
}

jint (*original_set_position)(JNIEnv*, jobject, jint);
jint captured_set_position(JNIEnv* env, jobject object, jint position) {
    auto value = stream(env, object);
    if (env->ExceptionCheck()) return -3;
    if (!value || !value->is_static()) return original_set_position(env, object, position);
    return position < 0 ? -2 : value->set_static_position(static_cast<size_t>(position));
}

jint (*original_set_loop)(JNIEnv*, jobject, jint, jint, jint);
jint captured_set_loop(JNIEnv* env, jobject object, jint start, jint end, jint count) {
    auto value = stream(env, object);
    if (env->ExceptionCheck()) return -3;
    if (!value || !value->is_static()) return original_set_loop(env, object, start, end, count);
    if (start < 0 || end < 0) return -2;
    return value->set_static_loop(static_cast<size_t>(start), static_cast<size_t>(end), count);
}

template<typename Array, typename Element>
jint array_write(JNIEnv* env, jobject object, Array input, jint offset, jint size, jboolean blocking,
                 void (JNIEnv::*read)(Array, jsize, jsize, Element*)) {
    auto value = stream(env, object);
    if (env->ExceptionCheck()) return -3;
    if (!value) return INT32_MIN;
    if (!input || offset < 0 || size < 0 || offset > env->GetArrayLength(input) - size) return -2;
    const size_t element_limit = value->maximum_write_bytes() / sizeof(Element);
    const size_t elements = std::min(static_cast<size_t>(size), element_limit);
    const size_t bytes = elements * sizeof(Element);
    const size_t count = (bytes - bytes % value->frame_size()) / sizeof(Element);
    if (count == 0) return 0;
    std::vector<Element> samples(count);
    (env->*read)(input, offset, static_cast<jsize>(count), samples.data());
    if (env->ExceptionCheck()) return -2;
    const int result = value->write(reinterpret_cast<const uint8_t*>(samples.data()),
                                    count * sizeof(Element), blocking);
    return result < 0 ? result : result / sizeof(Element);
}

#define ARRAY_HOOK(name, type, element, reader) \
    jint (*original_##name)(JNIEnv*, jobject, type, jint, jint, jint, jboolean); \
    jint captured_##name(JNIEnv* env, jobject object, type input, jint offset, jint size, jint format, jboolean blocking) { \
        const jint result = array_write<type, element>(env, object, input, offset, size, blocking, &JNIEnv::reader); \
        return result == INT32_MIN ? original_##name(env, object, input, offset, size, format, blocking) : result; \
    }
ARRAY_HOOK(bytes, jbyteArray, jbyte, GetByteArrayRegion)
ARRAY_HOOK(shorts, jshortArray, jshort, GetShortArrayRegion)
ARRAY_HOOK(floats, jfloatArray, jfloat, GetFloatArrayRegion)

jint (*original_buffer)(JNIEnv*, jobject, jobject, jint, jint, jint, jboolean);
jint captured_buffer(JNIEnv* env, jobject object, jobject buffer, jint offset, jint size, jint format, jboolean blocking) {
    auto value = stream(env, object);
    if (env->ExceptionCheck()) return -3;
    if (!value) return original_buffer(env, object, buffer, offset, size, format, blocking);
    if (!buffer) return -2;
    const auto bytes = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong length = env->GetDirectBufferCapacity(buffer);
    if (!bytes || offset < 0 || size < 0 || static_cast<jlong>(offset) + size > length) return -2;
    size_t byte_count = static_cast<size_t>(size);
    if (value->is_static()) byte_count = std::min(byte_count, value->maximum_write_bytes());
    byte_count -= byte_count % value->frame_size();
    return value->write(bytes + offset, byte_count, blocking);
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_top_niunaijun_blackbox_core_AudioCapture_install(JNIEnv* env, jclass) {
    static std::mutex install_mutex;
    static bool attempted = false;
    std::lock_guard<std::mutex> install_lock(install_mutex);
    if (enabled) return JNI_TRUE;
    if (attempted) return JNI_FALSE; // Never hook an already replaced entry as its own original.
    attempted = true;
    jclass track = env->FindClass("android/media/AudioTrack");
    if (!track) return JNI_FALSE;
    native_track = env->GetFieldID(track, "mNativeTrackInJavaObj", "J");
    data_mode = env->GetFieldID(track, "mDataLoadMode", "I");
    encoding = env->GetFieldID(track, "mAudioFormat", "I");
    sample_rate = env->GetMethodID(track, "getSampleRate", "()I");
    channels = env->GetMethodID(track, "getChannelCount", "()I");
    capacity = env->GetMethodID(track, "getBufferCapacityInFrames", "()I");
    if (env->ExceptionCheck() || !native_track || !data_mode || !encoding || !sample_rate || !channels || !capacity) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    bool complete = true;
#define INSTALL(name, method, signature) \
    JniHook::HookJniFun(env, "android/media/AudioTrack", method, signature, \
                       reinterpret_cast<void*>(captured_##name), reinterpret_cast<void**>(&original_##name), false); \
    complete = complete && original_##name != nullptr;
    INSTALL(start, "native_start", "()V")
    INSTALL(pause, "native_pause", "()V")
    INSTALL(stop, "native_stop", "()V")
    INSTALL(flush, "native_flush", "()V")
    INSTALL(volume, "native_setVolume", "(FF)V")
    INSTALL(release, "native_release", "()V")
    INSTALL(finalize, "native_finalize", "()V")
    INSTALL(position, "native_get_position", "()I")
    INSTALL(timestamp, "native_get_timestamp", "([J)I")
    INSTALL(reload_static, "native_reload_static", "()I")
    INSTALL(set_position, "native_set_position", "(I)I")
    INSTALL(set_loop, "native_set_loop", "(III)I")
    INSTALL(bytes, "native_write_byte", "([BIIIZ)I")
    INSTALL(shorts, "native_write_short", "([SIIIZ)I")
    INSTALL(floats, "native_write_float", "([FIIIZ)I")
    INSTALL(buffer, "native_write_native_bytes", "(Ljava/nio/ByteBuffer;IIIZ)I")
#undef INSTALL
    const bool aaudio_complete = virtualdap::install_aaudio_capture();
    const bool opensl_complete = virtualdap::install_opensl_capture();
    // App startup must fail if any supported output family cannot be intercepted. Partial
    // installation must not leave another family available as an ordinary-output escape hatch.
    enabled = complete && aaudio_complete && opensl_complete;
    __android_log_print(complete ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, "VirtualDAP-Capture",
                        "AudioTrack PCM capture hooks %s", complete ? "ready" : "unavailable");
    if (!aaudio_complete) {
        __android_log_print(ANDROID_LOG_WARN, "VirtualDAP-Capture",
                            "AAudio capture is unavailable; hosted app startup is blocked");
    }
    if (!opensl_complete) {
        __android_log_print(ANDROID_LOG_WARN, "VirtualDAP-Capture",
                            "OpenSL ES capture is unavailable; hosted app startup is blocked");
    }
    return enabled ? JNI_TRUE : JNI_FALSE;
}
