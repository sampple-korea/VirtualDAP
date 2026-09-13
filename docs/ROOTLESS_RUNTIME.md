# Ordinary-APK runtime requirements

The product must run after installing and configuring a normal APK. Root, a platform certificate,
an OEM allowlist, KVM access and Android Virtualization Framework privileges are not requirements.
This supersedes the earlier AVF deployment model; `platform_runtime` is a reference integration,
not the default consumer runtime.

The user accepted an app container when it meets music isolation and original-audio delivery.
The selected direction is a source-pinned, adapted BlackBox-family container on **Android 13/API 33
and later**, without a manufacturer/device allowlist. Its virtual applications share the host
Android framework; the UI must not claim to boot a separate Android 13 OS.

See the [source and maintenance audit](VIRTUALIZATION_RESEARCH.md). QEMU TCG build scripts remain a
recorded exploration, not the selected production engine. The rejected mandatory system-emulation
design had these requirements:

- package the Android-native engine and its libraries with the APK, including 16 KiB ELF alignment;
- launch from `ApplicationInfo.nativeLibraryDir`, not downloaded executable code in writable data;
- run the guest CPU, memory, block devices, network and virtual audio device without `/dev/kvm`;
- keep mutable guest state in private app storage and validate the downloaded base before first use;
- display the guest over a private Unix socket with in-app input handling;
- capture decoded audio at the guest's virtual hardware/Audio HAL boundary and send typed data over
  the existing bounded audio bridge.

The required deliverables are the embedded container, APK/split-APK installation, app launch and
shutdown, decoded-audio interception, and ordinary-UID runtime tests. They cannot be inferred from
a successful host APK build. The earlier Android 13 guest HAL and AVF provider remain references.

## Output requirements

Normal playback must work on built-in and system-routed outputs. On Android 14 and later, request
`AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT` for a matching USB format, then inspect the actual
route and the current OS mixer preference. An exclusive USB driver obtains access using
`UsbManager.requestPermission` and `UsbManager.openDevice`, then wraps the granted descriptor in
native USB code. It must not try to open arbitrary `/dev/bus/usb` nodes or require root.

DSD support includes DoP framing, explicit native-DSD device capabilities and DSD-to-PCM fallback.
DoP and native DSD must never be sent through a PCM mixer or resampler. The virtual high-resolution
device must advertise formats it actually implements. Each UI status identifies source format,
transport, conversion and selected/actual output separately.

## UAPP reference inspection

UAPP is a reference for implementing VirtualDAP's own USB/DSD audio engine, not an app that users
must install, launch or select from the music-service catalog. Earlier installer/ABI smoke probes
using its supplied APK were temporary engineering tests, not the intended product integration.

The user-provided APK set identifies itself as USB Audio Player PRO 7.1.2.3 (7123). Inspection found:

- normal Android USB permission/open-device calls followed by transfer of the granted file descriptor;
- `libusb_wrap_sys_device`, alternate-interface selection and asynchronous/isochronous USB functions;
- separate DoP, native-DSD and DSD-to-PCM paths;
- model-specific handling for some built-in high-resolution chips.

These observations inform interoperability. UAPP's APKs, decompiled code and proprietary native
libraries are not included in this repository. Implement against public USB/DoP specifications and
appropriately licensed source libraries.

References: [Android USB mixer attributes](https://developer.android.com/media/platform/improve-audio-playback),
[libusb device handle wrapping](https://libusb.sourceforge.io/api-1.0/group__libusb__dev.html),
[DoP open standard](https://dsd-guide.com/dop-open-standard),
[QEMU system emulation](https://www.qemu.org/docs/master/system/introduction.html).
