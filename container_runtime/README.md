# Consumer music app container

This is an ordinary-UID app container, not a second Android OS. The host minimum is Android 13
(API 33); no manufacturer allowlist, root, AVF permission or platform signing is required by this
integration. That is an architecture requirement, not proof of compatibility with every device/app.

## Source provenance

- Blacks-BlackBox: `40282a7bf4500948cfd598fc67e6e63114b26dd9`, Apache-2.0.
- JingMatrix/Dobby: `05a09ac6807a6bb1726350e40ea4b127c1c79809`, Apache-2.0.
- Both are pinned Git submodules. Their source licenses are copied into APK assets.
- `scripts/prepare_container_sources.py` applies checked adaptations to generated build sources,
  leaving the pinned checkouts unchanged. Java overrides are under `core/overrides`.
- Native hooks build from source for arm64-v8a, armeabi-v7a, x86 and x86_64, with 16 KiB ELF
  alignment. Runtime code patching uses the actual page size, not a fixed 4096-byte assumption.

Adaptations remove automatic external crash submission, global exception swallowing, microphone
unmute overrides, unnecessary permissions and VPN/daemon services. They correct Android version
tests, ABI extraction and native vararg/ART bounds bugs. The exact private lifecycle endpoint is
explicitly routed to the host, whose provider checks the caller UID.

## Verified 2026-09-14

- Integrated host/debug and separate music fixture APKs build successfully.
- Host unit tests and lint pass.
- Ten prepared-source privacy/native/signature/install integration regression checks pass.
- Unrooted API 33 and API 36 x86_64 emulators install the independent fixture APK **inside** the music space,
  launches its Application/Activity in a container process, persists its private launch counter and
  reports the actual process startup back to the host. The fixture is not installed in Android's
  system package manager. This is an instrumentation test, not a timer-based UI simulation.
- The same fixture's Java 48 kHz PCM16, 96 kHz float and 44.1 kHz static-loop PCM plus native
  88.2 kHz PCM16 AAudio callback, 96 kHz float AAudio blocking-write and 48 kHz PCM16 OpenSL ES
  buffer-queue streams reach the host audio service. Tests cover prebuffering, timed writes, callback
  and queue contracts, mute/unity gain, pause/flush/resume, track release and zero reported dropped
  bytes.
- Imported APK signatures are verified, tampered updates are rejected, and the existing app remains
  installed after rejection. Updates require the same current signing certificates; certificate
  rotation migration is not yet implemented and is conservatively rejected.
- The container instrumentation suite passes on API 33. CI runs the same installed-APK suite
  on API 33 and 36, separate from the host build job.
- Manifest-based split selection and atomic directory publication are implemented. The API 33
  instrumented fixture loads a class found only in a separately signed feature APK after an update.
  The API 36 probe also imports the fixture from an official bundletool 1.18.3 APKS; CI exercises a
  deterministic binary-`toc.pb` archive with incompatible ARM64 and compatible x86_64 variants.
  See [installation guarantees and scope](../docs/CONTAINER_INSTALLATION.md).

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
python3 scripts/verify_container_sources.py
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.virtualdap.host.ContainerInstrumentedTest \
  com.virtualdap.host.test/androidx.test.runner.AndroidJUnitRunner
```

The test fixture is included only in the instrumentation APK, never the production host assets.
It requests no network, microphone or storage permissions.

## Not yet verified or complete

Java streaming/static PCM plus native AAudio and OpenSL ES output interception are now implemented; see
[its scope and protocol](../docs/CONTAINER_AUDIO.md).
Independent overlapping tracks, source formats and individual release are exercised on API 33/36.
Streaming-service login/DRM compatibility, sample-aligned gapless transitions and broader
commercial-app coverage still require implementation and validation. Encrypted/proprietary package
wrappers, JSON-only bundletool tables and OBB/Play Asset Delivery installation are outside the current
import path. A successful app launch alone is not proof of original-format audio capture.
The integrated direct USB PCM path and its separate hardware-free validation are described in
[USB output](../docs/USB_OUTPUT.md); physical DAC output has not been measured.
