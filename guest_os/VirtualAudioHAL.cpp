#define LOG_TAG "VirtualAudioHAL"
//#define LOG_NDEBUG 0

#include <errno.h>
#include <malloc.h>
#include <pthread.h>
#include <stdint.h>
#include <sys/time.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <stdatomic.h>

#include <log/log.h>
#include <hardware/hardware.h>
#include <system/audio.h>
#include <hardware/audio.h>

#include "RingBuffer.h"

#define ASHMEM_NAME "/virtual_dap_audio"

struct virtual_audio_device {
    struct audio_hw_device device;
    // Protects output stream creation
    pthread_mutex_t lock;
    struct virtual_stream_out *active_output;
    
    // Shared Memory
    int shm_fd;
    struct ring_buffer_t *shm_buffer;
};

struct virtual_stream_out {
    struct audio_stream_out stream;
    struct virtual_audio_device *dev;
    pthread_mutex_t lock;
    
    // Config
    uint32_t sample_rate;
    audio_channel_mask_t channel_mask;
    audio_format_t format;
    size_t frame_count;
    
    bool standby;
};

static int shm_init(struct virtual_audio_device *adev) {
    if (adev->shm_buffer != NULL) return 0;
    
    // In valid Android environment we use ASharedMemory_create or open direct ashmem device
    // For this POC code we assume a standard posix shm or pre-created file
    adev->shm_fd = open("/dev/shm/virtual_dap_audio", O_RDWR | O_CREAT, 0666);
    if (adev->shm_fd < 0) {
        ALOGE("Failed to open shared memory: %s", strerror(errno));
        return -errno;
    }
    
    // Force size
    if (ftruncate(adev->shm_fd, sizeof(struct ring_buffer_t)) < 0) {
        ALOGE("Failed to set shared memory size");
        return -errno;
    }
    
    adev->shm_buffer = (struct ring_buffer_t *)mmap(NULL, sizeof(struct ring_buffer_t), 
                                                    PROT_READ | PROT_WRITE, MAP_SHARED, 
                                                    adev->shm_fd, 0);
    
    if (adev->shm_buffer == MAP_FAILED) {
        ALOGE("mmap failed");
        adev->shm_buffer = NULL;
        return -errno;
    }
    
    // Initialize header
    adev->shm_buffer->magic = RING_BUFFER_MAGIC;
    adev->shm_buffer->size = RING_BUFFER_SIZE;
    atomic_init(&adev->shm_buffer->head, 0);
    atomic_init(&adev->shm_buffer->tail, 0);
    
    ALOGI("Shared Memory Initialized at %p", adev->shm_buffer);
    return 0;
}

static ssize_t out_write(struct audio_stream_out *stream, const void* buffer,
                         size_t bytes) {
    struct virtual_stream_out *out = (struct virtual_stream_out *)stream;
    struct virtual_audio_device *adev = out->dev;
    
    if (adev->shm_buffer == NULL) shm_init(adev);
    
    // 1. Update Format Metadata if changed
    adev->shm_buffer->sample_rate = out->sample_rate;
    adev->shm_buffer->channel_mask = out->channel_mask;
    adev->shm_buffer->format = out->format;
    
    // 2. Write to Ring Buffer
    const uint8_t *in_buf = (const uint8_t *)buffer;
    size_t bytes_written = 0;
    
    // Simple blocking write (with timeout to prevent freezing OS)
    int retries = 100;
    while (bytes_written < bytes && retries > 0) {
        uint32_t avail = ring_buffer_available_write(adev->shm_buffer);
        if (avail == 0) {
            usleep(1000); // 1ms wait
            retries--;
            continue;
        }
        
        size_t chunk = (bytes - bytes_written) > avail ? avail : (bytes - bytes_written);
        uint32_t head = atomic_load_explicit(&adev->shm_buffer->head, memory_order_relaxed);
        
        // Circular buffer logic
        uint32_t end = adev->shm_buffer->size;
        size_t first_part = (head + chunk > end) ? (end - head) : chunk;
        
        memcpy(&adev->shm_buffer->data[head], &in_buf[bytes_written], first_part);
        if (chunk > first_part) {
            memcpy(&adev->shm_buffer->data[0], &in_buf[bytes_written + first_part], chunk - first_part);
        }
        
        // Advancing head
        uint32_t new_head = (head + chunk) % end;
        atomic_store_explicit(&adev->shm_buffer->head, new_head, memory_order_release);
        
        bytes_written += chunk;
    }

    // Simulate timing consumption if buffer was full to keep audio flinger happy
    if (bytes_written < bytes) {
       usleep((bytes * 1000000) / (out->sample_rate * 4)); // approx time for stereo 16bit
    }

    out->standby = false;
    return bytes;
}

// ... Boilerplate HAL implementation omitted for brevity (open_output_stream, etc.) ...
// For the sake of this file interacting with the user task I will just include the core open function.

static int adev_open_output_stream(struct audio_hw_device *dev,
                                   audio_io_handle_t handle,
                                   audio_devices_t devices,
                                   audio_output_flags_t flags,
                                   struct audio_config *config,
                                   struct audio_stream_out **stream_out,
                                   const char *address __unused) {
    struct virtual_audio_device *adev = (struct virtual_audio_device *)dev;
    struct virtual_stream_out *out;

    out = (struct virtual_stream_out *)calloc(1, sizeof(struct virtual_stream_out));
    if (!out) return -ENOMEM;

    out->stream.common.get_sample_rate = NULL; // ... set standard function pointers
    out->stream.common.set_sample_rate = NULL;
    // ...
    out->stream.write = out_write;
    
    // FORCE CONFIGURATION (The "God Mode" aspect)
    // We accept whatever Android asks for, but we also TELL Android we support it.
    // Ideally we'd claim we support everything to avoid resampling.
    
    out->sample_rate = config->sample_rate;
    out->channel_mask = config->channel_mask;
    out->format = config->format;
    out->dev = adev;

    *stream_out = &out->stream;
    return 0;
}

static int adev_close(hw_device_t *device) {
    free(device);
    return 0;
}

static int adev_open(const hw_module_t* module, const char* name,
                     hw_device_t** device) {
    if (strcmp(name, AUDIO_HARDWARE_INTERFACE) != 0) return -EINVAL;

    struct virtual_audio_device *adev = calloc(1, sizeof(struct virtual_audio_device));
    if (!adev) return -ENOMEM;

    adev->device.common.tag = HARDWARE_DEVICE_TAG;
    adev->device.common.version = AUDIO_DEVICE_API_VERSION_2_0;
    adev->device.common.module = (struct hw_module_t *) module;
    adev->device.common.close = adev_close;
    
    adev->device.open_output_stream = adev_open_output_stream;
    
    shm_init(adev);

    *device = &adev->device.common;
    return 0;
}

static struct hw_module_methods_t hal_module_methods = {
    .open = adev_open,
};

struct audio_module HAL_MODULE_INFO_SYM = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .module_api_version = AUDIO_MODULE_API_VERSION_0_1,
        .hal_api_version = HARDWARE_HAL_API_VERSION,
        .id = AUDIO_HARDWARE_MODULE_ID,
        .name = "VirtualDAP Audio HAL",
        .author = "Antigravity",
        .methods = &hal_module_methods,
    },
};
