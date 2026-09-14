#include <jni.h>
#include <samplerate.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

struct StateDeleter {
    void operator()(SRC_STATE* state) const { src_delete(state); }
};

struct Resampler {
    std::unique_ptr<SRC_STATE, StateDeleter> state;
    int channels = 0;
    double ratio = 1.0;
    bool finished = false;
    std::mutex mutex;
};

std::mutex resamplers_mutex;
std::unordered_map<jlong, std::shared_ptr<Resampler>> resamplers;
jlong next_resampler_handle = 1;

void throw_state(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

std::shared_ptr<Resampler> find_resampler(jlong handle) {
    std::lock_guard<std::mutex> lock(resamplers_mutex);
    const auto found = resamplers.find(handle);
    return found == resamplers.end() ? nullptr : found->second;
}

bool append_checked(std::vector<float>* destination, const float* source, size_t count) {
    constexpr size_t kMaximumOutputSamples = 16 * 1024 * 1024;
    if (count > kMaximumOutputSamples - destination->size()) return false;
    destination->insert(destination->end(), source, source + count);
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_virtualdap_host_audio_HighQualityPcmResampler_nativeCreate(
    JNIEnv* env, jobject, jint source_rate, jint target_rate, jint channels) {
    if (source_rate < 8000 || source_rate > 6144000 || target_rate < 8000 ||
        target_rate > 6144000 || channels < 1 || channels > 8) {
        throw_state(env, "Invalid high-quality sample-rate conversion format");
        return 0;
    }
    const double ratio = static_cast<double>(target_rate) / source_rate;
    if (src_is_valid_ratio(ratio) == 0) {
        throw_state(env, "libsamplerate rejected the requested conversion ratio");
        return 0;
    }
    int error = 0;
    std::unique_ptr<SRC_STATE, StateDeleter> state(src_new(SRC_SINC_BEST_QUALITY, channels, &error));
    if (!state) {
        throw_state(env, std::string("Could not initialize libsamplerate: ") + src_strerror(error));
        return 0;
    }
    auto resampler = std::make_shared<Resampler>();
    resampler->state = std::move(state);
    resampler->channels = channels;
    resampler->ratio = ratio;
    std::lock_guard<std::mutex> lock(resamplers_mutex);
    const jlong handle = next_resampler_handle++;
    resamplers.emplace(handle, std::move(resampler));
    return handle;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_virtualdap_host_audio_HighQualityPcmResampler_nativeProcess(
    JNIEnv* env, jobject, jlong handle, jfloatArray input, jboolean end_of_input) {
    auto resampler = find_resampler(handle);
    if (!resampler || input == nullptr) {
        throw_state(env, "High-quality PCM resampler is closed or its input is missing");
        return nullptr;
    }
    const jsize sample_count = env->GetArrayLength(input);
    if (sample_count < 0 || sample_count > 1024 * 1024 || sample_count % resampler->channels != 0) {
        throw_state(env, "PCM resampler input is too large or has an incomplete channel frame");
        return nullptr;
    }
    std::vector<float> samples(static_cast<size_t>(sample_count));
    if (sample_count > 0) {
        env->GetFloatArrayRegion(input, 0, sample_count, samples.data());
        if (env->ExceptionCheck()) return nullptr;
    }

    std::lock_guard<std::mutex> state_lock(resampler->mutex);
    if (resampler->finished) {
        if (end_of_input == JNI_TRUE && sample_count == 0) return env->NewFloatArray(0);
        throw_state(env, "High-quality PCM resampler has already finished");
        return nullptr;
    }

    const long total_input_frames = sample_count / resampler->channels;
    long consumed_frames = 0;
    std::vector<float> result;
    const float empty_input = 0.0F;
    bool draining = end_of_input == JNI_TRUE;
    while (consumed_frames < total_input_frames || draining) {
        const long remaining_frames = total_input_frames - consumed_frames;
        const long estimated = static_cast<long>(std::ceil(
            (static_cast<double>(std::max<long>(remaining_frames, 1)) + 512.0) * resampler->ratio));
        const long output_frames = std::clamp<long>(estimated + 512, 512, 65536);
        std::vector<float> output(static_cast<size_t>(output_frames) * resampler->channels);
        SRC_DATA data{};
        data.data_in = remaining_frames > 0
            ? samples.data() + static_cast<size_t>(consumed_frames) * resampler->channels
            : &empty_input;
        data.data_out = output.data();
        data.input_frames = remaining_frames;
        data.output_frames = output_frames;
        data.end_of_input = end_of_input == JNI_TRUE ? 1 : 0;
        data.src_ratio = resampler->ratio;
        const int error = src_process(resampler->state.get(), &data);
        if (error != 0) {
            throw_state(env, std::string("High-quality PCM resampling failed: ") + src_strerror(error));
            return nullptr;
        }
        if (data.input_frames_used < 0 || data.input_frames_used > remaining_frames ||
            data.output_frames_gen < 0 || data.output_frames_gen > output_frames) {
            throw_state(env, "libsamplerate returned invalid progress");
            return nullptr;
        }
        const size_t generated_samples = static_cast<size_t>(data.output_frames_gen) * resampler->channels;
        if (!append_checked(&result, output.data(), generated_samples)) {
            throw_state(env, "High-quality PCM resampler output exceeds the packet limit");
            return nullptr;
        }
        consumed_frames += data.input_frames_used;
        if (consumed_frames == total_input_frames) {
            if (!draining || data.output_frames_gen == 0) draining = false;
        }
        if (data.input_frames_used == 0 && data.output_frames_gen == 0 &&
            consumed_frames < total_input_frames) {
            throw_state(env, "High-quality PCM resampler stopped consuming input");
            return nullptr;
        }
    }
    if (end_of_input == JNI_TRUE) resampler->finished = true;
    if (result.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throw_state(env, "High-quality PCM resampler output is too large for Android");
        return nullptr;
    }
    jfloatArray returned = env->NewFloatArray(static_cast<jsize>(result.size()));
    if (returned != nullptr && !result.empty()) {
        env->SetFloatArrayRegion(returned, 0, static_cast<jsize>(result.size()), result.data());
    }
    return returned;
}

extern "C" JNIEXPORT void JNICALL
Java_com_virtualdap_host_audio_HighQualityPcmResampler_nativeClose(
    JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(resamplers_mutex);
    resamplers.erase(handle);
}
