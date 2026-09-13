# VirtualDAP guest bundle v1

The host imports a `.vdap` file through Android's system document picker. The file is a ZIP whose
first member is `manifest.properties` and whose second and final member is `payload/guest.img`.
The strict ordering lets the host reject an incompatible image before writing gigabytes of data.

Example manifest:

```properties
formatVersion=1
androidApi=33
architecture=arm64-v8a
backend=virtualdap-platform-v1
displayName=VirtualDAP Android 13
buildFingerprint=virtualdap/aosp_arm64/virtualdap:13/TQ3A.230901.001/user/release-keys
imageFile=payload/guest.img
imageBytes=4294967296
imageSha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
services=aosp
attestation=not-certified
```

Accepted architectures are `arm64-v8a` and `x86_64`; the import must match a 64-bit host ABI.
`androidApi` is exactly 33 and `backend` is exactly `virtualdap-platform-v1`. `services` is either
`aosp` or `user-provided-gms`. `attestation` is either `not-certified` or `oem-certified`.

The importer applies the following rules:

- the archive contains exactly the two expected entries and cannot choose extraction paths;
- manifest size is capped at 64 KiB and the image at 24 GiB;
- declared and extracted byte counts must match;
- SHA-256 is calculated while extracting, before activation;
- SHA-256 is recalculated immediately before every guest launch;
- a fully verified staging directory atomically replaces the previous installation where the
  filesystem permits; a failed import leaves the previous guest active.

SHA-256 establishes content consistency, not publisher identity or Android certification. An
`oem-certified` value is descriptive metadata consumed by the UI; the runtime and service provider
remain responsible for verifying their own signed boot chain. VirtualDAP does not accept keyboxes,
spoof device identity, or alter Play Integrity responses.

Create a conforming bundle with:

```shell
scripts/package_guest_bundle.py \
  --image out/target/product/virtualdap/guest.img \
  --output out/virtualdap-android13-arm64.vdap \
  --architecture arm64-v8a \
  --display-name "VirtualDAP Android 13" \
  --fingerprint "$(adb shell getprop ro.build.fingerprint)"
```

## Runtime provider boundary

The regular host APK cannot safely mount a full Android disk or create virtual machines on stock
Android. A device integration therefore supplies exactly one platform-signed service for action
`com.virtualdap.runtime.GUEST_RUNTIME`. The service declares
`com.virtualdap.host.permission.BIND_GUEST_RUNTIME` and implements protocol version 1 from
`IGuestRuntimeService.aidl` (protocol version 2).

The host verifies that the resolved provider is a system app signed with the same certificate as
the Android platform. It passes a read-only `ParcelFileDescriptor`, never its private filesystem
path. The provider owns VM/container lifecycle, display/input plumbing, network setup, verified
boot, and the guest-side socket or vsock proxy. Runtime states are the integer constants documented
in `GuestRuntimeController.RuntimeState`. Once running, the host attaches an Android `Surface` and
forwards cloned `MotionEvent` and `KeyEvent` objects through Binder; the provider must preserve their
coordinates, pointer IDs and event times when injecting them into the guest compositor/input stack.
