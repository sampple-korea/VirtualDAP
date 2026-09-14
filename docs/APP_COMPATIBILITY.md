# App compatibility evidence

Catalog membership is a target, not a compatibility certification. No subscription login, account
state, DRM verdict or provider policy is bypassed. Tests use an ordinary application UID.

| Package/build | Environment | Installation | Application start | Captured playback |
| --- | --- | --- | --- | --- |
| VirtualDAP fixture, source-built debug | Historical baseline: API 33 AOSP x86_64; API 36 Google APIs x86_64 | Verified base, signed feature split update, device-targeted binary-`toc.pb` APKS, and official bundletool 1.18.3 APKS probe | Verified, including feature-only class | Java streaming/static PCM, AAudio callback/write, OpenSL ES buffer queue, prebuffer/pause/resume/volume, two overlapping streams and individual release |
| VirtualDAP fixture, API 34 minimum / official-output build | API 36 Google APIs x86_64, ordinary UID; September 14, 2026 | Verified base, split update and device-targeted APKS | Verified, including feature-only class | Paced test receiver: Java streaming/static, AAudio callback/write, OpenSL ES, controls, overlapping capture and individual release; **not DAC output** |
| YouTube Music 8.09.50, version code 80950280, x86_64 | API 36 Google APIs x86_64, ordinary UID; September 14, 2026 | Verified copying the installed host base/split package into the container | Application.onCreate callback verified; subsequent visible sign-in-screen check **fails** | Not tested; login, DRM, UI navigation and music playback remain unverified |
| Apple Music, Spotify and remaining catalog services | — | Not yet verified | Not yet verified | Not yet verified |

The current minimum is API 34. The output policy now requires an officially supported bit-perfect
route; historical mixed-output playback results are not evidence for that route. Current capture
instrumentation uses a paced test receiver, separate from production output.

At commit `959a2c4`, GitHub run `34818766657` passed the host build and ordinary-UID fixture
instrumentation on both API 34 and API 36. This precedes the later unsupported-format rejection
changes and is not evidence for those changes. At `8b7b3f0`, local API 36 instrumentation additionally
verified AudioTrack PCM8 write/start rejection and cleanup, AAudio IEC61937 rejection, and OpenSL
PCM8 rejection, followed by the existing supported capture/controls/split-install regression suite.
The YouTube Music result above used that same build's installed-app import/start smoke (1 test,
35.566 seconds). It proves application initialization only, not a usable signed-in music session.

The extended September 14 check exposed a later Activity startup crash: Android 16's
`MediaRouter2.getSystemRoutes` rejected the container application's caller attribution. The adapter
now attributes only this process's own call to the real host package, without changing permissions,
proxy-router flags or results. The independent fixture exercises real `MediaRouter2` discovery;
the local API 36 regression suite passed all 11 tests after this change.

The next startup attempt reached a missing `READ_GSERVICES` permission. The host now declares this
read permission normally in its manifest; the tested emulator defines it as `normal`, and package
inspection confirmed Android granted it at installation. There is no provider-access bypass or
account permission transfer. After that change the visible `Sign in` check still timed out
(110.808 seconds total test time), with the app reporting Google Play services unavailable inside
the container. Host-installed Google Play services alone is not evidence of container service
availability. This remains a failed UI compatibility result and blocks claiming a usable YouTube
Music session or publishing a release on that basis.
The full local API 36 suite was rerun after the permission declaration: 11 tests passed in
109.973 seconds. The debug build, lint, 98 JVM tests, 11 prepared-source checks and APK output
boundary check also passed; none of these results substitutes for the failed commercial-app UI check.

## Optional local compatibility smoke

The instrumentation APK accepts `externalApk` (a filename in the debug host's private cache) and
`externalPackage`. This mode checks import and Application.onCreate only; it deliberately does not
claim playback verification.
It uses the test-only receiver so import/start checks do not require a physical official-output DAC.
Optional `externalUiText` additionally requires an exact, visible accessibility label belonging to
the imported package to remain on screen for three seconds after initialization. This distinguishes
an actual app screen from the host launcher or a startup callback preceding a crash. It does not
click login controls, submit credentials or establish playback compatibility. Choose the label for
the app version and emulator locale being tested.

```sh
adb push /path/to/legitimately-obtained.apks /data/local/tmp/virtualdap-app.apks
adb shell run-as com.virtualdap.host cp /data/local/tmp/virtualdap-app.apks cache/compat-app.apks
adb shell am instrument -w -r -e class com.virtualdap.host.ContainerInstrumentedTest \
  -e externalApk compat-app.apks -e externalPackage com.example.music \
  com.virtualdap.host.test/androidx.test.runner.AndroidJUnitRunner
```

For regular users, the Music space supports file import and copying installed catalog music apps.
Only APK code is copied; the original app's private data/accounts are not accessed.
