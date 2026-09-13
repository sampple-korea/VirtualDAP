#include <jni.h>
#include "dsd2pcm.h"

#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace {

struct ChannelDeleter {
    void operator()(dsd2pcm_ctx* context) const { dsd2pcm_destroy(context); }
};

struct Converter {
    std::vector<std::unique_ptr<dsd2pcm_ctx, ChannelDeleter>> channels;
    bool lsb_first = false;
};

std::mutex converters_mutex;
std::unordered_map<jlong, std::unique_ptr<Converter>> converters;
jlong next_handle = 1;
std::once_flag filter_init;

void throw_state(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_virtualdap_host_audio_DsdPcmDecoder_nativeCreate(
    JNIEnv* env, jobject, jint channels, jboolean lsb_first) {
    if (channels < 1 || channels > 8) {
        throw_state(env, "Invalid DSD channel count");
        return 0;
    }
    std::call_once(filter_init, dsd2pcm_precalc);
    auto converter = std::make_unique<Converter>();
    converter->lsb_first = lsb_first == JNI_TRUE;
    for (int channel = 0; channel < channels; ++channel) {
        std::unique_ptr<dsd2pcm_ctx, ChannelDeleter> state(dsd2pcm_init());
        if (!state) {
            throw_state(env, "Could not allocate the DSD filter");
            return 0;
        }
        converter->channels.push_back(std::move(state));
    }
    std::lock_guard<std::mutex> lock(converters_mutex);
    const jlong handle = next_handle++;
    converters.emplace(handle, std::move(converter));
    return handle;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_virtualdap_host_audio_DsdPcmDecoder_nativeConvert(
    JNIEnv* env, jobject, jlong handle, jbyteArray input) {
    std::lock_guard<std::mutex> lock(converters_mutex);
    auto found = converters.find(handle);
    if (found == converters.end() || input == nullptr) {
        throw_state(env, "DSD decoder is closed or its input is missing");
        return nullptr;
    }
    const auto& converter = found->second;
    const size_t channels = converter->channels.size();
    const jsize byte_count = env->GetArrayLength(input);
    if (byte_count > 1024 * 1024 || static_cast<size_t>(byte_count) % channels != 0) {
        throw_state(env, "DSD input is too large or has an incomplete channel frame");
        return nullptr;
    }
    std::vector<jbyte> bytes(static_cast<size_t>(byte_count));
    std::vector<float> pcm(static_cast<size_t>(byte_count));
    if (byte_count > 0) {
        env->GetByteArrayRegion(input, 0, byte_count, bytes.data());
        if (env->ExceptionCheck()) return nullptr;
        for (size_t channel = 0; channel < channels; ++channel) {
            dsd2pcm_translate(
                converter->channels[channel].get(), static_cast<size_t>(byte_count) / channels,
                reinterpret_cast<const unsigned char*>(bytes.data()) + channel,
                static_cast<ptrdiff_t>(channels), converter->lsb_first ? 1 : 0,
                pcm.data() + channel, static_cast<ptrdiff_t>(channels));
        }
    }
    jfloatArray result = env->NewFloatArray(byte_count);
    if (result != nullptr && byte_count > 0) {
        env->SetFloatArrayRegion(result, 0, byte_count, pcm.data());
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_virtualdap_host_audio_DsdPcmDecoder_nativeClose(
    JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(converters_mutex);
    converters.erase(handle);
}
