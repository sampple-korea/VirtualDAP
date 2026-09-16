# App compatibility evidence

Catalog membership is a target, not a compatibility certification. No subscription login, account
state, DRM verdict or provider policy is bypassed. Tests use an ordinary application UID.

## User-reported compatibility gap after alpha 2

The user reports Apple Music opening but login and other operations loading indefinitely, and
YouTube Music not opening, while their supplied multi-account app can open and sign in to these
apps. These are unresolved product failures, not successes established by a welcome-screen test.
Separately, the signed alpha 2's host-installed Apple Music import/welcome check passed locally in
54.508 seconds. A supervised, no-account inspection reached the actual catalog after Continue,
declining optional diagnostics and leaving explicit content disabled. This does not contradict
the user's login failure: no credentials, subscription or music playback were tested.

The supplied reference APK is `com.excelliance.multiaccounts` 5.7.8 (668), min API 23 / target 35,
SHA-256 `c4edff2f35f7f664f13ed400c99a3cc9d78619070bf4873553964970df3be321`.
Static interoperability inspection found an embedded runtime archive, separate core process,
account-selection activities, a Binder account-manager interface and authenticator sessions with
service-connection/death callbacks; Google service/framework packages are explicitly handled.
Several implementations are native/obfuscated and the decompiler reported six errors. These
observations do not prove their exact login mechanism or that a permission list alone is sufficient.
The next compatibility work must verify real dependency installation, service/provider binding,
account-authenticator discovery and asynchronous callback delivery, preserving PCM capture.
The APK, extracted binaries and analysis output stay outside tracked source and are not bundled;
no proprietary implementation, fabricated account/token or attestation result is imported.

### Embedded APK modules and real service-package import

The first import of this API 36 emulator's actual Google Play services APK failed with
`APK set contains multiple base APKs`. The container's archive classifier counted APK modules
inside an otherwise normal APK as an outer install bundle. The classifier now gives the root
`AndroidManifest.xml` precedence over both embedded `.apk` entries and the filename extension.
Outer bundles still use the existing split/identity/signature validation; this is not a parser
or signature bypass. Four JVM cases cover real-APK structure, misleading names, a one-APK
outer archive and a non-APK archive.

After the fix, copying only the emulator's Google Play services APK into the container passed
the import-only check in **47.372 seconds**. Its Google Services Framework APK had separately
passed import in **10.831 seconds**. No account data, system privileges or permissions were
copied. This does not establish that those services start or can authenticate a user; the
emulator's `com.android.vending` is a license-checker stub, not a complete Play Store.

The same installed debug host passed all **15 ordinary-UID API 36 regression tests in 66.006
seconds**, including genuine split/APKS installs and PCM capture. Debug build/lint, **122 JVM
tests** and **14 prepared-source checks** passed. The earlier USB AudioControl ownership fix
also passed GitHub run `35002284364` (host and API 34/36) at `259e090`; that run does not include
this later archive-classification change. Both changes are included in the published alpha 3 APK;
its exact release commit passed GitHub run `35007733625` and 15 signed-release runtime tests.

The subsequent YouTube Music 8.09.50 screen check with real GMS/GSF installed **failed** after
97.164 seconds: no stable visible `Sign in` screen. Its logs explicitly report that the Google
Play Store is missing and service availability error 9. The container did initialize GMS
processes, but neither initialization nor installing these two dependencies established login.
For the next check, the actual `Phonesky.apk` was obtained from the official API 36 Google Play
emulator image, not substituted with its Google-APIs image's license-checker stub. The APK is
kept in ignored local build output only; no Google APK is bundled or redistributed by VirtualDAP.

Importing the actual Google Play Store 45.3.21-31 APK passed in **18.949 seconds**. A further
YouTube Music screen test nevertheless **failed in 97.057 seconds**. The new logs report invalid
Google Play services signing identity (not a missing Store), followed by
`MeasurementServiceConnection.onServiceConnected` failing a main-thread assertion. These are
unresolved interoperability failures; no signing check, account result or callback-thread check
was suppressed to make the test pass. The next investigation must compare actual installed-APK
signing metadata, including rotation history, and preserve the caller's service-dispatch semantics.

### Imported-package signing metadata

Package information previously borrowed certificates from a same-named host package, or
constructed incomplete signing details when the host package was absent. It now obtains
requested legacy signatures and current/rotation metadata from Android's parser for the
actual imported base APK. A missing or mismatched archive fails closed; install/update
signature verification is unchanged. This matters in particular when the host has a Store
stub instead of the imported Store, or a different version of a rotated signing identity.
No certificates, verification results or account identities are fabricated.

The API 36 ordinary-UID regression suite passed **17 tests in 140.291 seconds**. A separate
real Google Play services import compared its legacy/current/history metadata against the
platform parser and passed in **21.289 seconds**. Debug build/lint, **122 JVM tests** and
**16 prepared-source checks** passed. Nevertheless, the subsequent YouTube Music screen
check **failed in 94.927 seconds**, still reporting invalid GMS signing identity and the
service-callback main-thread assertion. Correct package metadata alone did not resolve
that application's startup. Neither this fix nor the authenticator discovery below is
included in the published alpha 3 APK.

### Login environment and bounded authenticator failures (after alpha 3)

Comparison with the reference's dependency classifications led to a separate Korean
`도구 → 로그인 환경` panel. It imports user-selected real host base/split APKs for GMS, GSF,
the Store and an optional legacy account manager using the existing verified installer.
These packages no longer appear as playable music cards. No Google APK, host account data
or provider login implementation is bundled. The reference's native account implementation
is not reproduced or claimed to have been completely recovered.

Authenticator discovery and session binding now query the same container user's installed
services. The previous global cache could be cleared by installing an unrelated package,
and queried USER_ALL instead of the session user. New account records now retain their actual
user ID; each authenticator XML parser is closed after use.

A real `AbstractAccountAuthenticator` fixture exposed a separate Android 16 restriction:
the Binder request reached the authenticator but Android rejected `ACCOUNT_MANAGER`.
An experimental local bridge also failed caller validation and was discarded, not relaxed.
There is no permission-checker replacement in the product; the earlier blanket account
permission response is removed. A rootless imported APK does not inherit its former system
privileges, and this remains a blocker for affected authenticators, not successful login.

The old session removed timeout messages without ever scheduling one. Requests that never
respond now return a remote/authenticator error after 30 seconds; a real login-activity
continuation gets a separate ten-minute deadline. Binding death/null binding are reported,
pending bindings are released, and a late callback cannot run an already-closed session.
The fixture checks an actual timeout error and elapsed time, not an invented success result.

During verification, removed override files were found to survive in the generated source
overlay. Preparation now rebuilds only the validated disposable `build/generated/upstream`
tree, rejecting symlinks and source/output overlap. Source and APK-boundary checks assert that
the discarded bridge is absent. Tracked upstream inputs and user files are not removed.

The final ordinary-UID API 36 suite passed **17 tests in 96.206 seconds**, including the
bounded authenticator error, PCM capture and the Korean login-environment dialog. Debug
build/lint passed in **4m 51s**, with **125 JVM tests**, **21 generated-source/preparation
checks** and the four-ABI APK product-boundary check. The first timeout test incorrectly
expected `IOException`; Android actually returned `AuthenticatorException: timeout`. That
failed run is not counted as a pass. The corrected fixture requires that exact timeout and
25–60 seconds elapsed, and still fails if the privileged authenticator succeeds. These are
error-handling and regression results, **not Google/Apple login certification**.

### Authenticator discovery before the first account

Code inspection found `getAuthenticatorTypes` enumerating saved accounts instead of installed
authenticator services. A fresh profile therefore returned no types even when the corresponding
service and metadata existed. The adapter now queries this container user's actual declared
services and parses their authenticator XML; it does not require an account to exist or borrow
another user's/host's identities. A metadata-only fixture declares one type without creating
accounts, accepting credentials or issuing tokens, and checks discovery through the app-facing
`AccountManager`. This is a discovery regression, not a successful authentication session.
This follow-up change is **not included in the published alpha 3 APK**.

The unchanged fixture/test APK against the pre-fix alpha-3 debug host failed in **33.010 seconds**
with `Declared authenticator must be discoverable without an account: 0`. Replacing only the host
with the fix then passed all **15 runtime tests in 64.026 seconds**, including PCM capture,
split installs, controls and Korean UI. Debug build/lint, 122 JVM tests, 15 prepared-source checks
and the four-ABI product boundary also passed. An earlier local run ended without a result when
the test emulator exited; host logs later recorded memory pressure. After rebooting the emulator
with 2 GiB RAM and dismissing its boot-time System UI dialog, the explicit discovery failure
above and subsequent full pass were recorded. Incomplete/obscured runs are not counted as passes.

| Package/build | Environment | Installation | Application start | Captured playback |
| --- | --- | --- | --- | --- |
| VirtualDAP fixture, source-built debug | Historical baseline: API 33 AOSP x86_64; API 36 Google APIs x86_64 | Verified base, signed feature split update, device-targeted binary-`toc.pb` APKS, and official bundletool 1.18.3 APKS probe | Verified, including feature-only class | Java streaming/static PCM, AAudio callback/write, OpenSL ES buffer queue, prebuffer/pause/resume/volume, two overlapping streams and individual release |
| VirtualDAP fixture, API 34 minimum / official-output build | API 36 Google APIs x86_64, ordinary UID; September 14, 2026 | Verified base, split update and device-targeted APKS | Verified, including feature-only class | Paced test receiver: Java streaming/static, AAudio callback/write, OpenSL ES, controls, overlapping capture and individual release; **not DAC output** |
| YouTube Music 8.09.50, version code 80950280, x86_64 | API 36 Google APIs x86_64, ordinary UID; September 14, 2026 | Verified copying the installed host base/split package into the container | Application.onCreate callback verified; subsequent visible sign-in-screen check **fails** | Not tested; login, DRM, UI navigation and music playback remain unverified |
| foobar2000 mobile 2.25.9, version code 1093, official x86_64 APK | API 36 Google APIs x86_64, ordinary UID | Verified APK import | Application.onCreate, welcome screen and completed ordinary onboarding | Generated WAV: captured 48 kHz / stereo / PCM16 from the app PID; left 440 Hz and right 660 Hz signals verified at the paced test receiver; **not DAC output** |
| Apple Music 6.5.2, version code 1586, publisher-listed Android APK | API 36 Google APIs x86_64, ordinary UID; September 15, 2026 | Verified APK import | Stable welcome screen passed after fixing media-service discovery; earlier startup crash recorded below | Not tested; login and subscription playback unverified |
| Spotify and remaining catalog services | — | Not yet verified | Not yet verified | Not yet verified |

The current minimum is API 34. USB audio is now the default product mode, with official bit-perfect
as an explicitly selected advanced mode. Earlier results below describe the official-only builds
on which they were obtained, not later USB integration. Historical mixed-output results do not
establish either current output contract. Current capture
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

Follow-up CI run `34833833601` passed the host build but failed the API 34/36 runtime jobs
(a foreground-service lifecycle error and a PCM-capture timeout). The test wait helper was found
to execute successful button actions twice. It now returns on the first success, and container-test
cleanup no longer creates an unused STOP-only production service. The local suite passed 12 tests
in 50.354 seconds after these test changes; the CI matrix must be checked independently.
Run `34837159352` subsequently passed the host debug/release build and both API 34/36 runtime jobs
for commit `3e1c045`.

## Subsequent CI reliability check

GitHub run `34945887250` passed the host build and API 34/36 runtime jobs for `7c87ba3`,
including the USB source-preserving alternate selection and corrected fixture-readiness checks.
This result precedes the media-service query and Korean notification changes below.

After release, documentation-only commit `bd21a32` passed the host and API 36 jobs in run
`34933502548`, but API 34 failed while waiting for 96 kHz float capture (15 tests, one failure).
The log recorded the preceding 48 kHz track, but no subsequent 96 kHz track creation. This is
not a passing run and does not change the recorded result of the earlier release-commit run.

Inspection found a race in the fixture: capture socket closure was observable before its producer
thread finished releasing resources, while the next Play handler silently ignored requests until
`Thread.isAlive()` became false. The fixture now disables its start buttons during playback and
publishes readiness on the UI thread only after the producer's cleanup. Instrumentation waits for
an enabled button before clicking and checks the busy state during playback. This addresses a
concrete test-app race consistent with the failure; it does not establish a production USB fix or
commercial-app compatibility. Subsequent runtime results must be checked independently.

The first local rerun was obscured by an emulator System UI startup ANR and failed two screen
checks. After dismissing that system dialog, the new busy-state assertion exposed a test-label
comparison error: Android's button text transformation was not handled consistently with the
existing click helper. The busy assertion now uses the same case-insensitive exact-label check;
it still requires the button to be disabled. The subsequent full local API 36 run passed all
15 tests in **79.987 seconds**, including both PCM formats, producer readiness, native capture,
split update and Korean home. Debug/test APK build and lint passed; the USB change passed
116 JVM tests, including UAC1/UAC2 alternate-selection regressions. None is a physical USB test.

## Real account and package results

The consumer-source preparation excludes the synthetic authentication/account adapters: fabricated
tokens, accounts and login-success fallbacks are not a service integration. Package-info lookup
returns an actual installed container record, a permitted real host query, or absence; it no longer
invents a Play Store package/version/signing identity or sets permission-granted bits in that lookup.
The normal container account-manager implementation remains. Prepared-source and APK checks cover
the excluded adapters. This does not establish working Google services, login or subscription playback.

## Container control lifetime

A subsequent local split-update test failed when Android froze the cached container control process:
the next Binder call reported `Transaction failed because process frozen`, then `DeadObjectException`.
The host now holds an ordinary, non-exported service binding into the control process. This declares
the process dependency to Android without a foreground daemon, root, a wake lock or a freezer override.
The fixture verifies that the bound control service remains below cached importance after the hosted
app stops, then performs the real split update. The combined account-adapter and lifetime changes
passed 12 local API 36 tests in 52.806 seconds, debug build/lint, 98 JVM tests, prepared-source checks
and the APK boundary check. This is fixture/control evidence, not commercial-service certification.
GitHub run `34865313353` passed the host debug/release build and both API 34/36 runtime jobs for
commit `04aa48c`. This precedes the activity-lifecycle evidence changes below.

## Visible activity evidence

The official foobar2000 APK used for the local check was downloaded from the publisher's
`foobar2000.org/downloads/foobar2000-mobile-v2.25.9-x86_64.apk` distribution, without modification.
Its verified signing-certificate SHA-256 is
`7cc027cf34f77977aaa7543a3390d293212fd75ab306de6898e61528f99a82b5`.
The welcome-screen smoke passed (1 test, 26 seconds, rounded). No login controls or personal media
were used. This establishes an independently distributed app's visible startup, not audio capture.

On this emulator, accessibility reports the container's real host package on the app's window.
A package-label check alone therefore rejected a visibly rendered screen. The optional UI smoke
now combines the expected app's resumed-activity callback, a live process, and an exact visible
non-password label. Lifecycle reports are accepted only from the same UID with the actual calling
PID; each activity instance has its own identity, so a late pause cannot clear its successor.
Explicit app stop clears that app's activity evidence. Neither these callbacks nor a host-package
accessibility label on its own constitutes a successful app-screen test.
After the stop-state reduction and regression tests were added, the local debug build/lint,
103 JVM tests and all 12 API 36 instrumentation tests passed (48.739 seconds for instrumentation).

## Optional local compatibility smoke

### Apple Music media-service discovery

The Apple support article `support.apple.com/en-us/109340` links to the Android distribution at
`sj.qq.com/appdetail/com.apple.android.music`. On September 15, 2026 its mobile download response
provided Apple Music 6.5.2, version code 1586, minimum API 30, target API 35, with all four ABIs.
The 205,338,559-byte APK matched that listing's MD5 `339074e43d51af1d2959e81ba2c985fc`;
APK signature verification passed with certificate SHA-256
`88ba590ec2e1ea33c4458daf59489faee2cef297a9b4071e18cf82ef531100aa`.
APK SHA-256: `a05a36a5678015fd49d8c73aed2087e7a2f8f3232376733a2cf2f82623895736`.
The unmodified APK is local test input only, not bundled or redistributed.

Import/initialization passed in 52.765 seconds (the class's two tests). The subsequent visible
`Continue` screen check failed in 40.774 seconds: the welcome screen appeared, then the process
threw `Failed to resolve SessionToken` for its declared `MediaPlaybackService`. No account was
entered and no subscription or music playback was attempted.

Inspection found that the pinned container exposed a service-query implementation internally
but did not route Android's `queryIntentServices` call into it. The adapter now returns real
parsed container records for the target package; queries outside the container keep Android's
normal host visibility/permissions. It does not invent service entries or alter app signatures.
A fixture declares a real browser service and checks both its discovery and an empty result
for an undeclared action. After installing the service-query fix, the same unmodified Apple Music
APK passed the visible `Continue`-screen check in **99.9 seconds**. That check requires a real
resumed activity, live process, visible non-password label and three seconds of screen stability.
This proves only the welcome-screen stage, not acceptance of onboarding, sign-in, media playback,
or PCM/DAC output. The full fixture/runtime suite remains a separate check.

The combined changes also localize the audio notification channel, controls and playback states
in Korean without changing transport selection. Debug/test APK assembly, lint, the 116 JVM tests,
14 prepared-source checks and the packaged-output boundary passed locally. The first full local
API 36 run failed two screen checks while a system-process ANR dialog covered the application;
the notification/error-state tests passed. A second attempt was interrupted after the system
dialog recurred, and the emulator was rebooted without clearing app data. Neither attempt is a
passing full-suite result; a clean runtime rerun and the commit's CI matrix are still required.

After reboot, a Bluetooth controller-start crash and launcher/System UI ANR dialogs also obscured
the local screen checks. Bluetooth was switched off in this test emulator (not in product code),
and the system dialogs were dismissed before testing. The unchanged APK then passed all **15 tests
in 74.201 seconds**, including the new declared/absent service queries, Korean notification channel,
PCM formats, native capture, lifecycle and Korean home. Native host tests also passed: two PCM
transport tests and three DSP/USB tests. This is ordinary-UID software evidence, not Bluetooth,
physical DAC or commercial subscription-playback certification.

### Current Android intent delivery and independent-player PCM

The default-USB/Korean-UI build passed GitHub run `34896732514` at `9e5e6ed` (host,
API 34 and API 36), and the UI recovery/USB receiver fixes passed run `34898228784`
at `1d0bc87`. These runs precede the new-intent changes described here.

The first independent-player WAV check exposed missing normal Android permissions:
`REORDER_TASKS` for returning to an existing container task and `BROADCAST_STICKY` for
legacy playback-state broadcasts. Both are now declared normally; the emulator's package
manager reported them granted at installation, without a root/permission override.

A further failure showed that current Android's `ActivityThread.handleNewIntent` takes an
`ActivityClientRecord`, while the pinned engine tried only old token-based signatures and
silently omitted the callback. The narrow adapter now looks up this process's actual record
by token and invokes the current signature. It does not manufacture an Activity or suppress
an application exception. The regular fixture now checks a unique new-intent marker and
that the original activity instance is preserved.

After these fixes and ordinary first-run onboarding (default theme, skip optional library
setup), the unmodified player opened a locally generated 10-second WAV via its public VIEW
activity. The optional smoke passed in **36.420 seconds**. It checks the sending PID, 48 kHz,
stereo PCM16, and more than 95% test-tone energy in each channel over a one-second window
(left 440 Hz, right 660 Hz). This is stronger than a startup callback or counting nonzero
bytes. It does not establish bit-exact output, physical USB/DAC playback, streaming-service
login or subscription/DRM compatibility. No personal media or accounts are used.
The updated ordinary-UID API 36 suite passed all 15 tests in 67.842 seconds, including
same-instance new-intent delivery. Debug build/lint, 114 JVM tests, 13 prepared-source checks
and the four-ABI USB/retired-code APK boundary also passed locally.

Add `-e externalWav true` to the local compatibility command to perform this check after
completing the player's ordinary first-run setup. This opt-in test creates and removes its
own test WAV in host cache, stops the selected player afterward, and requires that the
player support the public WAV VIEW action. No external APK is bundled in the test suite.

The instrumentation APK accepts `externalApk` (a filename in the debug host's private cache) and
`externalPackage`. This mode checks import and Application.onCreate only; it deliberately does not
claim playback verification.
It uses the test-only receiver so import/start checks do not require a physical official-output DAC.
Optional `externalUiText` additionally requires an exact, visible accessibility label, a resumed
activity from the imported app and its live process for three seconds after initialization.
The node package may be the app or the real host, because container windows can report host IDs.
This distinguishes
an actual app screen from the host launcher or a startup callback preceding a crash. It does not
click login controls, submit credentials or establish playback compatibility. Choose the label for
the app version and emulator locale being tested.

For an explicitly supervised local inspection, `externalInspectionSeconds` (0–180, default 0)
keeps the test-only paced receiver alive after the selected startup/screen check. This allows
manual onboarding navigation before the instrumentation process exits. The wait itself is **not**
a login, navigation or playback assertion; record the actual observed screens/errors separately.
It neither submits credentials nor accepts agreements automatically. Normal CI does not wait.

```sh
adb push /path/to/legitimately-obtained.apks /data/local/tmp/virtualdap-app.apks
adb shell run-as com.virtualdap.host cp /data/local/tmp/virtualdap-app.apks cache/compat-app.apks
adb shell am instrument -w -r -e class com.virtualdap.host.ContainerInstrumentedTest \
  -e externalApk compat-app.apks -e externalPackage com.example.music \
  com.virtualdap.host.test/androidx.test.runner.AndroidJUnitRunner
```

For regular users, the Music space supports file import and copying installed catalog music apps.
Only APK code is copied; the original app's private data/accounts are not accessed.
