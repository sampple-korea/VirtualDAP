# VirtualDAP Guest Android 13

VirtualDAP standardizes on **Android 13 (AOSP 13, API 33), 64-bit** for the music guest.
Android 13 is the last Android release whose audio HAL is defined by HIDL 7.1 and whose AOSP
default HIDL service officially wraps a legacy `audio.primary.*` module. This lets the guest use
the small, auditable PCM bridge in this repository while retaining a modern media framework.

## What is in this repository

- `VirtualAudioHAL.cpp`: complete legacy primary output HAL used behind Android 13's HIDL 7.1
  wrapper. It accepts proportional PCM only and deliberately does not advertise compressed
  offload, ensuring decoded music reaches the bridge.
- `BridgeTransport.cpp`: bounded, frame-aligned Unix-domain transport with reconnection,
  stream-format epochs, sequence numbers and drop counters.
- `audio_policy_configuration.xml`: normal 44.1/48 kHz mixed output plus direct PCM profiles
  from 44.1 through 384 kHz, 16/24/32-bit integer and float where applicable.
- `Android.bp` and `virtualdap_audio.mk`: AOSP/Soong integration files.

## AOSP 13 product integration

Place `guest_os` under the device tree (for example `device/virtualdap/audio`) and inherit its
product fragment from the guest product makefile:

```make
$(call inherit-product, device/virtualdap/audio/virtualdap_audio.mk)
```

The Android 13 product also needs the stock audio HIDL wrapper and its legacy implementations:

```make
PRODUCT_PACKAGES += \
    android.hardware.audio.service \
    android.hardware.audio@7.1-impl \
    android.hardware.audio.effect@7.0-impl
```

The device VINTF manifest must publish the core and effects factories:

```xml
<hal format="hidl">
    <name>android.hardware.audio</name>
    <transport>hwbinder</transport>
    <version>7.1</version>
    <interface><name>IDevicesFactory</name><instance>default</instance></interface>
</hal>
<hal format="hidl">
    <name>android.hardware.audio.effect</name>
    <transport>hwbinder</transport>
    <version>7.0</version>
    <interface><name>IEffectsFactory</name><instance>default</instance></interface>
</hal>
```

For a Linux-container backend, the guest and host share the abstract Unix socket namespace: keep
`ro.vendor.virtualdap.socket_name=virtualdap_audio_v1` and do not isolate that namespace, or proxy
`@virtualdap_audio_v1`. For a hardware VM, set
`ro.vendor.virtualdap.socket_name=vsock:2:45000`; CID 2 is the crosvm host and the platform runtime
proxies port 45000 into the host app's authenticated abstract socket. The runtime also injects the
per-start `androidboot.virtualdap.bridge_token`; the HAL sends its decoded 32 bytes before the wire
handshake and the proxy uses a constant-time comparison before granting access. Both transports are
full duplex. After each PCM packet, the host returns a sequence-matched submission ACK only after
the blocking AudioTrack write completes. That single-packet window propagates backpressure to
AudioFlinger instead of growing a hidden socket queue. When disconnected, the HAL alone applies
real-time pacing so music applications cannot spin.

## Music service compatibility

Apple Music, Spotify, YouTube Music, TIDAL, Qobuz, Amazon Music, Deezer, SoundCloud and local
players all converge on AudioFlinger/AudioTrack when compressed offload and proprietary USB
drivers are disabled. The HAL therefore handles them without per-app hooks.

That audio compatibility does not manufacture service certification. AOSP images do not include
Google Mobile Services, Widevine provisioning or Play certification. Distributors must not bundle
proprietary GMS components without permission. Users may import a lawfully obtained, compatible
guest image. VirtualDAP does not spoof Play Integrity, hardware attestation, Widevine level, or
device identity.

## Verification

Run:

```shell
scripts/verify_guest.sh
```

It runs the native socket integration test, cross-compiles the transport for arm64 and x86_64,
checks the complete HAL against pinned Android 13 AOSP headers with warnings as errors, and
validates the audio policy. A full `m audio.primary.virtualdap` build remains the authoritative
integration test inside an AOSP 13 source tree.
