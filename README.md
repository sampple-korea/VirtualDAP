# VirtualDAP

VirtualDAP runs music apps in an ordinary-UID app container and carries their decoded PCM to a
selected USB DAC output. Minimum Android version: **14 / API 34**. The Korean interface opens on
music apps; diagnostics, output tone and local DSD live in the compact Tools menu.
The latest user decisions and remaining completion evidence are recorded in
[product requirements](docs/PRODUCT_REQUIREMENTS.md).

## APK 다운로드 · 알파 버전

[VirtualDAP 0.1.0-alpha.2 APK 다운로드](https://github.com/sampple-korea/VirtualDAP/releases/download/v0.1.0-alpha.2/VirtualDAP-0.1.0-alpha.2.apk)
· [설치 안내와 제한 사항](https://github.com/sampple-korea/VirtualDAP/releases/tag/v0.1.0-alpha.2)

Android 14 이상, 루팅 불필요. 기본 USB 출력과 한국어 음악 앱 중심 화면을 제공하는 초기 공개 테스트입니다.
Apple Music·Spotify 등 모든 음악 서비스의 로그인·재생이나 실제 DAC 호환성이 검증된 완성판은 아닙니다.
alpha.2는 USB 원본 포맷 선택과 음악 앱의 재생 서비스 검색을 개선하고, 재생 알림을 한국어화했습니다.
공개 alpha.1과 같은 서명이며, 해당 버전 위에 업데이트 설치하는 것을 확인했습니다.
기존 개발용 APK와 서명이 다르면 업데이트 설치가 안 될 수 있으며, 삭제 시 내부 앱·계정 데이터가 지워집니다.

The container shares the device's Android framework. It supports APK and compatible split-package
imports, separate app data, app launch/stop, and Java AudioTrack, native AAudio and OpenSL ES PCM
capture. See [capture scope](docs/CONTAINER_AUDIO.md) and [installation](docs/CONTAINER_INSTALLATION.md).
Catalog entries identify target services; installation, login and playback are not yet verified
for every commercial service. Provider login, subscriptions, DRM and attestation remain provider-controlled.

## Output

**USB audio is the default mode.** After the user grants Android USB access and selects a DAC,
the native transport negotiates USB Audio Class formats and sends audio directly. Exact source
formats are preferred; necessary rate/channel/encoding conversion is reflected in output status.
USB software volume initially starts at 25%; non-unity gain is not bit-perfect. This is not a
guarantee that every phone/DAC works. FreeDSP on SM-S938N is a user-reported official-path limitation,
not yet a verified direct-USB playback result.

**Official bit-perfect is an advanced mode.** Only a device advertising the exact input format
with Android's official bit-perfect mixer behavior can play in this mode. Unsupported formats
are rejected; there is no output conversion. Neither mode automatically falls back to the other
or to the ordinary Android mixer/speaker.

One active output stream is required. Official mode also requires unity application gain.
Crossfade/overlap is currently rejected. Device loss stops playback without speaker fallback.

Local DSF and uncompressed DSDIFF playback supports explicitly selected DSD-to-PCM conversion and
confirmed DoP and qualified native DSD over USB. Official mode supports exact PCM and DoP only.
See [official output](docs/OFFICIAL_OUTPUT.md)
and [DSD pipeline](docs/DSD_PIPELINE.md).

The direct USB implementation is included in the product, along with its existing regression tests.
See [USB source](compatibility/direct_usb/README.md). No separate Android OS
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
The Tools tone uses the selected output mode and does not certify the music-app capture path.
Physical DAC validation is outside the current test scope.

A signed, limited alpha is available above. See [release evidence and future gates](docs/RELEASE_READINESS.md);
fixture and independent local-player results do not imply commercial-service login or subscription
playback is verified.
