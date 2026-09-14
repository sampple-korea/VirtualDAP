# VirtualDAP

VirtualDAP runs music apps in an ordinary-UID app container and carries their decoded PCM to a
selected Android official bit-perfect output. Minimum Android version: **14 / API 34**.

The container shares the device's Android framework. It supports APK and compatible split-package
imports, separate app data, app launch/stop, and Java AudioTrack, native AAudio and OpenSL ES PCM
capture. See [capture scope](docs/CONTAINER_AUDIO.md) and [installation](docs/CONTAINER_INSTALLATION.md).
Catalog entries identify target services; installation, login and playback are not yet verified
for every commercial service. Provider login, subscriptions, DRM and attestation remain provider-controlled.

## Output

Only a device advertising the exact input format with Android's official bit-perfect mixer behavior
can play. The user selects that output explicitly. Unsupported device, DAC, sample rate, channel
layout or PCM encoding produces an error. There is no automatic output conversion, ordinary-mixer
fallback or direct-USB fallback.

One active output stream and unity application gain are required. Crossfade/overlap and non-unity
application volume are rejected. Route changes or lost mixer preferences stop submission.

Local DSF and uncompressed DSDIFF playback supports explicitly selected DSD-to-PCM conversion and
confirmed DoP. Both use the same official output contract. See [official output](docs/OFFICIAL_OUTPUT.md)
and [DSD pipeline](docs/DSD_PIPELINE.md).

The direct USB implementation and its tests are retained for future compatibility development.
Its Kotlin sources are compiled only into JVM tests, and its native library is excluded from the
product build. See [compatibility source](compatibility/direct_usb/README.md). No separate Android OS
boot, guest image importer, privileged VM provider or emulated guest display is included.

## Build and verify

Requirements: JDK 17+, Android SDK 37, NDK 28.2.13676358 and CMake 3.22.1.
The app targets API 36.

```sh
git submodule update --init --recursive
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
bash scripts/verify_container_transport.sh
bash scripts/verify_dsp.sh
python3 scripts/verify_container_sources.py
python3 scripts/verify_product_boundary.py app/build/outputs/apk/debug/app-debug.apk
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Instrumentation separates real container capture (using a paced, test-only socket receiver) from
official-output rejection tests. The receiver emits no sound and is not hardware playback evidence.
The Diagnostics tone uses the same official output restrictions as music playback.
Physical DAC validation is outside the current test scope.
