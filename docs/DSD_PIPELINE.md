# DSD transport and conversion

The source must actually provide DSD data for native DSD or DoP output. PCM produced by a streaming
music app is kept as PCM; relabeling or repacking it cannot recover an original DSD stream.

Implemented components:

- `DsdFormat` describes the one-bit sample rate, channel count and source bit order.
- `DopEncoder` implements the DoP 1.1 marker sequence, preserves markers across packet boundaries,
  keeps channel ordering and handles packed 24-bit or padded four-byte UAC subslots. Truncated
  frames are rejected.
- `DsdOutputPlanner` requires explicit output capabilities and a bit-transparent route before
  selecting native DSD or DoP. A generic PCM capability does not imply native DSD support.
- `DsdPcmDecoder` wraps the source-pinned BSD-licensed `dsd2pcm` 96-tap filter. It keeps independent
  channel history, performs 8:1 decimation and emits interleaved float PCM. Any further resampling
  needed by the output remains a separate stage.

The packet/format tests and native filter tests run without hardware. Android instrumentation also
checks the real JNI converter, channel separation, packet continuity, and use after close.

```shell
git submodule update --init third_party/dsd2pcm
bash scripts/verify_dsp.sh
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

Direct USB PCM transport and endpoint/clock negotiation are now implemented; see
[the USB output scope and tests](USB_OUTPUT.md). The complete source-to-DSD-output connection and
device-specific native-DSD qualification remain integration work.
The UI must not label those paths available merely because the framing and converter components
build successfully. A DoP stream must never enter a normal PCM mixer, volume control or resampler.

Specifications: [DoP 1.1](https://dsd-guide.com/sites/default/files/white-papers/DoP_openStandard_1v1.pdf),
[Sony DSF](https://dsd-guide.com/sites/default/files/white-papers/DSFFileFormatSpec_E.pdf).
Filter source and license:
[dsd2pcm at 6cfb3ba](https://github.com/clivem/dsd2pcm/tree/6cfb3bad54c103a74f15f0399fa8f435ecc3592a).
