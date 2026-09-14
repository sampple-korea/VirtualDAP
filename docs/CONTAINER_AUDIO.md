# Container AudioTrack, AAudio and OpenSL ES transport

The consumer container intercepts decoded Java `AudioTrack` streaming/static PCM plus native AAudio
and OpenSL ES output PCM in its application process, before the hosted Application is constructed. Native
registration uses the source-built container hook engine. No system binary, root service or
host-wide audio-capture permission is used.

```text
hosted AudioTrack.write → bounded streaming queue / static buffer iterator → private Unix socket
hosted AAudio callback  → preallocated callback buffer ────────────────────┤
hosted AAudio write     → timeout-aware bounded streaming queue ───────────┤
hosted OpenSL ES queue  → copied, count-bounded native buffer queue ───────┘
                         → host AudioPipelineService → real host AudioTrack → output route
```

An intercepted AudioTrack's original native output is not started or written, so playback is not
duplicated. A captured AAudio stream keeps an unstarted real stream only as the opaque application
handle and to obtain Android's negotiated PCM configuration; its native data/error callbacks are
removed and no original start or write call is made. A captured OpenSL ES PCM player is still
created and realized so Android validates the source/sink and requested interfaces, but its original
play-state and buffer-enqueue functions are never called. Per-object copies of the engine, player,
play, buffer-queue and volume vtables redirect only the candidate player. Native allocations are
still released normally. Unsupported compressed/native audio paths retain their original Android
behavior and must not be reported as captured. Failure to install either native hook family does not
disable the separate AudioTrack capture path.

## Implemented boundary

- Streaming PCM16, packed PCM24, PCM32 and float, 1–8 channels, within the bridge's 8–768 kHz range.
- Static PCM uses the AudioTrack capacity as a zero-initialized, overwrite-from-start shared buffer.
  Playback-head positioning, reload, disabled/finite/infinite loop points and immediate stop are
  implemented without expanding repeated audio in memory.
- Java byte[], short[], float[] and direct/non-direct ByteBuffer write entry points.
- Native AAudio PCM16, float, packed PCM24 and PCM32 output in both data-callback and blocking-write
  modes. Input and requested non-PCM streams pass through unchanged.
- Native OpenSL ES Android-simple and legacy PCM buffer queues for PCM16, float, packed PCM24 and
  PCM32. Android's original CreateAudioPlayer remains the authority for accepted rates, channel masks,
  formats and interfaces; non-PCM and non-output-mix players pass through unchanged.
- OpenSL ES queue capacity/count/index, Clear, STOPPED-only queue callback registration, play state,
  duration/position, marker/period/event masks, buffer callbacks, volume, mute and stereo-position
  controls. A buffer callback means the copied buffer has entered VirtualDAP's bounded transport,
  not that a physical DAC has presented its final sample.
- Prebuffer before play; blocking backpressure and non-blocking partial writes.
- Play, pause, flush while paused, resume, draining stop, release and finalization.
- AAudio start/pause/flush/stop state checks, timeout-aware partial writes, frames-written/read
  counters and source-frame monotonic/boottime timestamps. State-changing or close calls colliding
  with an AAudio callback are rejected as Android does.
- Application left/right volume is forwarded to the actual host track. Non-unity application gain
  prevents the UI from labeling the output bit-perfect.
- Playback-head and timestamp queries return host observations in source-frame units.
- Source bytes are neither re-encoded nor mixed in the container bridge. Any required host-format
  conversion is separately reported by the output pipeline.

The streaming queue and static buffer are each bounded by the original AudioTrack capacity, capped
at 8 MiB. The worker sends at most a 10 ms PCM packet at a time; a streaming in-flight packet counts
against its queue limit, while a static finite or infinite loop is generated incrementally from the
single retained buffer. Controls have their own bounded queue and precede the next PCM packet. Pause
can therefore take effect after the current bounded submission rather than waiting behind the entire
prebuffer or loop. Guest callbacks never hold JNI array pins while doing a blocking output write.
AAudio callback storage is allocated when the stream opens rather than on its callback thread. The
application callback itself performs no socket or bridge operation; after it returns, the dedicated
worker submits the block using bounded 5 ms waits so pause, flush, stop and close remain observable.
Pause, stop, flush and gain changes before first playback stay local and do not open an idle host
output. The OpenSL ES worker retains no caller-owned PCM pointer: Enqueue copies complete frames into
a queue limited by the declared OpenSL buffer count and an 8 MiB byte ceiling, then admits data to a
separate transport queue capped at 200 ms. Five-millisecond timed admission slices keep pause, Clear,
stop and destruction observable.

## Protocol 3

The original 32-byte handshake layout is retained with version 3. Version 2 HAL clients still work
unchanged. Version 3 starts the host sink paused. Every v3 message, including controls and pings,
receives a 32-byte acknowledgment:

| Offset | Type | Meaning |
| --- | --- | --- |
| 0 | u32 | Existing ACK magic |
| 4 | u16 | Version 3 |
| 6 | u16 | Zero |
| 8 | u64 | Exact acknowledged message sequence |
| 16 | u64 | Observed source playback frames since the last flush/stop |
| 24 | u64 | Host monotonic observation time in nanoseconds |

Message type 5 has a four-byte command: 1 play, 2 pause, 3 flush, 4 drain/stop.
Message type 6 has two little-endian float32 gains in [0, 1]; NaN/infinity are rejected.
Pings refresh position after the last PCM submission. These are playback-head observations,
**not measured USB DAC presentation timestamps**. A host compatibility resampler introduces
source-frame rounding; production rate changes use a packet-continuous, source-pinned best-sinc
filter. Hardware latency beyond Android's playback head is not claimed measured.

## Evidence and remaining work

The independent fixture APK runs inside ordinary-UID API 33/36 x86_64 emulators and emits streaming
48 kHz PCM16, streaming 96 kHz float, a finite-loop 44.1 kHz static PCM16 buffer, an 88.2 kHz PCM16
AAudio callback stream, a 96 kHz float AAudio blocking-write stream and a 48 kHz PCM16 OpenSL ES
Android-simple buffer queue. Instrumentation verifies reception at the host output, source formats,
static reload/position/loop entry points, AAudio callback collision and duplicate-start rules, timed
native writes, OpenSL engine reference handling, queue capacity/Clear/state/callback contracts,
play events and position, pause/flush/resume, timestamp and frame progress, mute/unity gain and no
reported drops. The host C++ test checks exact
streaming and static packet bytes, bounded prebuffering, finite/infinite loop generation, seek,
reload, immediate static stop, timed full-buffer writes, pre-play control/gain isolation, streaming drain,
volume controls, playback position and worker cleanup.

The receiver now accepts up to 16 independent streams, each with its own output track, format,
backpressure, controls and playback-head observations. Android mixes overlapping tracks, and the
host revokes its USB bit-perfect preference while they overlap. The UI shows the most recently
activated playing stream and the total connected/playing track counts. Idle handshakes time out;
paused, authenticated tracks can remain connected without occupying another stream's worker.

The fixture verifies two simultaneous 48/96 kHz streams and continued playback of the second after
the first is released on API 33/36. This establishes concurrent delivery, not gapless or sample-aligned
crossfade timing across independent hardware tracks. Bit-perfect mode is not automatically reasserted
mid-track after overlap; a new exact-format track can request it again.

This does not establish all music-service compatibility. Still required are AudioTrack/OpenSL ES
playback-speed and effect semantics, app-specific decoder/DRM/login tests and wider Android API/ABI
runtime coverage.
An opt-in exclusive USB PCM transport is now integrated; see [USB output](USB_OUTPUT.md).
Its single-stream and hardware-validation limits remain distinct from Android's mixed output path.
Streaming apps remain PCM sources at this capture boundary. Local DSF/DSDIFF playback has a
separate product-facing native DSD/DoP path with reference-qualified device layouts; it does not
relabel a service's decoded PCM as DSD.

API signatures are checked against
[AOSP Android 16 AudioTrack](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/media/java/android/media/AudioTrack.java),
and native state behavior is matched to the current
[AOSP AAudio AudioStream](https://android.googlesource.com/platform/frameworks/av/+/master/media/libaaudio/src/core/AudioStream.cpp).
OpenSL ES queue/play behavior is checked against current AOSP
[IBufferQueue](https://android.googlesource.com/platform/frameworks/wilhelm/+/refs/heads/main/src/itf/IBufferQueue.cpp),
[IPlay](https://android.googlesource.com/platform/frameworks/wilhelm/+/refs/heads/main/src/itf/IPlay.cpp) and
[Android AudioPlayer](https://android.googlesource.com/platform/frameworks/wilhelm/+/refs/heads/main/src/android/AudioPlayer_to_android.cpp).
