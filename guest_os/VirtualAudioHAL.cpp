#define LOG_TAG "VirtualDAP-HAL"

#include <errno.h>
#include <fcntl.h>
#include <hardware/audio.h>
#include <hardware/hardware.h>
#include <log/log.h>
#include <math.h>
#include <new>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <mutex>

#include <cutils/properties.h>

#include "include/BridgeTransport.h"

namespace {

constexpr uint32_t kDefaultSampleRate = 48000;
constexpr audio_channel_mask_t kDefaultChannelMask = AUDIO_CHANNEL_OUT_STEREO;
constexpr audio_format_t kDefaultFormat = AUDIO_FORMAT_PCM_16_BIT;
constexpr uint32_t kEstimatedLatencyMs = 40;
constexpr uint32_t kMaximumSampleRate = 384000;
constexpr uint32_t kMinimumSampleRate = 8000;
constexpr uint32_t kDirectFlag = 1u;

struct virtual_stream_out;

struct virtual_audio_device {
    virtual_audio_device(const char* endpoint, const char* bridge_token)
        : transport(endpoint, bridge_token) {}

    audio_hw_device device{};
    std::mutex mutex;
    virtual_stream_out* direct_output = nullptr;
    virtualdap::BridgeTransport transport;
    bool mic_muted = false;
};

struct virtual_stream_out {
    audio_stream_out stream{};
    virtual_audio_device* device = nullptr;
    mutable std::mutex mutex;
    uint32_t sample_rate = kDefaultSampleRate;
    audio_channel_mask_t channel_mask = kDefaultChannelMask;
    audio_format_t format = kDefaultFormat;
    audio_devices_t devices = AUDIO_DEVICE_OUT_SPEAKER;
    audio_output_flags_t flags = AUDIO_OUTPUT_FLAG_NONE;
    uint64_t stream_epoch = 0;
    uint64_t frames_accepted = 0;
    int64_t next_deadline_ns = 0;
    bool standby = true;
};

std::atomic<uint64_t> g_next_stream_epoch{1};

int64_t monotonic_time_ns() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<int64_t>(now.tv_sec) * 1000000000LL + now.tv_nsec;
}

void sleep_until_ns(int64_t deadline_ns) {
    timespec deadline{
        static_cast<time_t>(deadline_ns / 1000000000LL),
        static_cast<long>(deadline_ns % 1000000000LL),
    };
    while (clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &deadline, nullptr) == EINTR) {
    }
}

bool is_supported_format(audio_format_t format) {
    return format == AUDIO_FORMAT_PCM_16_BIT || format == AUDIO_FORMAT_PCM_24_BIT_PACKED ||
           format == AUDIO_FORMAT_PCM_32_BIT || format == AUDIO_FORMAT_PCM_FLOAT;
}

bool is_supported_channel_mask(audio_channel_mask_t mask) {
    const size_t count = audio_channel_count_from_out_mask(mask);
    return count == 1 || count == 2 || count == 6 || count == 8;
}

virtualdap::Encoding to_wire_encoding(audio_format_t format) {
    switch (format) {
        case AUDIO_FORMAT_PCM_24_BIT_PACKED:
            return virtualdap::Encoding::kPcm24Packed;
        case AUDIO_FORMAT_PCM_32_BIT:
            return virtualdap::Encoding::kPcm32;
        case AUDIO_FORMAT_PCM_FLOAT:
            return virtualdap::Encoding::kPcmFloat;
        case AUDIO_FORMAT_PCM_16_BIT:
        default:
            return virtualdap::Encoding::kPcm16;
    }
}

size_t stream_frame_size(const virtual_stream_out* output) {
    return audio_channel_count_from_out_mask(output->channel_mask) *
           audio_bytes_per_sample(output->format);
}

virtualdap::PcmConfig stream_config(const virtual_stream_out* output) {
    return virtualdap::PcmConfig{
        output->sample_rate,
        static_cast<uint16_t>(audio_channel_count_from_out_mask(output->channel_mask)),
        to_wire_encoding(output->format),
        static_cast<uint32_t>(stream_frame_size(output)),
        (output->flags & AUDIO_OUTPUT_FLAG_DIRECT) != 0 ? kDirectFlag : 0u,
        output->stream_epoch,
    };
}

void pace_output(virtual_stream_out* output, uint64_t frames) {
    const int64_t now = monotonic_time_ns();
    if (output->next_deadline_ns == 0 || now - output->next_deadline_ns > 500000000LL) {
        output->next_deadline_ns = now;
    }
    output->next_deadline_ns += static_cast<int64_t>(
        (frames * 1000000000ULL) / std::max(output->sample_rate, 1u));
    if (output->next_deadline_ns > now) sleep_until_ns(output->next_deadline_ns);
}

virtual_stream_out* as_output(const audio_stream* stream) {
    return reinterpret_cast<virtual_stream_out*>(const_cast<audio_stream*>(stream));
}

virtual_stream_out* as_output(audio_stream_out* stream) {
    return reinterpret_cast<virtual_stream_out*>(stream);
}

uint32_t out_get_sample_rate(const audio_stream* stream) {
    return as_output(stream)->sample_rate;
}

int out_set_sample_rate(audio_stream* stream, uint32_t rate) {
    auto* output = as_output(stream);
    if (rate < kMinimumSampleRate || rate > kMaximumSampleRate) return -EINVAL;
    std::lock_guard<std::mutex> lock(output->mutex);
    if (!output->standby) return -ENOSYS;
    output->sample_rate = rate;
    ++output->stream_epoch;
    return 0;
}

size_t out_get_buffer_size(const audio_stream* stream) {
    const auto* output = as_output(stream);
    const size_t frames = std::max<size_t>(output->sample_rate / 100u, 256u);
    return frames * stream_frame_size(output);
}

audio_channel_mask_t out_get_channels(const audio_stream* stream) {
    return as_output(stream)->channel_mask;
}

audio_format_t out_get_format(const audio_stream* stream) {
    return as_output(stream)->format;
}

int out_set_format(audio_stream* stream, audio_format_t format) {
    auto* output = as_output(stream);
    if (!is_supported_format(format)) return -EINVAL;
    std::lock_guard<std::mutex> lock(output->mutex);
    if (!output->standby) return -ENOSYS;
    output->format = format;
    ++output->stream_epoch;
    return 0;
}

int out_standby(audio_stream* stream) {
    auto* output = as_output(stream);
    {
        std::lock_guard<std::mutex> lock(output->mutex);
        output->standby = true;
        output->next_deadline_ns = 0;
    }
    output->device->transport.disconnect();
    return 0;
}

int out_dump(const audio_stream* stream, int fd) {
    const auto* output = as_output(stream);
    dprintf(fd,
            "VirtualDAP output: rate=%u channels=%u format=0x%x direct=%d standby=%d "
            "accepted_frames=%llu\n",
            output->sample_rate, audio_channel_count_from_out_mask(output->channel_mask),
            output->format, (output->flags & AUDIO_OUTPUT_FLAG_DIRECT) != 0, output->standby,
            static_cast<unsigned long long>(output->frames_accepted));
    return 0;
}

audio_devices_t out_get_device(const audio_stream* stream) {
    return as_output(stream)->devices;
}

int out_set_device(audio_stream* stream, audio_devices_t device) {
    auto* output = as_output(stream);
    std::lock_guard<std::mutex> lock(output->mutex);
    output->devices = device;
    return 0;
}

int out_set_parameters(audio_stream* stream, const char* key_values) {
    if (key_values == nullptr) return 0;
    const char* routing = strstr(key_values, "routing=");
    if (routing != nullptr) {
        const auto route = static_cast<audio_devices_t>(strtoul(routing + strlen("routing="), nullptr, 10));
        return out_set_device(stream, route);
    }
    return 0;
}

char* out_get_parameters(const audio_stream*, const char*) { return strdup(""); }

int out_add_audio_effect(const audio_stream*, effect_handle_t) { return 0; }

int out_remove_audio_effect(const audio_stream*, effect_handle_t) { return 0; }

uint32_t out_get_latency(const audio_stream_out*) { return kEstimatedLatencyMs; }

int out_set_volume(audio_stream_out*, float, float) { return -ENOSYS; }

ssize_t out_write(audio_stream_out* stream, const void* buffer, size_t byte_count) {
    auto* output = as_output(stream);
    const size_t frame_size = stream_frame_size(output);
    if (buffer == nullptr || frame_size == 0 || byte_count % frame_size != 0) return -EINVAL;
    const uint64_t frames = byte_count / frame_size;

    std::lock_guard<std::mutex> stream_lock(output->mutex);
    output->standby = false;
    bool selected = false;
    {
        std::lock_guard<std::mutex> device_lock(output->device->mutex);
        selected = output->device->direct_output == nullptr || output->device->direct_output == output;
    }
    if (selected) {
        output->device->transport.write(stream_config(output), buffer, byte_count);
    } else {
        // A direct hi-res stream owns the bridge. Guest UI/notification mixer audio is discarded.
        output->device->transport.note_dropped(byte_count);
    }
    output->frames_accepted += frames;
    pace_output(output, frames);
    return static_cast<ssize_t>(byte_count);
}

int out_get_render_position(const audio_stream_out* stream, uint32_t* frames) {
    if (frames == nullptr) return -EINVAL;
    auto* output = as_output(const_cast<audio_stream_out*>(stream));
    std::lock_guard<std::mutex> lock(output->mutex);
    const uint64_t latency_frames = output->sample_rate * kEstimatedLatencyMs / 1000u;
    const uint64_t presented = output->frames_accepted > latency_frames
                                   ? output->frames_accepted - latency_frames
                                   : 0;
    *frames = static_cast<uint32_t>(presented);
    return 0;
}

int out_get_next_write_timestamp(const audio_stream_out*, int64_t*) { return -EINVAL; }

int out_set_callback(audio_stream_out*, stream_callback_t, void*) { return -ENOSYS; }

int out_pause(audio_stream_out*) { return -ENOSYS; }

int out_resume(audio_stream_out*) { return -ENOSYS; }

int out_drain(audio_stream_out*, audio_drain_type_t) {
    usleep(kEstimatedLatencyMs * 1000u);
    return 0;
}

int out_flush(audio_stream_out* stream) {
    auto* output = as_output(stream);
    std::lock_guard<std::mutex> lock(output->mutex);
    output->next_deadline_ns = 0;
    return 0;
}

int out_get_presentation_position(const audio_stream_out* stream, uint64_t* frames,
                                  timespec* timestamp) {
    if (frames == nullptr || timestamp == nullptr) return -EINVAL;
    auto* output = as_output(const_cast<audio_stream_out*>(stream));
    std::lock_guard<std::mutex> lock(output->mutex);
    const uint64_t latency_frames = output->sample_rate * kEstimatedLatencyMs / 1000u;
    *frames = output->frames_accepted > latency_frames
                  ? output->frames_accepted - latency_frames
                  : 0;
    clock_gettime(CLOCK_MONOTONIC, timestamp);
    return 0;
}

void out_update_source_metadata(audio_stream_out*, const source_metadata*) {}

int device_init_check(const audio_hw_device*) { return 0; }

int device_set_voice_volume(audio_hw_device*, float) { return -ENOSYS; }

int device_set_master_volume(audio_hw_device*, float) { return -ENOSYS; }

int device_get_master_volume(audio_hw_device*, float*) { return -ENOSYS; }

int device_set_mode(audio_hw_device*, audio_mode_t) { return 0; }

int device_set_mic_mute(audio_hw_device* device, bool state) {
    auto* virtual_device = reinterpret_cast<virtual_audio_device*>(device);
    std::lock_guard<std::mutex> lock(virtual_device->mutex);
    virtual_device->mic_muted = state;
    return 0;
}

int device_get_mic_mute(const audio_hw_device* device, bool* state) {
    if (state == nullptr) return -EINVAL;
    auto* virtual_device = reinterpret_cast<virtual_audio_device*>(
        const_cast<audio_hw_device*>(device));
    std::lock_guard<std::mutex> lock(virtual_device->mutex);
    *state = virtual_device->mic_muted;
    return 0;
}

int device_set_parameters(audio_hw_device*, const char*) { return 0; }

char* device_get_parameters(const audio_hw_device*, const char*) { return strdup(""); }

size_t device_get_input_buffer_size(const audio_hw_device*, const audio_config*) { return 0; }

int device_open_output_stream(audio_hw_device* device, audio_io_handle_t,
                              audio_devices_t devices, audio_output_flags_t flags,
                              audio_config* config, audio_stream_out** stream_out,
                              const char*) {
    if (config == nullptr || stream_out == nullptr) return -EINVAL;
    *stream_out = nullptr;
    if ((flags & AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD) != 0) {
        config->format = kDefaultFormat;
        return -EINVAL;
    }

    if (config->sample_rate == 0) config->sample_rate = kDefaultSampleRate;
    if (config->channel_mask == AUDIO_CHANNEL_NONE) config->channel_mask = kDefaultChannelMask;
    if (config->format == AUDIO_FORMAT_DEFAULT) config->format = kDefaultFormat;
    if (config->sample_rate < kMinimumSampleRate || config->sample_rate > kMaximumSampleRate ||
        !is_supported_channel_mask(config->channel_mask) || !is_supported_format(config->format)) {
        config->sample_rate = kDefaultSampleRate;
        config->channel_mask = kDefaultChannelMask;
        config->format = kDefaultFormat;
        return -EINVAL;
    }

    auto* output = new (std::nothrow) virtual_stream_out();
    if (output == nullptr) return -ENOMEM;
    output->device = reinterpret_cast<virtual_audio_device*>(device);
    output->sample_rate = config->sample_rate;
    output->channel_mask = config->channel_mask;
    output->format = config->format;
    output->devices = devices;
    output->flags = flags;
    output->stream_epoch = g_next_stream_epoch.fetch_add(1);

    output->stream.common.get_sample_rate = out_get_sample_rate;
    output->stream.common.set_sample_rate = out_set_sample_rate;
    output->stream.common.get_buffer_size = out_get_buffer_size;
    output->stream.common.get_channels = out_get_channels;
    output->stream.common.get_format = out_get_format;
    output->stream.common.set_format = out_set_format;
    output->stream.common.standby = out_standby;
    output->stream.common.dump = out_dump;
    output->stream.common.get_device = out_get_device;
    output->stream.common.set_device = out_set_device;
    output->stream.common.set_parameters = out_set_parameters;
    output->stream.common.get_parameters = out_get_parameters;
    output->stream.common.add_audio_effect = out_add_audio_effect;
    output->stream.common.remove_audio_effect = out_remove_audio_effect;
    output->stream.get_latency = out_get_latency;
    output->stream.set_volume = out_set_volume;
    output->stream.write = out_write;
    output->stream.get_render_position = out_get_render_position;
    output->stream.get_next_write_timestamp = out_get_next_write_timestamp;
    output->stream.set_callback = out_set_callback;
    output->stream.pause = out_pause;
    output->stream.resume = out_resume;
    output->stream.drain = out_drain;
    output->stream.flush = out_flush;
    output->stream.get_presentation_position = out_get_presentation_position;
    output->stream.update_source_metadata = out_update_source_metadata;

    auto* virtual_device = reinterpret_cast<virtual_audio_device*>(device);
    if ((flags & AUDIO_OUTPUT_FLAG_DIRECT) != 0) {
        std::lock_guard<std::mutex> lock(virtual_device->mutex);
        if (virtual_device->direct_output != nullptr) {
            delete output;
            return -EBUSY;
        }
        virtual_device->direct_output = output;
    }

    *stream_out = &output->stream;
    ALOGI("Opened %s output: %u Hz, %u channels, format 0x%x",
          (flags & AUDIO_OUTPUT_FLAG_DIRECT) != 0 ? "direct" : "mixed", output->sample_rate,
          audio_channel_count_from_out_mask(output->channel_mask), output->format);
    return 0;
}

void device_close_output_stream(audio_hw_device* device, audio_stream_out* stream) {
    if (stream == nullptr) return;
    auto* virtual_device = reinterpret_cast<virtual_audio_device*>(device);
    auto* output = as_output(stream);
    {
        std::lock_guard<std::mutex> lock(virtual_device->mutex);
        if (virtual_device->direct_output == output) virtual_device->direct_output = nullptr;
    }
    virtual_device->transport.disconnect();
    delete output;
}

int device_open_input_stream(audio_hw_device*, audio_io_handle_t, audio_devices_t,
                             audio_config*, audio_stream_in**, audio_input_flags_t,
                             const char*, audio_source_t) {
    return -ENOSYS;
}

void device_close_input_stream(audio_hw_device*, audio_stream_in*) {}

int device_dump(const audio_hw_device* device, int fd) {
    const auto* virtual_device = reinterpret_cast<const virtual_audio_device*>(device);
    dprintf(fd, "VirtualDAP HAL: frames=%llu dropped_bytes=%llu reconnects=%llu\n",
            static_cast<unsigned long long>(virtual_device->transport.frames_written()),
            static_cast<unsigned long long>(virtual_device->transport.dropped_bytes()),
            static_cast<unsigned long long>(virtual_device->transport.reconnect_count()));
    return 0;
}

int device_set_master_mute(audio_hw_device*, bool) { return -ENOSYS; }

int device_get_master_mute(audio_hw_device*, bool*) { return -ENOSYS; }

int device_create_audio_patch(audio_hw_device*, unsigned int, const audio_port_config*,
                              unsigned int, const audio_port_config*, audio_patch_handle_t*) {
    return -ENOSYS;
}

int device_release_audio_patch(audio_hw_device*, audio_patch_handle_t) { return -ENOSYS; }

int device_get_audio_port(audio_hw_device*, audio_port*) { return -ENOSYS; }

int device_set_audio_port_config(audio_hw_device*, const audio_port_config*) { return 0; }

int device_close(hw_device_t* device) {
    if (device == nullptr) return 0;
    auto* virtual_device = reinterpret_cast<virtual_audio_device*>(device);
    virtual_device->transport.disconnect();
    delete virtual_device;
    return 0;
}

int device_open(const hw_module_t* module, const char* name, hw_device_t** output_device) {
    if (module == nullptr || name == nullptr || output_device == nullptr ||
        strcmp(name, AUDIO_HARDWARE_INTERFACE) != 0) {
        return -EINVAL;
    }

    char socket_name[PROPERTY_VALUE_MAX]{};
    char bridge_token[PROPERTY_VALUE_MAX]{};
    property_get("ro.vendor.virtualdap.socket_name", socket_name, virtualdap::kDefaultSocketName);
    property_get("ro.boot.virtualdap.bridge_token", bridge_token, "");
    auto* virtual_device = new (std::nothrow) virtual_audio_device(socket_name, bridge_token);
    if (virtual_device == nullptr) return -ENOMEM;

    virtual_device->device.common.tag = HARDWARE_DEVICE_TAG;
    virtual_device->device.common.version = AUDIO_DEVICE_API_VERSION_3_0;
    virtual_device->device.common.module = const_cast<hw_module_t*>(module);
    virtual_device->device.common.close = device_close;
    virtual_device->device.init_check = device_init_check;
    virtual_device->device.set_voice_volume = device_set_voice_volume;
    virtual_device->device.set_master_volume = device_set_master_volume;
    virtual_device->device.get_master_volume = device_get_master_volume;
    virtual_device->device.set_mode = device_set_mode;
    virtual_device->device.set_mic_mute = device_set_mic_mute;
    virtual_device->device.get_mic_mute = device_get_mic_mute;
    virtual_device->device.set_parameters = device_set_parameters;
    virtual_device->device.get_parameters = device_get_parameters;
    virtual_device->device.get_input_buffer_size = device_get_input_buffer_size;
    virtual_device->device.open_output_stream = device_open_output_stream;
    virtual_device->device.close_output_stream = device_close_output_stream;
    virtual_device->device.open_input_stream = device_open_input_stream;
    virtual_device->device.close_input_stream = device_close_input_stream;
    virtual_device->device.dump = device_dump;
    virtual_device->device.set_master_mute = device_set_master_mute;
    virtual_device->device.get_master_mute = device_get_master_mute;
    virtual_device->device.create_audio_patch = device_create_audio_patch;
    virtual_device->device.release_audio_patch = device_release_audio_patch;
    virtual_device->device.get_audio_port = device_get_audio_port;
    virtual_device->device.set_audio_port_config = device_set_audio_port_config;

    *output_device = &virtual_device->device.common;
    ALOGI("VirtualDAP audio HAL opened; bridge=%s", socket_name);
    return 0;
}

hw_module_methods_t module_methods = {
    .open = device_open,
};

}  // namespace

extern "C" {
struct audio_module HAL_MODULE_INFO_SYM = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .module_api_version = AUDIO_MODULE_API_VERSION_0_1,
        .hal_api_version = HARDWARE_HAL_API_VERSION,
        .id = AUDIO_HARDWARE_MODULE_ID,
        .name = "VirtualDAP PCM Bridge Audio HAL",
        .author = "sampple-korea",
        .methods = &module_methods,
        .dso = nullptr,
        .reserved = {0},
    },
};
}
