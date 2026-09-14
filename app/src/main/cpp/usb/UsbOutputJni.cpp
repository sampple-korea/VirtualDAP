#include "UsbIsoOutput.h"
#include <jni.h>
#include <unordered_map>

namespace {
using virtualdap::usb::IsoOutput;
std::mutex handles_mutex;
std::unordered_map<jlong, std::shared_ptr<IsoOutput>> handles;
jlong next_handle = 1;
void error(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::shared_ptr<IsoOutput> lookup(JNIEnv* env, jlong handle) {
    std::lock_guard<std::mutex> lock(handles_mutex);
    auto found = handles.find(handle);
    if (found == handles.end()) {
        error(env, "USB stream handle is closed");
        return nullptr;
    }
    return found->second;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_virtualdap_host_audio_usb_NativeUsbOutput_openNative(
    JNIEnv* env, jclass, jint fd, jint config, jint interface_number, jint alternate, jint endpoint, jint feedback) {
    try {
        auto output = IsoOutput::open(fd, {config, interface_number, alternate, endpoint, feedback});
        std::lock_guard<std::mutex> lock(handles_mutex);
        if (handles.size() >= 4 || next_handle == INT64_MAX) throw std::runtime_error("Too many USB audio outputs");
        const auto handle = next_handle++;
        handles.emplace(handle, output);
        return handle;
    } catch (const std::exception& failure) { error(env, failure.what()); return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_virtualdap_host_audio_usb_NativeUsbOutput_controlNative(
    JNIEnv* env, jclass, jlong handle, jint type, jint request, jint value, jint index, jbyteArray data) {
    auto output = lookup(env, handle);
    if (!output) return -1;
    if (!data || type < 0 || type > 255 || request < 0 || request > 255 ||
        value < 0 || value > 65535 || index < 0 || index > 65535 || env->GetArrayLength(data) > 8192) {
        error(env, "Invalid USB control request");
        return -1;
    }
    const jsize length = env->GetArrayLength(data);
    std::vector<unsigned char> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return -1;
    const int result = output->control(type, request, value, index, bytes.data(), length);
    if (result >= 0 && result <= length && (type & 0x80)) {
        env->SetByteArrayRegion(data, 0, result, reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_virtualdap_host_audio_usb_NativeUsbOutput_startNative(
    JNIEnv* env, jclass, jlong handle, jint rate, jint frame_bytes) {
    auto output = lookup(env, handle);
    if (!output) return;
    try { output->start(static_cast<uint32_t>(rate), static_cast<uint32_t>(frame_bytes)); }
    catch (const std::exception& failure) { error(env, failure.what()); }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_virtualdap_host_audio_usb_NativeUsbOutput_writeNative(
    JNIEnv* env, jclass, jlong handle, jbyteArray data) {
    auto output = lookup(env, handle);
    if (!output) return -1;
    if (!data || env->GetArrayLength(data) > 1024 * 1024) { error(env, "USB write exceeds the limit"); return -1; }
    const jsize size = env->GetArrayLength(data);
    if (size == 0) return 0;
    try {
        std::vector<uint8_t> bytes(static_cast<size_t>(size));
        env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return -1;
        return output->write(bytes.data(), bytes.size());
    } catch (const std::exception& failure) { error(env, failure.what()); return -1; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_virtualdap_host_audio_usb_NativeUsbOutput_commandNative(
    JNIEnv* env, jclass, jlong handle, jint command) {
    auto output = lookup(env, handle);
    if (!output) return;
    try {
        switch (command) {
            case 1: output->resume(); break;
            case 2: if (!output->pause()) throw std::runtime_error("USB pause failed or timed out"); break;
            case 3: output->flush(); break;
            case 4: if (!output->drain()) throw std::runtime_error("USB drain failed or timed out"); break;
            default: throw std::invalid_argument("Invalid USB stream command");
        }
    } catch (const std::exception& failure) { error(env, failure.what()); }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_virtualdap_host_audio_usb_NativeUsbOutput_statisticsNative(JNIEnv* env, jclass, jlong handle) {
    auto output = lookup(env, handle);
    if (!output) return nullptr;
    const auto stats = output->statistics();
    const jlong values[] = {static_cast<jlong>(stats.accepted_frames), static_cast<jlong>(stats.submitted_frames),
        static_cast<jlong>(stats.completed_frames), static_cast<jlong>(stats.underruns),
        static_cast<jlong>(stats.feedback_packets), static_cast<jlong>(stats.invalid_feedback_packets),
        static_cast<jlong>(stats.queued_bytes), stats.error, output->speed()};
    auto result = env->NewLongArray(9);
    if (result) env->SetLongArrayRegion(result, 0, 9, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_virtualdap_host_audio_usb_NativeUsbOutput_closeNative(JNIEnv*, jclass, jlong handle) {
    std::shared_ptr<IsoOutput> output;
    {
        std::lock_guard<std::mutex> lock(handles_mutex);
        auto found = handles.find(handle);
        if (found == handles.end()) return;
        output = std::move(found->second);
        handles.erase(found);
    }
    output->close();
}
