#include "OpenSLCapture.h"

#include "CapturedPcmStream.h"
#include "Dobby/dobby.h"

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <stdexcept>
#include <thread>
#include <unordered_map>
#include <vector>

namespace virtualdap {
namespace {

constexpr char kLogTag[] = "VirtualDAP-OpenSL";
constexpr size_t kMaximumQueuedBytes = 8 * 1024 * 1024;
constexpr size_t kMaximumEngines = 8;
constexpr size_t kMaximumPlayers = 32;
constexpr int64_t kWriteSliceNs = 5'000'000;
constexpr SLuint32 kSupportedPlayEvents =
    SL_PLAYEVENT_HEADATEND | SL_PLAYEVENT_HEADATMARKER | SL_PLAYEVENT_HEADATNEWPOS |
    SL_PLAYEVENT_HEADMOVING | SL_PLAYEVENT_HEADSTALLED;

std::atomic<bool> enabled{false};
std::atomic<uint64_t> next_epoch{uint64_t{1} << 48};

enum class QueueKind { kSimple, kLegacy };

struct EngineState;
struct PlayerState;

std::mutex registry_mutex;
std::unordered_map<const void*, std::shared_ptr<EngineState>> engine_objects;
std::unordered_map<const void*, std::shared_ptr<EngineState>> engine_interfaces;
std::unordered_map<const void*, std::shared_ptr<PlayerState>> player_objects;
std::unordered_map<const void*, std::shared_ptr<PlayerState>> play_interfaces;
std::unordered_map<const void*, std::shared_ptr<PlayerState>> simple_queue_interfaces;
std::unordered_map<const void*, std::shared_ptr<PlayerState>> legacy_queue_interfaces;
std::unordered_map<const void*, std::shared_ptr<PlayerState>> volume_interfaces;

template<typename Interface>
const void* interface_key(Interface interface) {
    return reinterpret_cast<const void*>(interface);
}

template<typename Table, typename Interface>
void replace_vtable(Interface interface, const Table* table) {
    auto slot = reinterpret_cast<const Table**>(
        const_cast<void*>(reinterpret_cast<const void*>(interface)));
    *slot = table;
}

template<typename State>
std::shared_ptr<State> find_state(
    const std::unordered_map<const void*, std::shared_ptr<State>>& registry, const void* key) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto found = registry.find(key);
    return found == registry.end() ? nullptr : found->second;
}

std::shared_ptr<EngineState> find_engine_object(SLObjectItf object) {
    return find_state(engine_objects, interface_key(object));
}

std::shared_ptr<EngineState> find_engine_interface(SLEngineItf engine) {
    return find_state(engine_interfaces, interface_key(engine));
}

std::shared_ptr<PlayerState> find_player_object(SLObjectItf object) {
    return find_state(player_objects, interface_key(object));
}

std::shared_ptr<PlayerState> find_play(SLPlayItf play) {
    return find_state(play_interfaces, interface_key(play));
}

std::shared_ptr<PlayerState> find_simple_queue(SLAndroidSimpleBufferQueueItf queue) {
    return find_state(simple_queue_interfaces, interface_key(queue));
}

std::shared_ptr<PlayerState> find_legacy_queue(SLBufferQueueItf queue) {
    return find_state(legacy_queue_interfaces, interface_key(queue));
}

std::shared_ptr<PlayerState> find_volume(SLVolumeItf volume) {
    return find_state(volume_interfaces, interface_key(volume));
}

SLresult captured_object_get_interface(SLObjectItf self, const SLInterfaceID iid, void* output);
void captured_object_destroy(SLObjectItf self);
SLresult captured_create_audio_player(
    SLEngineItf self, SLObjectItf* player, SLDataSource* source, SLDataSink* sink,
    SLuint32 interface_count, const SLInterfaceID* interface_ids,
    const SLboolean* interface_required);

SLresult captured_set_play_state(SLPlayItf self, SLuint32 state);
SLresult captured_get_play_state(SLPlayItf self, SLuint32* state);
SLresult captured_get_duration(SLPlayItf self, SLmillisecond* duration);
SLresult captured_get_position(SLPlayItf self, SLmillisecond* position);
SLresult captured_register_play_callback(SLPlayItf self, slPlayCallback callback, void* context);
SLresult captured_set_play_events(SLPlayItf self, SLuint32 events);
SLresult captured_get_play_events(SLPlayItf self, SLuint32* events);
SLresult captured_set_marker(SLPlayItf self, SLmillisecond position);
SLresult captured_clear_marker(SLPlayItf self);
SLresult captured_get_marker(SLPlayItf self, SLmillisecond* position);
SLresult captured_set_position_period(SLPlayItf self, SLmillisecond period);
SLresult captured_get_position_period(SLPlayItf self, SLmillisecond* period);

SLresult captured_simple_enqueue(
    SLAndroidSimpleBufferQueueItf self, const void* data, SLuint32 size);
SLresult captured_simple_clear(SLAndroidSimpleBufferQueueItf self);
SLresult captured_simple_get_state(
    SLAndroidSimpleBufferQueueItf self, SLAndroidSimpleBufferQueueState* state);
SLresult captured_simple_register_callback(
    SLAndroidSimpleBufferQueueItf self, slAndroidSimpleBufferQueueCallback callback, void* context);

SLresult captured_legacy_enqueue(SLBufferQueueItf self, const void* data, SLuint32 size);
SLresult captured_legacy_clear(SLBufferQueueItf self);
SLresult captured_legacy_get_state(SLBufferQueueItf self, SLBufferQueueState* state);
SLresult captured_legacy_register_callback(
    SLBufferQueueItf self, slBufferQueueCallback callback, void* context);

SLresult captured_set_volume_level(SLVolumeItf self, SLmillibel level);
SLresult captured_set_mute(SLVolumeItf self, SLboolean mute);
SLresult captured_enable_stereo_position(SLVolumeItf self, SLboolean enabled_value);
SLresult captured_set_stereo_position(SLVolumeItf self, SLpermille position);

struct EngineState {
    SLObjectItf object = nullptr;
    const SLObjectItf_* original_object_vtable = nullptr;
    SLObjectItf_ object_vtable{};
    SLEngineItf engine = nullptr;
    const SLEngineItf_* original_engine_vtable = nullptr;
    SLEngineItf_ engine_vtable{};
    std::mutex mutex;
    uint32_t references = 1;
};

struct QueuedBuffer {
    std::vector<uint8_t> bytes;
    uint64_t generation = 0;
};

struct PlayerState : std::enable_shared_from_this<PlayerState> {
    SLObjectItf engine_object = nullptr;
    SLObjectItf object = nullptr;
    const SLObjectItf_* original_object_vtable = nullptr;
    SLObjectItf_ object_vtable{};
    SLPlayItf play = nullptr;
    const SLPlayItf_* original_play_vtable = nullptr;
    SLPlayItf_ play_vtable{};
    SLAndroidSimpleBufferQueueItf simple_queue = nullptr;
    const SLAndroidSimpleBufferQueueItf_* original_simple_queue_vtable = nullptr;
    SLAndroidSimpleBufferQueueItf_ simple_queue_vtable{};
    SLBufferQueueItf legacy_queue = nullptr;
    const SLBufferQueueItf_* original_legacy_queue_vtable = nullptr;
    SLBufferQueueItf_ legacy_queue_vtable{};
    SLVolumeItf volume = nullptr;
    const SLVolumeItf_* original_volume_vtable = nullptr;
    SLVolumeItf_ volume_vtable{};

    PcmConfig config{};
    QueueKind queue_kind = QueueKind::kSimple;
    SLuint32 maximum_buffers = 1;
    std::shared_ptr<CapturedPcmStream> capture;
    std::mutex interface_mutex;
    std::mutex mutex;
    std::condition_variable changed;
    std::deque<QueuedBuffer> pending;
    std::thread worker;
    size_t queued_bytes = 0;
    SLuint32 queued_buffers = 0;
    SLuint32 queue_index = 0;
    SLuint32 play_state = SL_PLAYSTATE_STOPPED;
    uint64_t generation = 0;
    slAndroidSimpleBufferQueueCallback simple_callback = nullptr;
    slBufferQueueCallback legacy_callback = nullptr;
    void* queue_context = nullptr;
    slPlayCallback play_callback = nullptr;
    void* play_context = nullptr;
    SLuint32 play_events = 0;
    SLmillisecond marker_position = SL_TIME_UNKNOWN;
    SLmillisecond position_period = 1000;
    SLmillisecond next_position_event = 1000;
    bool marker_armed = false;
    SLmillibel volume_level = 0;
    SLboolean muted = SL_BOOLEAN_FALSE;
    SLboolean stereo_position_enabled = SL_BOOLEAN_FALSE;
    SLpermille stereo_position = 0;
    bool closing = false;
    bool failed = false;

    void launch() {
        auto self = shared_from_this();
        worker = std::thread([self] { self->worker_loop(); });
    }

    void worker_loop();

    void update_gain() {
        SLmillibel level;
        SLboolean is_muted;
        SLboolean position_enabled;
        SLpermille position;
        {
            std::lock_guard<std::mutex> lock(mutex);
            level = volume_level;
            is_muted = muted;
            position_enabled = stereo_position_enabled;
            position = stereo_position;
        }
        float gain = is_muted == SL_BOOLEAN_TRUE
            ? 0.f : static_cast<float>(std::pow(10.0, static_cast<double>(level) / 2000.0));
        gain = std::clamp(gain, 0.f, 1.f);
        float left = gain, right = gain;
        if (position_enabled == SL_BOOLEAN_TRUE) {
            const float normalized = std::clamp(static_cast<float>(position) / 1000.f, -1.f, 1.f);
            if (normalized < 0) right *= 1.f + normalized;
            else left *= 1.f - normalized;
        }
        capture->set_volume(left, right);
    }

    void shutdown() {
        bool first = false;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!closing) {
                closing = true;
                ++generation;
                pending.clear();
                queued_bytes = 0;
                queued_buffers = 0;
                first = true;
            }
        }
        changed.notify_all();
        if (first) capture->close();
        if (worker.joinable()) {
            if (worker.get_id() == std::this_thread::get_id()) worker.detach();
            else worker.join();
        }
    }

    void restore_vtables() {
        if (play && original_play_vtable) replace_vtable(play, original_play_vtable);
        if (simple_queue && original_simple_queue_vtable) {
            replace_vtable(simple_queue, original_simple_queue_vtable);
        }
        if (legacy_queue && original_legacy_queue_vtable) {
            replace_vtable(legacy_queue, original_legacy_queue_vtable);
        }
        if (volume && original_volume_vtable) replace_vtable(volume, original_volume_vtable);
        if (object && original_object_vtable) replace_vtable(object, original_object_vtable);
    }
};

struct PcmDescription {
    PcmConfig config{};
    QueueKind queue_kind = QueueKind::kSimple;
    SLuint32 maximum_buffers = 1;
};

bool parse_pcm_source(const SLDataSource* source, const SLDataSink* sink, PcmDescription* output) {
    if (!source || !source->pLocator || !source->pFormat || !sink || !sink->pLocator || !output) {
        return false;
    }
    const SLuint32 source_locator = *static_cast<const SLuint32*>(source->pLocator);
    const SLuint32 sink_locator = *static_cast<const SLuint32*>(sink->pLocator);
    if (sink_locator != SL_DATALOCATOR_OUTPUTMIX ||
        (source_locator != SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE &&
         source_locator != SL_DATALOCATOR_BUFFERQUEUE)) {
        return false;
    }

    const auto* pcm = static_cast<const SLDataFormat_PCM*>(source->pFormat);
    if (pcm->formatType != SL_DATAFORMAT_PCM && pcm->formatType != SL_ANDROID_DATAFORMAT_PCM_EX) {
        return false;
    }
    if (pcm->samplesPerSec % 1000 != 0 || pcm->numChannels < 1 || pcm->numChannels > 8 ||
        pcm->endianness != SL_BYTEORDER_LITTLEENDIAN) {
        return false;
    }
    const uint32_t rate = pcm->samplesPerSec / 1000;
    if (rate < 8000 || rate > 768000 || pcm->containerSize % 8 != 0) return false;

    SLuint32 representation = SL_ANDROID_PCM_REPRESENTATION_SIGNED_INT;
    if (pcm->formatType == SL_ANDROID_DATAFORMAT_PCM_EX) {
        representation = static_cast<const SLAndroidDataFormat_PCM_EX*>(source->pFormat)->representation;
    }
    Encoding encoding;
    uint32_t sample_bytes = pcm->containerSize / 8;
    if (representation == SL_ANDROID_PCM_REPRESENTATION_FLOAT &&
        pcm->bitsPerSample == 32 && pcm->containerSize == 32) {
        encoding = Encoding::kPcmFloat;
    } else if (representation == SL_ANDROID_PCM_REPRESENTATION_SIGNED_INT &&
               pcm->bitsPerSample == 16 && pcm->containerSize == 16) {
        encoding = Encoding::kPcm16;
    } else if (representation == SL_ANDROID_PCM_REPRESENTATION_SIGNED_INT &&
               pcm->bitsPerSample == 24 && pcm->containerSize == 24) {
        encoding = Encoding::kPcm24Packed;
    } else if (representation == SL_ANDROID_PCM_REPRESENTATION_SIGNED_INT &&
               pcm->bitsPerSample == 32 && pcm->containerSize == 32) {
        encoding = Encoding::kPcm32;
    } else {
        return false;
    }

    const SLuint32 maximum_buffers = source_locator == SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE
        ? static_cast<const SLDataLocator_AndroidSimpleBufferQueue*>(source->pLocator)->numBuffers
        : static_cast<const SLDataLocator_BufferQueue*>(source->pLocator)->numBuffers;
    if (maximum_buffers == 0) return false;
    const uint32_t frame_size = sample_bytes * pcm->numChannels;
    output->config = {
        rate, static_cast<uint16_t>(pcm->numChannels), encoding, frame_size, 0, next_epoch++,
    };
    output->queue_kind = source_locator == SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE
        ? QueueKind::kSimple : QueueKind::kLegacy;
    output->maximum_buffers = maximum_buffers;
    return true;
}

SLmillisecond source_position_ms(const PlayerState& state) {
    const PlaybackPosition position = state.capture->position();
    const uint64_t milliseconds = position.source_frames * 1000 / state.config.sample_rate;
    return static_cast<SLmillisecond>(std::min<uint64_t>(
        milliseconds, std::numeric_limits<SLmillisecond>::max()));
}

SLmillisecond add_milliseconds(SLmillisecond left, SLmillisecond right) {
    return static_cast<SLmillisecond>(std::min<uint64_t>(
        static_cast<uint64_t>(left) + right, std::numeric_limits<SLmillisecond>::max()));
}

void PlayerState::worker_loop() {
    pthread_setname_np(pthread_self(), "Vdap-OpenSL");
    QueuedBuffer current;
    size_t offset = 0;
    bool has_current = false;
    for (;;) {
        {
            std::unique_lock<std::mutex> lock(mutex);
            changed.wait(lock, [&] {
                return closing || failed ||
                    (has_current && current.generation != generation) ||
                    (play_state == SL_PLAYSTATE_PLAYING && (has_current || !pending.empty()));
            });
            if (closing || failed) return;
            if (has_current && current.generation != generation) {
                current = {};
                offset = 0;
                has_current = false;
                continue;
            }
            if (!has_current && play_state == SL_PLAYSTATE_PLAYING && !pending.empty()) {
                current = std::move(pending.front());
                pending.pop_front();
                offset = 0;
                has_current = true;
            }
            if (!has_current || play_state != SL_PLAYSTATE_PLAYING) continue;
        }

        const int written = capture->write_timed(
            current.bytes.data() + offset, current.bytes.size() - offset, kWriteSliceNs);
        if (written < 0) {
            {
                std::lock_guard<std::mutex> lock(mutex);
                if (closing) return;
                failed = true;
                play_state = SL_PLAYSTATE_STOPPED;
            }
            changed.notify_all();
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "PCM bridge failed for player %p", object);
            return;
        }
        offset += static_cast<size_t>(written);
        if (offset < current.bytes.size()) continue;

        slAndroidSimpleBufferQueueCallback simple = nullptr;
        slBufferQueueCallback legacy = nullptr;
        void* callback_context = nullptr;
        const uint64_t completed_generation = current.generation;
        const SLmillisecond position = source_position_ms(*this);
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (completed_generation == generation) {
                if (queued_buffers > 0) --queued_buffers;
                queued_bytes -= std::min(queued_bytes, current.bytes.size());
                ++queue_index;
                simple = simple_callback;
                legacy = legacy_callback;
                callback_context = queue_context;
            }
        }
        current = {};
        offset = 0;
        has_current = false;
        if (queue_kind == QueueKind::kSimple && simple) {
            simple(simple_queue, callback_context);
        } else if (queue_kind == QueueKind::kLegacy && legacy) {
            legacy(legacy_queue, callback_context);
        }

        SLuint32 events = 0;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!closing && completed_generation == generation) {
                if (marker_armed && marker_position != SL_TIME_UNKNOWN &&
                    position >= marker_position && (play_events & SL_PLAYEVENT_HEADATMARKER)) {
                    events |= SL_PLAYEVENT_HEADATMARKER;
                    marker_armed = false;
                }
                if (position_period > 0 && next_position_event != SL_TIME_UNKNOWN &&
                    position >= next_position_event && (play_events & SL_PLAYEVENT_HEADATNEWPOS)) {
                    events |= SL_PLAYEVENT_HEADATNEWPOS;
                    do next_position_event = add_milliseconds(next_position_event, position_period);
                    while (next_position_event != SL_TIME_UNKNOWN && next_position_event <= position);
                }
                if (queued_buffers == 0) {
                    events |= play_events & (SL_PLAYEVENT_HEADATEND | SL_PLAYEVENT_HEADSTALLED);
                }
            }
        }
        if (events != 0) {
            constexpr SLuint32 ordered_events[] = {
                SL_PLAYEVENT_HEADATMARKER,
                SL_PLAYEVENT_HEADATNEWPOS,
                SL_PLAYEVENT_HEADATEND,
                SL_PLAYEVENT_HEADSTALLED,
            };
            for (const SLuint32 event : ordered_events) {
                if ((events & event) == 0) continue;
                slPlayCallback callback = nullptr;
                void* context = nullptr;
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    if (closing || completed_generation != generation) break;
                    if ((play_events & event) != 0) {
                        callback = play_callback;
                        context = play_context;
                    }
                }
                if (callback) callback(play, context, event);
            }
        }
    }
}

bool patch_engine_interface(const std::shared_ptr<EngineState>& state, SLEngineItf engine) {
    std::lock_guard<std::mutex> state_lock(state->mutex);
    if (state->engine) return state->engine == engine;
    bool inserted = false;
    try {
        state->engine = engine;
        state->original_engine_vtable = *engine;
        state->engine_vtable = **engine;
        state->engine_vtable.CreateAudioPlayer = captured_create_audio_player;
        {
            std::lock_guard<std::mutex> lock(registry_mutex);
            inserted = engine_interfaces.emplace(interface_key(engine), state).second;
            if (!inserted) throw std::runtime_error("Duplicate OpenSL ES engine interface");
        }
        replace_vtable(engine, &state->engine_vtable);
        return true;
    } catch (...) {
        if (inserted) {
            std::lock_guard<std::mutex> lock(registry_mutex);
            const auto found = engine_interfaces.find(interface_key(engine));
            if (found != engine_interfaces.end() && found->second == state) {
                engine_interfaces.erase(found);
            }
        }
        state->engine = nullptr;
        state->original_engine_vtable = nullptr;
        return false;
    }
}

template<typename Interface, typename Table, typename Registry>
bool register_player_interface(
    const std::shared_ptr<PlayerState>& state, Interface interface, Interface* stored,
    const Table** original, Table* replacement, Registry& registry) {
    if (*stored) return *stored == interface;
    bool inserted = false;
    try {
        *stored = interface;
        *original = *interface;
        *replacement = **interface;
        {
            std::lock_guard<std::mutex> lock(registry_mutex);
            inserted = registry.emplace(interface_key(interface), state).second;
            if (!inserted) throw std::runtime_error("Duplicate OpenSL ES player interface");
        }
        return true;
    } catch (...) {
        if (inserted) {
            std::lock_guard<std::mutex> lock(registry_mutex);
            const auto found = registry.find(interface_key(interface));
            if (found != registry.end() && found->second == state) registry.erase(found);
        }
        *stored = nullptr;
        *original = nullptr;
        return false;
    }
}

bool patch_play_interface(const std::shared_ptr<PlayerState>& state, SLPlayItf play) {
    std::lock_guard<std::mutex> lock(state->interface_mutex);
    if (!register_player_interface(state, play, &state->play, &state->original_play_vtable,
                                   &state->play_vtable, play_interfaces)) return false;
    state->play_vtable.SetPlayState = captured_set_play_state;
    state->play_vtable.GetPlayState = captured_get_play_state;
    state->play_vtable.GetDuration = captured_get_duration;
    state->play_vtable.GetPosition = captured_get_position;
    state->play_vtable.RegisterCallback = captured_register_play_callback;
    state->play_vtable.SetCallbackEventsMask = captured_set_play_events;
    state->play_vtable.GetCallbackEventsMask = captured_get_play_events;
    state->play_vtable.SetMarkerPosition = captured_set_marker;
    state->play_vtable.ClearMarkerPosition = captured_clear_marker;
    state->play_vtable.GetMarkerPosition = captured_get_marker;
    state->play_vtable.SetPositionUpdatePeriod = captured_set_position_period;
    state->play_vtable.GetPositionUpdatePeriod = captured_get_position_period;
    replace_vtable(play, &state->play_vtable);
    return true;
}

bool patch_simple_queue_interface(
    const std::shared_ptr<PlayerState>& state, SLAndroidSimpleBufferQueueItf queue) {
    std::lock_guard<std::mutex> lock(state->interface_mutex);
    if (!register_player_interface(
            state, queue, &state->simple_queue, &state->original_simple_queue_vtable,
            &state->simple_queue_vtable, simple_queue_interfaces)) return false;
    state->simple_queue_vtable.Enqueue = captured_simple_enqueue;
    state->simple_queue_vtable.Clear = captured_simple_clear;
    state->simple_queue_vtable.GetState = captured_simple_get_state;
    state->simple_queue_vtable.RegisterCallback = captured_simple_register_callback;
    replace_vtable(queue, &state->simple_queue_vtable);
    return true;
}

bool patch_legacy_queue_interface(const std::shared_ptr<PlayerState>& state, SLBufferQueueItf queue) {
    std::lock_guard<std::mutex> lock(state->interface_mutex);
    if (!register_player_interface(
            state, queue, &state->legacy_queue, &state->original_legacy_queue_vtable,
            &state->legacy_queue_vtable, legacy_queue_interfaces)) return false;
    state->legacy_queue_vtable.Enqueue = captured_legacy_enqueue;
    state->legacy_queue_vtable.Clear = captured_legacy_clear;
    state->legacy_queue_vtable.GetState = captured_legacy_get_state;
    state->legacy_queue_vtable.RegisterCallback = captured_legacy_register_callback;
    replace_vtable(queue, &state->legacy_queue_vtable);
    return true;
}

bool patch_volume_interface(const std::shared_ptr<PlayerState>& state, SLVolumeItf volume) {
    std::lock_guard<std::mutex> lock(state->interface_mutex);
    if (!register_player_interface(state, volume, &state->volume, &state->original_volume_vtable,
                                   &state->volume_vtable, volume_interfaces)) return false;
    state->volume_vtable.SetVolumeLevel = captured_set_volume_level;
    state->volume_vtable.SetMute = captured_set_mute;
    state->volume_vtable.EnableStereoPosition = captured_enable_stereo_position;
    state->volume_vtable.SetStereoPosition = captured_set_stereo_position;
    replace_vtable(volume, &state->volume_vtable);
    return true;
}

SLresult captured_object_get_interface(SLObjectItf self, const SLInterfaceID iid, void* output) {
    if (!iid || !output) return SL_RESULT_PARAMETER_INVALID;
    if (auto engine_state = find_engine_object(self)) {
        const SLresult result = engine_state->original_object_vtable->GetInterface(self, iid, output);
        if (result == SL_RESULT_SUCCESS && iid == SL_IID_ENGINE) {
            auto* engine = static_cast<SLEngineItf*>(output);
            if (!*engine || !patch_engine_interface(engine_state, *engine)) {
                *engine = nullptr;
                __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                                    "Cannot patch engine interface for %p", self);
                return SL_RESULT_RESOURCE_ERROR;
            }
        }
        return result;
    }
    auto player_state = find_player_object(self);
    if (!player_state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    const SLresult result = player_state->original_object_vtable->GetInterface(self, iid, output);
    if (result != SL_RESULT_SUCCESS) return result;
    bool patched = true;
    if (iid == SL_IID_PLAY) {
        auto* play = static_cast<SLPlayItf*>(output);
        patched = *play && patch_play_interface(player_state, *play);
        if (!patched) *play = nullptr;
    } else if (iid == SL_IID_ANDROIDSIMPLEBUFFERQUEUE) {
        auto* queue = static_cast<SLAndroidSimpleBufferQueueItf*>(output);
        patched = player_state->queue_kind == QueueKind::kSimple && *queue &&
            patch_simple_queue_interface(player_state, *queue);
        if (!patched) *queue = nullptr;
    } else if (iid == SL_IID_BUFFERQUEUE) {
        auto* queue = static_cast<SLBufferQueueItf*>(output);
        patched = player_state->queue_kind == QueueKind::kLegacy && *queue &&
            patch_legacy_queue_interface(player_state, *queue);
        if (!patched) *queue = nullptr;
    } else if (iid == SL_IID_VOLUME) {
        auto* volume = static_cast<SLVolumeItf*>(output);
        patched = *volume && patch_volume_interface(player_state, *volume);
        if (!patched) *volume = nullptr;
    }
    if (!patched) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "Cannot patch player interface for %p", self);
        return SL_RESULT_RESOURCE_ERROR;
    }
    return result;
}

void erase_player_registries(const std::shared_ptr<PlayerState>& state) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    player_objects.erase(interface_key(state->object));
    if (state->play) play_interfaces.erase(interface_key(state->play));
    if (state->simple_queue) simple_queue_interfaces.erase(interface_key(state->simple_queue));
    if (state->legacy_queue) legacy_queue_interfaces.erase(interface_key(state->legacy_queue));
    if (state->volume) volume_interfaces.erase(interface_key(state->volume));
}

void destroy_player_state(const std::shared_ptr<PlayerState>& state) {
    state->shutdown();
    state->restore_vtables();
    const auto destroy = state->original_object_vtable->Destroy;
    destroy(state->object);
    erase_player_registries(state);
}

void captured_object_destroy(SLObjectItf self) {
    if (auto player_state = find_player_object(self)) {
        destroy_player_state(player_state);
        return;
    }
    auto engine_state = find_engine_object(self);
    if (!engine_state) return;
    bool final_reference = false;
    {
        std::lock_guard<std::mutex> state_lock(engine_state->mutex);
        if (engine_state->references > 1) {
            --engine_state->references;
        } else {
            final_reference = true;
        }
    }
    if (!final_reference) {
        engine_state->original_object_vtable->Destroy(self);
        return;
    }

    std::array<std::shared_ptr<PlayerState>, kMaximumPlayers> remaining_players{};
    size_t remaining_count = 0;
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        for (const auto& [key, player] : player_objects) {
            (void) key;
            if (player->engine_object == self && remaining_count < remaining_players.size()) {
                remaining_players[remaining_count++] = player;
            }
        }
    }
    for (size_t index = 0; index < remaining_count; ++index) {
        destroy_player_state(remaining_players[index]);
    }

    const auto destroy = engine_state->original_object_vtable->Destroy;
    destroy(self);
    std::lock_guard<std::mutex> lock(registry_mutex);
    engine_objects.erase(interface_key(engine_state->object));
    if (engine_state->engine) engine_interfaces.erase(interface_key(engine_state->engine));
}

SLresult captured_create_audio_player(
    SLEngineItf self, SLObjectItf* player, SLDataSource* source, SLDataSink* sink,
    SLuint32 interface_count, const SLInterfaceID* interface_ids,
    const SLboolean* interface_required) {
    auto engine_state = find_engine_interface(self);
    if (!engine_state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    PcmDescription description;
    const bool candidate = parse_pcm_source(source, sink, &description);
    if (!candidate) {
        if (player) *player = nullptr;
        return SL_RESULT_CONTENT_UNSUPPORTED;
    }
    const SLresult result = engine_state->original_engine_vtable->CreateAudioPlayer(
        self, player, source, sink, interface_count, interface_ids, interface_required);
    if (result != SL_RESULT_SUCCESS || !player || !*player) return result;

    const SLObjectItf native_player = *player;
    const SLObjectItf_* native_object_vtable = **player;
    std::shared_ptr<PlayerState> state;
    bool registered = false;
    const auto abort_capture = [&](SLresult failure) {
        if (state) {
            if (state->capture) state->shutdown();
            if (state->object && state->original_object_vtable) {
                replace_vtable(state->object, state->original_object_vtable);
            }
            if (registered) erase_player_registries(state);
        }
        native_object_vtable->Destroy(native_player);
        *player = nullptr;
        return failure;
    };
    try {
        state = std::make_shared<PlayerState>();
        state->engine_object = engine_state->object;
        state->object = *player;
        state->original_object_vtable = **player;
        state->object_vtable = ***player;
        state->object_vtable.GetInterface = captured_object_get_interface;
        state->object_vtable.Destroy = captured_object_destroy;
        state->config = description.config;
        state->queue_kind = description.queue_kind;
        state->maximum_buffers = description.maximum_buffers;
        const size_t maximum_frames = kMaximumQueuedBytes / state->config.frame_size;
        const size_t capacity_frames = std::min<size_t>(
            maximum_frames, std::max<uint32_t>(1, state->config.sample_rate / 5));
        state->capture = CapturedPcmStream::create(state->config, capacity_frames);
        {
            std::lock_guard<std::mutex> lock(registry_mutex);
            if (player_objects.size() >= kMaximumPlayers) {
                throw std::runtime_error("Too many captured OpenSL ES players");
            }
            if (!player_objects.emplace(interface_key(state->object), state).second) {
                throw std::runtime_error("Duplicate OpenSL ES player");
            }
            registered = true;
        }
        replace_vtable(state->object, &state->object_vtable);
        state->launch();
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                            "PCM player %p: %u Hz, %u ch, encoding %u, %u buffers",
                            state->object, state->config.sample_rate, state->config.channel_count,
                            static_cast<unsigned>(state->config.encoding), state->maximum_buffers);
    } catch (const std::bad_alloc& error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Cannot capture player: %s", error.what());
        return abort_capture(SL_RESULT_MEMORY_FAILURE);
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Cannot capture player: %s", error.what());
        return abort_capture(SL_RESULT_RESOURCE_ERROR);
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Cannot capture player: unknown failure");
        return abort_capture(SL_RESULT_RESOURCE_ERROR);
    }
    return result;
}

SLresult captured_set_play_state(SLPlayItf self, SLuint32 requested) {
    auto state = find_play(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    if (requested != SL_PLAYSTATE_STOPPED && requested != SL_PLAYSTATE_PAUSED &&
        requested != SL_PLAYSTATE_PLAYING) return SL_RESULT_PARAMETER_INVALID;
    const SLmillisecond starting_position = requested == SL_PLAYSTATE_PLAYING
        ? source_position_ms(*state) : 0;
    SLuint32 previous;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        if (state->closing || state->failed) return SL_RESULT_PRECONDITIONS_VIOLATED;
        previous = state->play_state;
        state->play_state = requested;
        if (requested == SL_PLAYSTATE_PLAYING && previous != requested) {
            state->next_position_event = add_milliseconds(starting_position, state->position_period);
        }
    }
    if (previous == requested) return SL_RESULT_SUCCESS;
    if (requested == SL_PLAYSTATE_PLAYING) {
        state->capture->control(PlaybackControl::kPlay);
    } else if (requested == SL_PLAYSTATE_PAUSED) {
        state->capture->control(PlaybackControl::kPause);
    } else {
        state->capture->control(PlaybackControl::kStop);
    }
    state->changed.notify_all();
    return state->capture->failed() ? SL_RESULT_RESOURCE_ERROR : SL_RESULT_SUCCESS;
}

SLresult captured_get_play_state(SLPlayItf self, SLuint32* output) {
    auto state = find_play(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    if (!output) return SL_RESULT_PARAMETER_INVALID;
    std::lock_guard<std::mutex> lock(state->mutex);
    *output = state->play_state;
    return SL_RESULT_SUCCESS;
}

SLresult captured_get_duration(SLPlayItf self, SLmillisecond* output) {
    if (!find_play(self)) return SL_RESULT_PRECONDITIONS_VIOLATED;
    if (!output) return SL_RESULT_PARAMETER_INVALID;
    *output = SL_TIME_UNKNOWN;
    return SL_RESULT_SUCCESS;
}

SLresult captured_get_position(SLPlayItf self, SLmillisecond* output) {
    auto state = find_play(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    if (!output) return SL_RESULT_PARAMETER_INVALID;
    *output = source_position_ms(*state);
    return SL_RESULT_SUCCESS;
}

SLresult captured_register_play_callback(SLPlayItf self, slPlayCallback callback, void* context) {
    auto state = find_play(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->play_callback = callback;
    state->play_context = context;
    return SL_RESULT_SUCCESS;
}

SLresult captured_set_play_events(SLPlayItf self, SLuint32 events) {
    auto state = find_play(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    if ((events & ~kSupportedPlayEvents) != 0) return SL_RESULT_PARAMETER_INVALID;
    const SLmillisecond position = source_position_ms(*state);
    std::lock_guard<std::mutex> lock(state->mutex);
    if ((state->play_events & SL_PLAYEVENT_HEADATNEWPOS) == 0 &&
        (events & SL_PLAYEVENT_HEADATNEWPOS) != 0) {
        state->next_position_event = add_milliseconds(position, state->position_period);
    }
    state->play_events = events;
    return SL_RESULT_SUCCESS;
}

SLresult captured_get_play_events(SLPlayItf self, SLuint32* events) {
    auto state = find_play(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    if (!events) return SL_RESULT_PARAMETER_INVALID;
    std::lock_guard<std::mutex> lock(state->mutex);
    *events = state->play_events;
    return SL_RESULT_SUCCESS;
}

SLresult captured_set_marker(SLPlayItf self, SLmillisecond position) {
    auto state = find_play(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    if (position == SL_TIME_UNKNOWN) return SL_RESULT_PARAMETER_INVALID;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->marker_position = position;
    state->marker_armed = true;
    return SL_RESULT_SUCCESS;
}

SLresult captured_clear_marker(SLPlayItf self) {
    auto state = find_play(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->marker_position = SL_TIME_UNKNOWN;
    state->marker_armed = false;
    return SL_RESULT_SUCCESS;
}

SLresult captured_get_marker(SLPlayItf self, SLmillisecond* position) {
    auto state = find_play(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    if (!position) return SL_RESULT_PARAMETER_INVALID;
    std::lock_guard<std::mutex> lock(state->mutex);
    *position = state->marker_position;
    return state->marker_position == SL_TIME_UNKNOWN
        ? SL_RESULT_PRECONDITIONS_VIOLATED : SL_RESULT_SUCCESS;
}

SLresult captured_set_position_period(SLPlayItf self, SLmillisecond period) {
    auto state = find_play(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    if (period == 0) return SL_RESULT_PARAMETER_INVALID;
    const SLmillisecond position = source_position_ms(*state);
    std::lock_guard<std::mutex> lock(state->mutex);
    state->position_period = period;
    state->next_position_event = add_milliseconds(position, period);
    return SL_RESULT_SUCCESS;
}

SLresult captured_get_position_period(SLPlayItf self, SLmillisecond* period) {
    auto state = find_play(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    if (!period) return SL_RESULT_PARAMETER_INVALID;
    std::lock_guard<std::mutex> lock(state->mutex);
    *period = state->position_period;
    return SL_RESULT_SUCCESS;
}

SLresult enqueue_buffer(const std::shared_ptr<PlayerState>& state, const void* data, SLuint32 size) {
    if (!data || size == 0 || size % state->config.frame_size != 0) {
        return SL_RESULT_PARAMETER_INVALID;
    }
    if (size > kMaximumQueuedBytes) return SL_RESULT_BUFFER_INSUFFICIENT;
    try {
        std::lock_guard<std::mutex> lock(state->mutex);
        if (state->closing || state->failed) return SL_RESULT_PRECONDITIONS_VIOLATED;
        if (state->queued_buffers >= state->maximum_buffers ||
            state->queued_bytes > kMaximumQueuedBytes - size) {
            return SL_RESULT_BUFFER_INSUFFICIENT;
        }
        QueuedBuffer buffer;
        buffer.bytes.assign(static_cast<const uint8_t*>(data),
                            static_cast<const uint8_t*>(data) + size);
        buffer.generation = state->generation;
        state->pending.push_back(std::move(buffer));
        state->queued_bytes += size;
        ++state->queued_buffers;
    } catch (...) {
        return SL_RESULT_MEMORY_FAILURE;
    }
    state->changed.notify_all();
    return SL_RESULT_SUCCESS;
}

SLresult clear_queue(const std::shared_ptr<PlayerState>& state) {
    bool playing;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        if (state->closing || state->failed) return SL_RESULT_PRECONDITIONS_VIOLATED;
        playing = state->play_state == SL_PLAYSTATE_PLAYING;
        ++state->generation;
        state->pending.clear();
        state->queued_bytes = 0;
        state->queued_buffers = 0;
        state->queue_index = 0;
    }
    state->changed.notify_all();
    state->capture->control(PlaybackControl::kPause);
    state->capture->control(PlaybackControl::kFlush);
    if (playing) state->capture->control(PlaybackControl::kPlay);
    return state->capture->failed() ? SL_RESULT_RESOURCE_ERROR : SL_RESULT_SUCCESS;
}

SLresult register_queue_callback(
    const std::shared_ptr<PlayerState>& state, slAndroidSimpleBufferQueueCallback simple,
    slBufferQueueCallback legacy, void* context) {
    std::lock_guard<std::mutex> lock(state->mutex);
    if (state->play_state != SL_PLAYSTATE_STOPPED) return SL_RESULT_PRECONDITIONS_VIOLATED;
    state->simple_callback = simple;
    state->legacy_callback = legacy;
    state->queue_context = context;
    return SL_RESULT_SUCCESS;
}

SLresult captured_simple_enqueue(
    SLAndroidSimpleBufferQueueItf self, const void* data, SLuint32 size) {
    auto state = find_simple_queue(self);
    return state ? enqueue_buffer(state, data, size) : SL_RESULT_PRECONDITIONS_VIOLATED;
}

SLresult captured_simple_clear(SLAndroidSimpleBufferQueueItf self) {
    auto state = find_simple_queue(self);
    return state ? clear_queue(state) : SL_RESULT_PRECONDITIONS_VIOLATED;
}

SLresult captured_simple_get_state(
    SLAndroidSimpleBufferQueueItf self, SLAndroidSimpleBufferQueueState* output) {
    auto state = find_simple_queue(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    if (!output) return SL_RESULT_PARAMETER_INVALID;
    std::lock_guard<std::mutex> lock(state->mutex);
    output->count = state->queued_buffers;
    output->index = state->queue_index;
    return SL_RESULT_SUCCESS;
}

SLresult captured_simple_register_callback(
    SLAndroidSimpleBufferQueueItf self, slAndroidSimpleBufferQueueCallback callback, void* context) {
    auto state = find_simple_queue(self);
    return state ? register_queue_callback(state, callback, nullptr, context)
                 : SL_RESULT_PRECONDITIONS_VIOLATED;
}

SLresult captured_legacy_enqueue(SLBufferQueueItf self, const void* data, SLuint32 size) {
    auto state = find_legacy_queue(self);
    return state ? enqueue_buffer(state, data, size) : SL_RESULT_PRECONDITIONS_VIOLATED;
}

SLresult captured_legacy_clear(SLBufferQueueItf self) {
    auto state = find_legacy_queue(self);
    return state ? clear_queue(state) : SL_RESULT_PRECONDITIONS_VIOLATED;
}

SLresult captured_legacy_get_state(SLBufferQueueItf self, SLBufferQueueState* output) {
    auto state = find_legacy_queue(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    if (!output) return SL_RESULT_PARAMETER_INVALID;
    std::lock_guard<std::mutex> lock(state->mutex);
    output->count = state->queued_buffers;
    output->playIndex = state->queue_index;
    return SL_RESULT_SUCCESS;
}

SLresult captured_legacy_register_callback(
    SLBufferQueueItf self, slBufferQueueCallback callback, void* context) {
    auto state = find_legacy_queue(self);
    return state ? register_queue_callback(state, nullptr, callback, context)
                 : SL_RESULT_PRECONDITIONS_VIOLATED;
}

SLresult captured_set_volume_level(SLVolumeItf self, SLmillibel level) {
    auto state = find_volume(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    const SLresult result = state->original_volume_vtable->SetVolumeLevel(self, level);
    if (result == SL_RESULT_SUCCESS) {
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            state->volume_level = level;
        }
        state->update_gain();
    }
    return result;
}

SLresult captured_set_mute(SLVolumeItf self, SLboolean mute) {
    auto state = find_volume(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    const SLresult result = state->original_volume_vtable->SetMute(self, mute);
    if (result == SL_RESULT_SUCCESS) {
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            state->muted = mute;
        }
        state->update_gain();
    }
    return result;
}

SLresult captured_enable_stereo_position(SLVolumeItf self, SLboolean enabled_value) {
    auto state = find_volume(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    const SLresult result = state->original_volume_vtable->EnableStereoPosition(self, enabled_value);
    if (result == SL_RESULT_SUCCESS) {
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            state->stereo_position_enabled = enabled_value;
        }
        state->update_gain();
    }
    return result;
}

SLresult captured_set_stereo_position(SLVolumeItf self, SLpermille position) {
    auto state = find_volume(self);
    if (!state) return SL_RESULT_PRECONDITIONS_VIOLATED;
    const SLresult result = state->original_volume_vtable->SetStereoPosition(self, position);
    if (result == SL_RESULT_SUCCESS) {
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            state->stereo_position = position;
        }
        state->update_gain();
    }
    return result;
}

using SlCreateEngine = SLresult (SLAPIENTRY *)(
    SLObjectItf*, SLuint32, const SLEngineOption*, SLuint32, const SLInterfaceID*,
    const SLboolean*);

SlCreateEngine original_sl_create_engine = nullptr;

SLresult captured_sl_create_engine(
    SLObjectItf* engine, SLuint32 option_count, const SLEngineOption* options,
    SLuint32 interface_count, const SLInterfaceID* interface_ids,
    const SLboolean* interface_required) {
    const SLresult result = original_sl_create_engine(
        engine, option_count, options, interface_count, interface_ids, interface_required);
    if (result != SL_RESULT_SUCCESS || !engine || !*engine) return result;

    if (auto existing = find_engine_object(*engine)) {
        std::lock_guard<std::mutex> state_lock(existing->mutex);
        if (existing->references == std::numeric_limits<uint32_t>::max()) {
            existing->original_object_vtable->Destroy(*engine);
            *engine = nullptr;
            return SL_RESULT_RESOURCE_ERROR;
        }
        ++existing->references;
        return result;
    }

    const SLObjectItf native_engine = *engine;
    const SLObjectItf_* native_object_vtable = **engine;
    std::shared_ptr<EngineState> state;
    bool registered = false;
    const auto abort_capture = [&](SLresult failure) {
        if (registered) {
            std::lock_guard<std::mutex> lock(registry_mutex);
            const auto found = engine_objects.find(interface_key(native_engine));
            if (found != engine_objects.end() && found->second == state) engine_objects.erase(found);
        }
        if (state && state->original_object_vtable &&
            *native_engine == &state->object_vtable) {
            replace_vtable(native_engine, state->original_object_vtable);
        }
        const auto tracked = find_engine_object(native_engine);
        const auto* destroy_vtable = tracked && tracked->original_object_vtable
            ? tracked->original_object_vtable
            : (state && state->original_object_vtable
                   ? state->original_object_vtable : native_object_vtable);
        destroy_vtable->Destroy(native_engine);
        *engine = nullptr;
        return failure;
    };
    try {
        state = std::make_shared<EngineState>();
        state->object = *engine;
        state->original_object_vtable = **engine;
        state->object_vtable = ***engine;
        state->object_vtable.GetInterface = captured_object_get_interface;
        state->object_vtable.Destroy = captured_object_destroy;
        std::shared_ptr<EngineState> raced_existing;
        {
            std::lock_guard<std::mutex> lock(registry_mutex);
            const auto found = engine_objects.find(interface_key(state->object));
            if (found != engine_objects.end()) {
                raced_existing = found->second;
            } else {
                if (engine_objects.size() >= kMaximumEngines) {
                    throw std::runtime_error("Too many OpenSL ES engines");
                }
                engine_objects.emplace(interface_key(state->object), state);
                registered = true;
            }
        }
        if (raced_existing) {
            std::lock_guard<std::mutex> state_lock(raced_existing->mutex);
            if (raced_existing->references == std::numeric_limits<uint32_t>::max()) {
                raced_existing->original_object_vtable->Destroy(native_engine);
                *engine = nullptr;
                return SL_RESULT_RESOURCE_ERROR;
            }
            ++raced_existing->references;
            return result;
        }
        replace_vtable(state->object, &state->object_vtable);
    } catch (const std::bad_alloc& error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Cannot capture engine: %s", error.what());
        return abort_capture(SL_RESULT_MEMORY_FAILURE);
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Cannot capture engine: %s", error.what());
        return abort_capture(SL_RESULT_RESOURCE_ERROR);
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Cannot capture engine: unknown failure");
        return abort_capture(SL_RESULT_RESOURCE_ERROR);
    }
    return result;
}

} // namespace

bool install_opensl_capture() {
    static std::mutex install_mutex;
    static bool attempted = false;
    static void* library = nullptr;
    std::lock_guard<std::mutex> lock(install_mutex);
    if (enabled) return true;
    if (attempted) return false;
    attempted = true;
    library = dlopen("libOpenSLES.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Cannot load libOpenSLES.so: %s", dlerror());
        return false;
    }
    void* target = dlsym(library, "slCreateEngine");
    const bool complete = target &&
        DobbyHook(target, reinterpret_cast<dobby_dummy_func_t>(captured_sl_create_engine),
                  reinterpret_cast<dobby_dummy_func_t*>(&original_sl_create_engine)) == 0 &&
        original_sl_create_engine;
    enabled = complete;
    __android_log_print(complete ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kLogTag,
                        "OpenSL ES PCM capture %s", complete ? "ready" : "unavailable");
    return complete;
}

}
