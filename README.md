# VirtualDAP

VirtualDAP is an Android host and guest-audio platform that carries decoded PCM from an isolated
Android 13 music environment to the host's built-in output or a selected USB DAC.

The repository no longer presents a timed UI simulation as a working guest. Status changes are
driven by a real versioned bridge connection, validated PCM packets and the actual Android output
route.

## Audio path

```text
music app → guest AudioFlinger → VirtualDAP primary HAL
          → abstract Unix socket (format + PCM + counters)
          → host foreground service → AudioTrack → selected output / USB DAC
```

The guest policy offers a normal mixed route for mainstream services and direct PCM profiles for
hi-res players. Compressed offload is intentionally absent so an app cannot bypass capture. The
host rebuilds `AudioTrack` on a format epoch, applies backpressure with blocking writes, monitors
disconnects/drops, enumerates real audio routes, and distinguishes Android direct support from a
mixer path. It never labels an unverified output as bit-perfect.

## Supported music applications

The capture point is system-wide, not app-specific. Apple Music, Spotify, YouTube Music, TIDAL,
Qobuz, Amazon Music, Deezer, SoundCloud, Bandcamp, Plexamp, Poweramp, Neutron, USB Audio Player
PRO (using its Android/AudioTrack driver), foobar2000 and other standard AudioTrack clients use the
same PCM path.

Subscriptions, regional restrictions, service login, Widevine, Play Integrity and guest image
certification remain controlled by each provider. VirtualDAP does not bypass DRM or attestation.

## Build and test the host

Requirements: JDK 17 and Android SDK 35.

```shell
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The Diagnostics tab includes a
clearly labeled host-only output tone test; it is disabled while guest playback is active.

## Build and test the Android 13 guest bridge

Requirements: CMake, Android NDK 28.2.13676358, Git and Python 3.

```shell
scripts/verify_guest.sh
```

See [Android 13 guest integration](docs/GUEST_ANDROID_13.md) for Soong/product configuration and
the guest image boundary.

## Wire protocol

- fixed little-endian handshake with magic, version, format, frame size, flags and stream epoch;
- length-delimited message headers with a 1 MiB hard payload limit;
- PCM payloads must contain complete frames;
- explicit format-change messages and monotonic packet sequence numbers;
- periodic frames/dropped-bytes/reconnect counters;
- blocking socket and AudioTrack writes provide bounded backpressure, while reconnect attempts are
  rate-limited so AudioFlinger cannot spin when the host is unavailable.

The Kotlin and C++ implementations are independently unit/integration tested in CI.
