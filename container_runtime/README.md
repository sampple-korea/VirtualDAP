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
- Seven prepared-source privacy/native integration regression checks pass.
- An unrooted API 36 x86_64 emulator installs the independent fixture APK **inside** the music space,
  launches its Application/Activity in a container process, persists its private launch counter and
  reports the actual process startup back to the host. The fixture is not installed in Android's
  system package manager. This is an instrumentation test, not a timer-based UI simulation.
- The same fixture's 48 kHz PCM16 and 96 kHz float reach the host audio service. Tests cover
  prebuffering, mute/unity gain, pause/resume, track release and zero reported dropped bytes.

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

Java streaming PCM interception is now implemented; see [its scope and protocol](../docs/CONTAINER_AUDIO.md).
Streaming-service login/DRM compatibility, AAudio/OpenSL ES paths,
multi-track behavior and full split-APK/feature-module coverage still require implementation and
validation. A successful app launch alone is not proof of original-format audio capture. The USB clock/
descriptor and DSD DSP tests likewise do not prove an integrated USB isochronous output driver.
