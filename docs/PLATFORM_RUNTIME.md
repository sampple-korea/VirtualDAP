# AVF platform runtime

`platform_runtime` is the reference privileged provider for physical hosts that ship Android's
Virtualization Framework with graphical custom-VM support. It is intentionally a product module,
not code hidden inside the ordinary APK: mounting/booting an imported OS and attaching crosvm to an
Android `Surface` require platform APIs, privileged permissions, platform signing, AVF device
support, and matching SELinux policy.

## Host product requirements

- 64-bit ARM or x86 with KVM/pKVM and `android.software.virtualization_framework`;
- `com.android.virt` with custom VM, graphics, input, network and vsock enabled;
- the platform `gfxstream` backend and crosvm Android display service;
- at least 3 GiB available RAM for the guest plus host overhead;
- a crosvm-compatible U-Boot payload at `/system/etc/virtualdap/u-boot.bin`;
- VirtualDAP host and runtime APKs signed with the product platform certificate;
- an AOSP branch whose `vmlauncher_app` domain includes display/input/AVF Binder and `AF_VSOCK`
  listen access. The included system-ext `seapp_contexts` assigns only this privileged package to
  that existing narrow domain; do not replace it with an unconfined domain.

Inherit the product fragment and arrange the bootloader copy in the device product:

```make
$(call inherit-product, path/to/VirtualDAP/platform_runtime/virtualdap_platform_runtime.mk)

PRODUCT_COPY_FILES += \
    path/to/verified/u-boot_crosvm_aarch64.bin:$(TARGET_COPY_OUT_SYSTEM)/etc/virtualdap/u-boot.bin
```

The repository does not ship a generic bootloader binary or signing keys. Those are build outputs
bound to the host architecture and product trust configuration.

## Guest disk contract

`payload/guest.img` is a crosvm/U-Boot bootable GPT disk. For an Android 13 Cuttlefish-derived
product it contains boot/vendor_boot and dynamic system/vendor partitions, a writable userdata
partition, virtio GPU/input/block/net/vsock kernel drivers, and
`ro.vendor.virtualdap.socket_name=vsock:2:45000`. Its audio product inherits
`guest_os/virtualdap_audio.mk`; crosvm speaker output is disabled so decoded PCM cannot bypass the
VirtualDAP HAL.

The host APK retains and rechecks the immutable imported base. On first launch, the provider copies
it across the Binder FD into a private writable disk and verifies its SHA-256 again. Subsequent
launches reuse that working disk with its saved apps, logins and downloads. A mutable disk is not
compared with the base hash: normal guest writes necessarily change it. Its fixed byte count and
recorded base provenance are checked instead. Android verified boot inside the guest remains
responsible for signed system partitions.

Each base hash gets a separate disk directory, activated only after a complete verified copy.
Selecting a different base does not erase the previous guest's data. A truncated disk or a missing
provenance record produces an error without resetting it. Prior guest disks remain in the runtime's
private storage; clearing that application's storage explicitly removes them.

## Runtime behavior

1. The host accepts only one provider resolved for `com.virtualdap.runtime.GUEST_RUNTIME`, and
   verifies that it is both a system app and platform-signed.
2. The provider validates the bundle metadata and disk while copying, starts a vsock listener, and
   injects a fresh 256-bit token into `androidboot.virtualdap.bridge_token`.
3. The guest HAL connects to host CID 2 / port 45000 and sends the token before its PCM handshake.
   The native proxy uses constant-time comparison, then connects to the host abstract socket. The
   host accepts that proxy UID only because it was obtained from the verified provider package.
4. AVF starts the unprotected custom VM. Imported code is deliberately never represented as a
   protected pVM payload. The provider disables crosvm speaker/microphone and enables gfxstream,
   touch and keyboard.
5. The Guest screen attaches a `Surface` and forwards timestamped Android motion/key events.
   Blocking transport and AudioTrack writes propagate bounded backpressure to guest AudioFlinger.

Run the checks that do not need an AOSP tree with:

```shell
scripts/verify_platform_runtime.sh
```

The authoritative provider build is `m VirtualDapPlatformRuntime` inside the matching AOSP product
tree. Real-device validation was outside this project run; the repository therefore does not claim
that a particular retail device exposes the necessary AVF graphics feature.
