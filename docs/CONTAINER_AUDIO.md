# Container AudioTrack transport

The consumer container intercepts decoded Java `AudioTrack` streaming PCM in its application
process, before the hosted Application is constructed. Native registration uses the source-built
container hook engine. No system binary, root service or host-wide audio-capture permission is used.

```text
hosted AudioTrack.write → bounded native packet queue → private Unix socket
                       → host AudioPipelineService → real host AudioTrack → output route
```

The intercepted track's original native output is not started or written, so playback is not
duplicated. Its native allocation is still released normally. Unsupported static/compressed/native
audio paths retain their original Android behavior and must not be reported as captured.

## Implemented boundary

- Streaming PCM16, packed PCM24, PCM32 and float, 1–8 channels, within the bridge's 8–768 kHz range.
- Java byte[], short[], float[] and direct/non-direct ByteBuffer write entry points.
- Prebuffer before play; blocking backpressure and non-blocking partial writes.
- Play, pause, flush while paused, resume, draining stop, release and finalization.
- Application left/right volume is forwarded to the actual host track. Non-unity application gain
  prevents the UI from labeling the output bit-perfect.
- Playback-head and timestamp queries return host observations in source-frame units.
- Source bytes are neither re-encoded nor mixed in the container bridge. Any required host-format
  conversion is separately reported by the output pipeline.

The per-track queue is bounded by the original AudioTrack capacity, capped at 8 MiB. The worker sends
at most a 10 ms PCM packet at a time; the in-flight packet counts against the queue limit. Controls
have their own bounded queue and precede the next PCM packet. Pause can therefore take effect after
the current bounded submission rather than waiting behind the entire prebuffer. Guest callbacks
never hold JNI array pins while doing a blocking output write.

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
source-frame rounding; hardware latency beyond Android's playback head is not claimed measured.

## Evidence and remaining work

The independent fixture APK runs inside ordinary-UID API 33/36 x86_64 emulators and emits 48 kHz
PCM16 and 96 kHz float. Instrumentation verifies reception at the host output, source formats,
mute/unity gain, pause/resume and no reported drops. The host C++ test checks exact packet bytes, bounded prebuffering,
flush discard, volume controls, playback position and worker cleanup.

The receiver now accepts up to 16 independent streams, each with its own output track, format,
backpressure, controls and playback-head observations. Android mixes overlapping tracks, and the
host revokes its USB bit-perfect preference while they overlap. The UI shows the most recently
activated playing stream and the total connected/playing track counts. Idle handshakes time out;
paused, authenticated tracks can remain connected without occupying another stream's worker.

The fixture verifies two simultaneous 48/96 kHz streams and continued playback of the second after
the first is released on API 33/36. This establishes concurrent delivery, not gapless or sample-aligned
crossfade timing across independent hardware tracks. Bit-perfect mode is not automatically reasserted
mid-track after overlap; a new exact-format track can request it again.

This does not establish all music-service compatibility. Still required are AudioTrack
playback-speed/effect semantics, static AudioTrack, AAudio/OpenSL ES, app-specific decoder/DRM/login
tests and wider Android API/ABI runtime coverage.
An opt-in exclusive USB PCM transport is now integrated; see [USB output](USB_OUTPUT.md).
Its single-stream and hardware-validation limits remain distinct from Android's mixed output path.
Streaming apps remain PCM sources at this capture boundary. Local DSF/DSDIFF playback has a
separate product-facing native DSD/DoP path with reference-qualified device layouts; it does not
relabel a service's decoded PCM as DSD.

API signatures are checked against
[AOSP Android 16 AudioTrack](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/media/java/android/media/AudioTrack.java).
