# App compatibility evidence

Catalog membership is a target, not a compatibility certification. No subscription login, account
state, DRM verdict or provider policy is bypassed. Tests use an ordinary application UID.

| Package/build | Environment | Installation | Application start | Captured playback |
| --- | --- | --- | --- | --- |
| VirtualDAP fixture, source-built debug | API 33 AOSP x86_64; API 36 Google APIs x86_64 | Verified base and signed feature split update | Verified, including feature-only class | 48 kHz PCM16, 96 kHz float, prebuffer/pause/resume/volume, two overlapping streams and individual release |
| Apple Music, Spotify and remaining catalog services | — | Not yet verified | Not yet verified | Not yet verified |

## Historical installer probe — UAPP is not a catalog target

UAPP's role is solely an implementation reference for the internal USB/DoP/DSD engine.
Its supplied 7.1.2.3 ARM64 APK set was temporarily used to probe installer and ABI behavior:
split import/signature verification succeeded; Application startup failed when loading
`libauogg.so` (AARCH64 code in an x86_64 process). Playback was not tested.

This result is an architecture-boundary issue in the container's foreign-ABI loading, not
evidence that UAPP works or fails on a native ARM64 phone. Although this emulator advertises native
bridge support, the container-created class-loader namespace did not use that translation path.
Native ARM64 runtime testing or an implemented foreign-ABI bridge is still needed.

User-supplied UAPP archive SHA-256:
`4d0f0ff75dbaeafbf5757702c0d726df1ec856b8c8975e9fb0f631a1d1c8f07c`.
Its proprietary APK/native libraries are not redistributed in this repository.

The same set was temporarily used to check host-installed-app copying, which passed.
UAPP has since been removed from the catalog and package-visibility queries to match its reference-only
role. These probes do not make UAPP a supported music-service target or a dependency of VirtualDAP.

## Optional local compatibility smoke

The instrumentation APK accepts `externalApk` (a filename in the debug host's private cache) and
`externalPackage`. This mode checks import and Application.onCreate only; it deliberately does not
claim playback verification.

```sh
adb push /path/to/legitimately-obtained.apks /data/local/tmp/virtualdap-app.apks
adb shell run-as com.virtualdap.host cp /data/local/tmp/virtualdap-app.apks cache/compat-app.apks
adb shell am instrument -w -r -e class com.virtualdap.host.ContainerInstrumentedTest \
  -e externalApk compat-app.apks -e externalPackage com.example.music \
  com.virtualdap.host.test/androidx.test.runner.AndroidJUnitRunner
```

For regular users, the Music space supports file import and copying installed catalog music apps.
Only APK code is copied; the original app's private data/accounts are not accessed.
