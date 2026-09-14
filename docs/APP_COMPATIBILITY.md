# App compatibility evidence

Catalog membership is a target, not a compatibility certification. No subscription login, account
state, DRM verdict or provider policy is bypassed. Tests use an ordinary application UID.

| Package/build | Environment | Installation | Application start | Captured playback |
| --- | --- | --- | --- | --- |
| VirtualDAP fixture, source-built debug | Historical baseline: API 33 AOSP x86_64; API 36 Google APIs x86_64 | Verified base, signed feature split update, device-targeted binary-`toc.pb` APKS, and official bundletool 1.18.3 APKS probe | Verified, including feature-only class | Java streaming/static PCM, AAudio callback/write, OpenSL ES buffer queue, prebuffer/pause/resume/volume, two overlapping streams and individual release |
| Apple Music, Spotify and remaining catalog services | — | Not yet verified | Not yet verified | Not yet verified |

The current minimum is API 34. The output policy now requires an officially supported bit-perfect
route; historical mixed-output playback results are not evidence for that route. Current capture
instrumentation uses a paced test receiver, separate from production output.

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
