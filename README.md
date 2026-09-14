# VirtualDAP

VirtualDAP is an Android music-container and audio-output project that carries decoded PCM from
isolated music applications to built-in audio or a selected USB DAC.

The consumer runtime is being changed to an ordinary-APK app container: no root, platform signing,
OEM allowlist or KVM permission is required by the intended product. The earlier AVF reference
provider is not sufficient for this requirement. See the
[rootless runtime and USB/DSD requirements](docs/ROOTLESS_RUNTIME.md) for the current implementation
boundary. The repository is not yet a completed install-and-boot consumer release.

The repository no longer presents a timed UI simulation as a working guest. Status changes are
driven by a real versioned bridge connection, validated PCM packets and the actual Android output
route.

## Audio bridge

The host socket receiver, output pipeline and the consumer container's Java streaming AudioTrack
interception are implemented and tested with a fixture app. See the [capture scope](docs/CONTAINER_AUDIO.md)
for verified behavior and remaining native/static/concurrent paths. The older full-OS reference captures at its
Audio HAL boundary; that reference does not make container capture complete.

The existing Android 13 guest HAL reference offers a normal mixed route for mainstream services and direct PCM profiles for
hi-res players. Compressed offload is intentionally absent so an app cannot bypass capture. The
host rebuilds `AudioTrack` on a format epoch, applies backpressure with blocking writes, monitors
disconnects/drops, enumerates real audio routes, and tries the exact source PCM first. If the host
or selected route rejects that format, a stateful compatibility converter negotiates float/16-bit,
supported sample rates and stereo downmix in that order. Every production sample-rate change uses
the source-pinned libsamplerate best-sinc filter, retains state across bridge packets and streams
bounded conversion slices instead of allocating one ratio-expanded packet. The UI distinguishes
unchanged PCM, software conversion, Android direct support and a mixer path; it never labels an
unverified output as bit-perfect. Reported queue latency is derived from submitted frames minus the
live AudioTrack playback head.

## Music application compatibility target

The intended capture point is shared by standard audio clients, not app-specific. Apple Music, Spotify, YouTube Music, TIDAL,
Qobuz, Amazon Music, Deezer, SoundCloud, Bandcamp, Plexamp, Poweramp, Neutron,
foobar2000 and other standard AudioTrack clients use the
same intended PCM path. Catalog inclusion is not proof that installation, login, decoding and
playback have been verified for that service. Individual compatibility results must be recorded
before any service is labeled verified.

UAPP is an audio-engine implementation reference, not a catalog music service or runtime dependency.
USB output, DoP, native DSD and DSD-to-PCM functionality belong inside VirtualDAP.

Subscriptions, regional restrictions, service login, Widevine, Play Integrity and guest image
certification remain controlled by each provider. VirtualDAP does not bypass DRM or attestation.

## Build and test the host

Requirements: JDK 17 and Android SDK 37 (the app still targets API 36 for the current Play policy).

```shell
git submodule update --init --recursive
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The Diagnostics tab includes a
clearly labeled host-only output tone test; it is disabled while guest playback is active.

The Player tab streams local DSF and uncompressed DSDIFF through DSD-to-PCM, qualified native DSD,
or explicitly confirmed DoP. DoP framing and the native DSD-to-PCM filter are described in
[DSD pipeline](docs/DSD_PIPELINE.md).
The opt-in internal direct USB engine, its tests and current limits are described in
[USB output](docs/USB_OUTPUT.md).
The DSD filter and high-quality sample-rate converter are built for all four Android ABIs with NDK
28.2.13676358; their license notices are included in APK assets.

## Build and test the Android 13 guest bridge

Requirements: CMake, Android NDK 28.2.13676358, Git and Python 3.

```shell
scripts/verify_guest.sh
```

See [Android 13 guest integration](docs/GUEST_ANDROID_13.md) for Soong/product configuration and
the audio integration. The retained legacy code imports the strict, streamed and hash-verified
[guest bundle format](docs/GUEST_BUNDLE.md), and connects only to one platform-signed runtime
provider; it is not the current consumer Music space screen. Stock third-party app permissions are intentionally not represented as sufficient to
mount or boot a full Android guest. A reference graphical Android Virtualization Framework provider,
authenticated vsock proxy and product integration boundary are documented in
[AVF platform runtime](docs/PLATFORM_RUNTIME.md).

## Wire protocol

- fixed little-endian handshake with magic, version, format, frame size, flags and stream epoch;
- length-delimited message headers with a 1 MiB hard payload limit;
- PCM payloads must contain complete frames;
- explicit format-change messages and monotonic packet sequence numbers;
- sequence-matched host submission ACKs keep at most one PCM packet in flight;
- periodic frames/dropped-bytes/reconnect counters;
- blocking socket and AudioTrack writes provide bounded backpressure, while reconnect attempts are
  rate-limited so AudioFlinger cannot spin when the host is unavailable.

The Kotlin and C++ implementations are independently unit/integration tested in CI.

The consumer app now targets Android 13 or later and integrates the source-built BlackBox
container. Broader app/API compatibility must be validated before a
consumer-ready release; the legacy HAL/AVF reference path is not a substitute for that validation.
