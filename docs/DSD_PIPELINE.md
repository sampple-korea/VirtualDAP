# DSD transport and conversion

The source must actually provide DSD data for native DSD or DoP output. PCM produced by a streaming
music app is kept as PCM; relabeling or repacking it cannot recover an original DSD stream.

Implemented components:

- `DsdContainerReader` detects content from its signature rather than trusting an extension or MIME
  label. `DsfReader` validates Sony DSF headers, format/channel declarations, bounded block sizes,
  audio length and metadata boundaries, then streams channel-planar blocks into chronological
  interleaved DSD packets without loading the whole file or forwarding final-block padding.
- `DffReader` validates Philips DSDIFF 1.x form/chunk boundaries, version, sound properties,
  channel IDs and compression declaration. It streams already-clustered MSB-first DSD and rejects
  DST-compressed content before any audio is emitted.
- `DsdFormat` describes the one-bit sample rate, channel count and source bit order.
- `DopEncoder` implements the DoP 1.1 marker sequence, preserves markers across packet boundaries,
  keeps channel ordering and handles packed 24-bit or padded four-byte UAC subslots. Truncated
  frames are rejected.
- `NativeDsdEncoder` groups chronological, channel-interleaved DSD bytes into negotiated U8,
  U16 or U32 wire words while keeping packet-boundary state, word endianness and bit order explicit.
- `UsbDsdDeviceRules` keeps UAC `RAW_DATA` untrusted by default. It promotes only layouts listed by
  the source-pinned Linux USB-audio DSD hardware rules, including their firmware and device-level
  exclusions, and removes known DSD-only alternates from PCM candidate selection.
- `DirectUsbDsdSink` composes those layouts with the real bounded USB transport. Native mode accepts
  only a qualified profile; DoP accepts only an explicitly user-confirmed DAC and an exact 24-bit
  carrier profile. Both paths bypass gain, PCM mixing and resampling, preserve partial-write order,
  reset framing on flush and fail closed on truncated source words.
- `DsdOutputPlanner` requires explicit output capabilities and a bit-transparent route before
  selecting native DSD or DoP. A generic PCM capability does not imply native DSD support.
- `DsdPcmDecoder` wraps the source-pinned BSD-licensed `dsd2pcm` 96-tap filter. It keeps independent
  channel history, performs 8:1 decimation and emits interleaved float PCM. Any further resampling
  needed by the output remains a separate stage.
- `DsdPlaybackTask` owns the reader/output lifecycle on one worker, applies pause only between
  complete source packets, closes a blocked content stream on cancellation and never drains a
  cancelled or failed source.
- The foreground audio service and Player screen now connect Android's document picker to those
  components. They expose DSD-to-PCM, qualified native DSD and explicitly confirmed DoP, keep the
  music-app bridge mutually exclusive, report progress/routing/underruns, and provide notification
  pause, resume and stop controls.

The packet/format tests and native filter tests run without hardware. Android instrumentation also
checks the real JNI converter, channel separation, packet continuity, use after close, a complete
MediaStore DSF-to-AudioTrack foreground-service playback, and fail-closed native mode when no
exclusive USB route is selected.

```shell
git submodule update --init third_party/dsd2pcm
bash scripts/verify_dsp.sh
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

Direct USB PCM transport, endpoint/clock negotiation and device-specific native-DSD layout
qualification and the typed DSD-to-USB sink are now implemented; see
[the USB output scope and tests](USB_OUTPUT.md). The product-facing reader validates a file before
opening its output, and native modes still fail closed unless the selected route satisfies their
qualification. A DoP stream never enters a normal PCM mixer, volume control or resampler.

Specifications: [DoP 1.1](https://dsd-guide.com/sites/default/files/white-papers/DoP_openStandard_1v1.pdf),
[Sony DSF](https://dsd-guide.com/sites/default/files/white-papers/DSFFileFormatSpec_E.pdf),
[Philips DSDIFF 1.5](https://dsd-guide.com/sites/default/files/white-papers/DSDIFF_1.5_Spec.pdf).
Filter source and license:
[dsd2pcm at 6cfb3ba](https://github.com/clivem/dsd2pcm/tree/6cfb3bad54c103a74f15f0399fa8f435ecc3592a).
Native USB layout facts:
[Linux USB-audio quirks at b731337](https://github.com/torvalds/linux/blob/b7313376809292f0e6bf2d5750225c8b66e9ccda/sound/usb/quirks.c).
