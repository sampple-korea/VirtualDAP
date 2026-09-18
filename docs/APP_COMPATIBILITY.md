# App compatibility evidence

Catalog membership is a target, not a compatibility certification. No subscription login, account
state, DRM verdict or provider policy is bypassed. Tests use an ordinary application UID.

**Latest scope update, September 17, 2026, after alpha 5:** the user requires reaching Apple
Music's credential-entry screen and Google account addition from YouTube Music. No credentials
need to be submitted; successful authentication/subscription playback are still not required.
The previous quality-only completion does not satisfy this new acceptance criterion. A welcome
screen, account picker, app process surviving, or a green fixture suite is not login-screen proof.

The original reference APK's temporary upload path is no longer present. Its retained local
interoperability-analysis files remain available; they are not part of the tracked project.
New findings and actual screen outcomes will be recorded separately from earlier alpha evidence.

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

The new user-facing host-dependency import path then copied real Google Play services
successfully in **49.160 seconds**, including comparison with the source APK's signing
metadata. This validates importing code, not service authorization or authentication.

A subsequent supervised Apple Music 6.5.2 inspection reached its main tab screen, but
crashed before credential entry. Android rejected `ISessionController.sendCustomAction`
because the request declared `com.apple.android.music` while its actual calling UID belonged
to `com.virtualdap.host`. The existing session-creation adapter correctly uses the real host
package; controller commands require separate investigation. Changing authentication or
claiming a provider identity would not fix this caller-attribution mismatch.
The old inspection harness reported its initialization-only test as passed in 243.356 seconds
despite the observed crash; this is **not** a UI/login success. The inspection path now also
requires the original app process to remain alive at its end. Provider login, credentials,
subscription playback and physical USB were not tested in this inspection.
The strengthened check reproduced the process-exit failure in **90.803 seconds**. The setup
commit `9af2a61` independently passed GitHub run `35054756617` (host build and ordinary-UID
API 34/36 suites); that green fixture result does not override this Apple Music failure.

### Media-controller caller attribution (after alpha 3)

The controller adapter now forwards only the caller-package fields of known platform media
commands as the real host package matching this process's actual UID. It wraps controllers
returned by session creation and tokens received through Android's parcel creator. Original
OS binders, token equality/hash, command payloads, callback results and thrown exceptions are
preserved. This does not grant media/account permissions or impersonate the music provider.
The caller-field list was checked against AOSP's Android 16 `ISessionController.aidl`.

A fixture sends real custom commands through both a local controller and a parcel-restored
token, using its guest package as the command payload to catch overly broad string replacement.
The same test APK failed against the preceding host in **58.312 seconds**, reproducing the
package/UID exception. Against the fixed host, the complete ordinary-UID API 36 suite passed
**17 tests in 115.010 seconds**, including PCM capture and both controller paths. Debug build,
lint and 125 JVM tests passed (5m 4s); 22 prepared-source checks and APK boundary checks passed.

The unmodified Apple Music 6.5.2 subsequently passed a 60-second supervised process-liveness
inspection (**116.001 seconds** total). Its Library screen rendered and tab navigation worked;
the New tab still showed a loading indicator. No credentials were entered and login, complete
catalog loading, subscription playback and physical DAC output remain unverified. This result
addresses the observed controller crash, not all of the user's reported loading problems.
These changes are not present in the public alpha 3 APK.

The media-controller commit `110f38c` passed GitHub run `35056554891`, including the host
build and ordinary-UID API 34/36 suites. A longer local inspection stayed alive for 180 seconds
(234.714 seconds total), but Settings → Sign In failed visibly: the embedded login sheet timed
out in `LoadingHTML` and displayed an error. The same unmodified Apple Music APK installed
normally on the same emulator subsequently reached **Continue with Email**, with its own
`Bootstrap: Succeeded` log. No email or credentials were entered in either environment. The
container's process-liveness pass must not be described as login-screen success. The native
baseline narrows this failure to the container environment; it does not identify the cause alone.

### Real connectivity state instead of invented fallback networks

The pinned engine's connectivity adapter manufactured network IDs/capabilities/DNS when the
platform returned no network, forced connected/validated states, and swallowed registration
errors. The replacement delegates Android results and errors unchanged, correcting only known
caller-package fields to the actual host UID's package. It does not change DNS servers, private
DNS, metering, VPN/network state, request UIDs or callback executors. Caller positions were
checked against AOSP's Android 14 and Android 16 `IConnectivityManager.aidl`.

The fixture now queries an absent network and requires null capabilities/link properties. It
registers a real default-network callback, requiring delivery when an active network exists;
offline environments are reported explicitly. It has `ACCESS_NETWORK_STATE`, but still no
Internet or storage permission and sends no remote requests. The first baseline attempt failed
during a lost runtime-service connection, before testing connectivity. After restarting only the
test host and dismissing a visible emulator-launcher ANR, the unchanged old host failed the new
absent-network assertion in **66.896 seconds**. The new host passed all **17 ordinary-UID API 36
tests in 101.659 seconds**, with actual default-network callback delivery observed. Debug build,
lint, 125 JVM tests, 23 prepared-source checks and the APK product-boundary check passed.
These software results do not establish that Apple/Google login or physical USB output works.
The subsequent Apple Music inspection stayed alive (238.199 seconds), but its login sheet still
timed out in `LoadingHTML`. WebView also logged invalid Google services identity and a supervised-
user query timeout. The connectivity correction therefore resolves the independently reproduced
false-network defect, not this login failure. Neither certificate checks nor WebView safety
checks were disabled to change that result.

### Google Play image comparison (September 16, 2026)

Connectivity commit `a4c4f67` passed GitHub run `35078283694` (host and API 34/36 runtime).
The earlier Google-APIs image's GMS APK has legacy test-certificate SHA-256
`1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00`; importing it does not
confer its preinstalled/system privileges. Rather than fabricating flags or accepting its
certificate in app checks, a separate official Google Play emulator was used for comparison.
ADB was recovered by dismissing a boot-time System UI ANR and restarting the host ADB server;
the shell remains UID 2000, not root. Neither emulator's data was cleared.

Actual installed GMS **26.33.32 (263332038)**, including all installed splits, imported in
**71.089 seconds**; GSF imported in **17.241 seconds**, and Store **53.0.27-34 (85302740)**
in **30.115 seconds**. The GMS base APK is 193,905,431 bytes, SHA-256
`4837692410edcd67cd963d340e37083f210e97040f1a8c1e85732d706e1eaf27`, with current signer
`5f2391277b1dbd489000467e4c2fa6af802430080457dce2f618992e9dfb5402` (Google Inc.). All are
unmodified local inputs, not bundled/distributed. Import checks compare actual platform-parsed
signing metadata, not authentication success.

YouTube Music 8.09.50 still failed its visible **Sign in** check in **97.276 seconds**.
Its dependencies exposed two concrete failures: GMS broadcast to Android user `-1` was denied
cross-user privileges, and Store initialization failed in a provider query because its package
did not belong to the actual host UID. The same normally installed YouTube Music reached its
Sign in/Device files only welcome screen on this emulator without container hooks. No credentials
were submitted. An initial shell command with incorrectly quoted `Sign in` did not start the
instrumentation and is not a test result. Provider and broadcast routing require further fixes;
the successful APK imports do not override these failed runtime checks.

### Provider caller attribution (after alpha 3)

The generic provider adapter now copies only the leading `AttributionSource`, using the actual
host package/UID for IPC. Query strings,
authorities, Bundles and other payloads are untouched. The real provider's results and exceptions
are returned, with no synthesized Samsung/adult status or default success values.

The same new instrumentation test against the preceding host failed in **0.902 seconds** because
the adapter mutated the caller's original attribution object. Against the new host it passed,
checking copy isolation, chain/token retention, payloads, Binder identity and the exact remote
`SecurityException`. The final build, unit tests and lint passed in **4m 24s**; all **125 JVM tests**,
**24 prepared-source checks** and the packaged product-boundary check passed.

The first full runtime run passed the provider test but timed out initializing the container
(18 tests, one failure, **123.195 seconds**). No crash was retained in the crash buffer. After
force-stopping only VirtualDAP, without clearing app/emulator data, the unchanged APKs passed
all **18 runtime tests in 101.410 seconds** on the ordinary-UID API 36 Google Play emulator.
This rerun does not establish the cause of the initial connection timeout or successful Google
login; the real YouTube Music/dependency checks remain separate.

GitHub run `35106014207` subsequently passed the host and API 36 jobs but failed API 34:
the copied attribution tag was null. AOSP Android 14's `Builder(copy).build()` resets fields
whose setter bits are absent, including the copied PID, tag and renounced permissions, and
replaces the token. The adapter now uses the platform's `withPackageName` copy operation instead;
that operation retains these fields and the downstream chain (and device ID on newer releases).
The regression now uses a non-default token, PID and renounced permission so default values cannot
mask this loss. No token is invented by the production adapter.
The combined attribution-copy/broadcast follow-up built with unit tests/lint in **4m 47s**,
passed all **20 runtime tests in 110.877 seconds** on API 36, and passed the 25 prepared-source
checks and packaged product boundary. API 34 CI must still confirm the platform-specific fix.

The unchanged YouTube Music screen check after this correction failed in **51.440 seconds**:
the requested screen appeared but did not remain visible for the required three seconds.
The retained crash buffer showed GMS failing on the separate `USER_ALL` broadcast. No successful
login or disappearance of every provider error is inferred from this one observation.

### Private broadcast transport (after alpha 3)

GMS's `USER_ALL` broadcast was already converted to a host-package-only shadow, but its final
Android user argument remained `-1`. Android correctly rejected the ordinary host UID's cross-user
request. The adapter now maps only this private transport to the host's actual Android user and
records the current virtual user in its payload. `ALL`, `CURRENT` and `CURRENT_OR_SELF` mean only
the active music space for this operation; a different explicit virtual user is rejected. A null
shadow keeps the original OS arguments/checks. Unscoped or externally targeted shadows are rejected.

The AOSP API 34/36 broadcast AIDL layouts are checked explicitly. Payloads, result callbacks,
required/excluded permissions, excluded packages, app-op and options remain unchanged. Receiver
registration no longer clears the app's requested sender permission. No Android cross-user
permission is granted and no ordinary OS broadcast is silently promoted to a privileged one.

A real guest fixture registers a receiver and sends a unique package-directed `USER_ALL` broadcast.
The preceding host fails this unchanged test in **55.763 seconds**, retaining the same Android
cross-user denial as GMS. Replacing only the host passes all **20 runtime tests in 106.105 seconds**,
including delivery of the original nonce/destination, argument/restriction preservation, invalid
destination rejection and the existing PCM checks. Build/unit/lint passed in **5m 2s**, with **125 JVM
tests**, **25 prepared-source checks**, and the packaged product-boundary check passing. This local
run preceded the separate API 34 attribution-copy follow-up and is not authentication evidence.

The actual YouTube Music check with the broadcast correction still failed (**120.825 seconds**,
Sign in label timeout). This run's retained crash buffer had no fatal exception, but logcat recorded
two proxy-process ANRs and host GMS's `AccountChimeraContentProvider` rejecting VirtualDAP's actual
signing identity (`GoogleCertificatesRslt: not allowed`). The lookup code still explicitly routes
Google authorities to host providers, despite importing the real dependency APKs. That routing
must be reviewed against the container's provider records; changing certificates or suppressing
the provider's denial is not a valid substitute. The emulator was also compiling under memory/CPU
pressure, so the ANRs alone do not isolate a production deadlock.

An independent Apple Music host-app import in the same environment timed out before application
launch (**86.734 seconds** total). The captured worker stack was inside `Deflater` called from
`ApkArchiveNormalizer.normalizeIfArchive`: installed APK code is compressed into an outer archive
and recompressed during normalization. Removing this redundant compression is a concrete import
performance task, not evidence that Apple login now succeeds.

### Installed APK staging performance

Single installed APKs are now copied directly into private staging instead of wrapping and
recompressing an outer archive. Split sets and normalized archives use no-compression deflate
transport, retaining their APK bytes, aggregate size limits and subsequent signature/manifest
checks. Temporary transport size may be larger; no APK is modified or re-signed. Five additional
JVM regressions check byte preservation, split ordering, absence of recompression, aggregate
limits, partial-file cleanup and existing-destination protection.

Build, **130 JVM tests** and lint passed in **3m 7s**, together with 25 prepared-source checks and
the APK boundary check. The same Apple Music 6.5.2 host-import command now completed installation
and application startup; its 120-second process-inspection test passed in **151.544 seconds** total.
This includes the inspection wait and is not an import-duration benchmark. The actual welcome
screen and New tab's remote catalog were seen, but WebView initialization triggered an input ANR;
no successful login is claimed. The retained main-thread trace was inside Trichrome/WebView
browser startup. Only ordinary Continue and declining diagnostics were used; no credentials,
subscription agreement or account data was submitted.
The unchanged full runtime suite then passed **20 tests in 100.047 seconds** on the same API 36
emulator, with no build running alongside it.

GitHub run `35109319897` passed host/API 36 but API 34 exposed a test-only portability issue:
`AttributionSource.withToken` is absent there. The fixture now sets its own non-default token
through its attribution-state field; production token handling is unchanged. The updated test
passed locally on API 36, and its build/lint passed in **1m 7s**. API 34 remains a separate CI gate.

### Container provider routing and Binder identity

The previous provider resolver forwarded any authority containing a Google-services/Store string
to Android's host provider resolver, even when the authority belonged to an imported application.
Those substring branches are removed. Imported providers now follow the existing exact authority
lookup for the active container user. Settings/media/telephony and explicitly open system packages
retain their separate handling. Host Google account providers are not an implicit fallback when a
dependency is absent; provider results, certificate decisions and permission denials are not faked.

A fixture declares three distinct private authorities containing the affected substrings, all in
its own separate provider process. It checks the actual process/package, unchanged payload and a
deliberate `SecurityException`. The preceding host logged `Failed to find provider info` and displayed
`Unknown authority`; its automated comparison was obscured by emulator launcher/System UI ANRs
and timed out at the earlier new-intent assertion (82.309 / 76.212 seconds), so these are not clean
assertion-level routing baselines. An earlier attempt was interrupted by emulator process exit,
and one boot-time attempt ended before the host finished startup. Emulator data was retained.

Removing only the routing branches exposed another real defect: the fixture provider process
received the IPC but rejected `Calling uid: 10005 doesn't match source uid: 10217`. That full suite
ran **20 tests with one failure in 58.481 seconds**. The inherited Binder callback replaced the real
kernel UID with a virtual package UID (and even had a stack-based system-UID fallback). It now
returns the original UID unchanged; virtual package/user IDs remain explicit container records.
The provider fixture also compares the real caller and process UIDs. No attribution validation or
permission check is disabled to make the call succeed.

The combined build/unit/lint passed in **5m 23s**, but its initial runtime check still failed
(20 tests, one failure, **127.526 seconds**). A diagnostic rerun (**29.084 seconds**) showed that
provider routing, payload and distinct process were correct: Binder reported the real UID 10217,
while the fixture compared it to Java `Process.myUid()` reporting the virtual package UID 10005.
The fixture now reads `getuid()` through its own tiny JNI helper for the kernel-identity assertion;
it does not loosen the assertion or treat a virtual package identifier as an OS permission grant.

With that kernel-UID fixture, the ordinary-UID API 36 suite passed all **20 tests in 99.661 seconds**.
The final test APK/lint build passed in **2m 1s**; the production build had passed 130 JVM tests,
and all 26 prepared-source checks passed. The preceding attribution portability commit `68dab92`
also passed GitHub run `35115178215` on host, API 34 and API 36; that CI result does not include
the new provider-routing changes.

The unmodified YouTube Music 8.09.50 APK, using the imported Google services in the ordinary
Google Play API 36 emulator, then passed its exact visible `Sign in` screen check and 30-second
process-inspection window in **130.891 seconds**. This is startup-screen evidence, not a completed
Google account login, DRM/subscription playback, PCM capture or physical USB-output result.

A second supervised run passed the same screen/process checks in **224.881 seconds**, including
its 180-second inspection window. Manual navigation displayed YouTube Music's actual `Accounts` /
`Add account` dialog. Selecting `Add account` did not reach a credential form during that window.
The process-survival assertion applies to the music app only: captured logs showed its imported
Google services process crashing on `WifiManager.getConnectionInfo` during a network change,
because the guest package name did not belong to the real host UID. This run therefore does not
certify Google-services stability or account addition.

### Wi-Fi connection-state caller attribution

The inherited Wi-Fi adapter passed the guest caller package to the system and replaced the returned
SSID/MAC/BSSID with constants. The replacement maps only `getConnectionInfo`'s known caller field
when it equals the current guest package, using the real host package. Android 14 and 16's
`IWifiManager.aidl` both declare `(String callingPackage, String callingFeatureId)` for this method.
The feature tag, arguments of unrelated/unknown layouts, actual returned object (including null
or redacted identifiers), and permission exceptions remain unchanged. No location permission,
system UID, manufactured network identity or successful permission result is introduced.

Regression tests cover the exact caller field, original-argument immutability, null/foreign callers,
unknown methods/layouts, same-object results and real remote denial propagation. The music fixture
also invokes the real OS Wi-Fi query without recording SSIDs/MACs or asking for location access.
Build/unit/lint passed in **5m 6s**, with 130 JVM tests, 27 prepared-source checks, the packaged
four-ABI output boundary, two native PCM transport tests and three native DSP/USB tests passing.
The first old-host comparison timed out before fixture initialization (**100.585 seconds**), so
it is not a Wi-Fi regression result. Repeating with the same old host and new fixture then failed
at `WIFI STATE ERROR: java.lang.reflect.UndeclaredThrowableException` in **26.865 seconds**.
The corrected host then passed all **22 ordinary-UID API 36 tests in 148.702 seconds**, including
the real Wi-Fi query and the new argument/result/exception tests. This does not assert account
login success or cover every Wi-Fi service method. The provider-routing commit `5400129` separately
passed host/API 34/API 36 CI in run `35138737543` before this Wi-Fi change.

With the Wi-Fi fix installed, another supervised YouTube Music run reached the real `Accounts` /
`Add account` dialog. At 2026-09-17 05:19 KST, selecting account addition reached the imported
Google authenticator, whose framework `IAccountAuthenticator.Stub.addAccount_enforcePermission`
rejected the request with `Access denied, requires: android.permission.ACCOUNT_MANAGER`.
Android 16's framework manifest declares that permission `signature`, not an install-time or
user-grantable runtime permission. The inspected Google services process remained alive; the
earlier Wi-Fi crash was not reproduced in this captured interval. No credential form or account
login succeeded. This remains a real compatibility blocker, not a request to copy phone account
data, change the platform signature, fake an authenticator result or claim system privileges.
The music-app screen/process check completed in **226.523 seconds**; it does not validate the
failed account-addition flow. Wi-Fi commit `be4b2f9` passed host/API 34/API 36 CI in run `35145629580`.

### Providers declared for the main process

Subsequent Apple Music inspection displayed its real catalog, Home, menu and Settings screens.
The 180-second supervised startup/process checks passed in **208.795** and **202.574 seconds**.
These checks do not assert login. Navigation to Sign In was not completed before the second
inspection window ended, so no login result is inferred from the later screen.

The same environment logged repeated `ClassCastException`s in imported Store `:background` and
`:quick_launch` processes, including main-process content providers. Inspection found that both
container provider-installation paths initialized a provider when its declared process matched
the package's main process, even if the current process was different. That extra condition is
removed: only an exact current-process match or explicitly declared `multiprocess` remains.
Android's `ProviderInfo` defines non-multiprocess providers as a single instance in `processName`.
This is a concrete process-placement defect; whether it resolves those real Store crashes still
requires a runtime comparison.

The fixture declares a main-only provider with a process-local initialization marker. It requires
that marker in the main activity process and rejects it in the separate provider process. The
existing cross-process payload/UID/denial checks remain. Validation is pending.

The already-running old-host comparison failed in **28.258 seconds** with `Main-only provider was
initialized in the remote provider process`, confirming the fixture detects the misplaced instance.
The initial process-selection build/lint/JVM run passed in **5m 24s**. Per the user's revised
verification preference, no corrected-host emulator rerun was started: subsequent development
uses code-first checks and reserves Android integration for a release-candidate gate.

Both installation paths now share a pure, JVM-tested process selector. Exact main/private process
matching, explicit multiprocess declarations and missing metadata are covered without a device.
Pure Wi-Fi attribution tests also moved from instrumentation to the host JVM; the fixture's real
OS query remains in the pre-release integration suite. This changes where checks execute, not
whether their assertions are required.

### Initialization failures and waiting callers

Code inspection found off-main-thread initialization waiting on a condition variable that was
opened only after successful initialization. An exception left the caller blocked indefinitely.
Dispatch now uses a future that returns the original failure to the caller, handles a rejected
main-thread post, retains interruption status and cancels work that is still queued when its
caller is interrupted. It does not pretend that an action that actually hangs has completed.

The inherited fallback from the declared Application to plain Application after creation failure
is removed. Provider initialization errors are also propagated instead of discarded. A first
initialization failure is latched for the lifetime of that app process, so partially populated
binding fields cannot later report successful initialization or trigger an unsafe silent retry.
Pure JVM tests cover completion, exception/error identity, rejection, interruption, queued-work
cancellation and the persistent first-failure state. The final batched code validation passed:
**143 JVM tests**, **29 prepared-source checks**, debug/test APK builds, debug lint and the packaged
output boundary (**6m 20s** for the combined Gradle invocation). An intermediate lint invocation
crashed while resolving a newly added test declaration; it is not counted as a passing check.
The final invocation recompiled the completed source set and passed without disabling lint.
No new emulator run was performed for this batch; these changes are not claimed as real-app login
fixes. The release-candidate runtime gate remains outstanding.

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

September 18 continuation: a real YouTube Music Add account attempt exposed a different lifetime
gap. Its host UI was cached while the imported activity was foreground. Android repeatedly killed
the control process with `Sync transaction while frozen`; the imported main thread waited in
`ActivityThread.acquireProvider` via `BJobManager.queryJobRecord`, producing an input ANR.
Every imported client now holds its own ordinary, explicit host control-service binding. Host
services bypass imported-service resolution to avoid recursively contacting the same control
process during connection/recovery. A later real-app attempt retained the control process at
foreground-bound importance and did not reproduce the ANR; this does not prove credential-form
compatibility or survive every lifecycle transition.

The private account transport delegates to the imported `AbstractAccountAuthenticator` public
implementation, preserving the real response binder, asynchronous results and standard errors.
Only an exact declared authenticator in the active imported package/user is eligible. A bounded,
identity-tracked local Binder walk handles modular forwarding layers; remote, ambiguous and
oversized graphs are left unchanged. Android ACCOUNT_MANAGER permission remains denied; no
host accounts, credentials, token fabrication or platform-permission changes are involved.
The fixture checks two forwarding layers with a cycle, a real immediate response, an asynchronous
response and a sanitized failure, as well as the existing PCM capture and split-update checks.
The local API 36 fixture passed both tests after correcting its expected exception type from
IOException to AuthenticatorException. Debug build and 148 JVM tests passed; the preceding
direct-transport/lifetime build also passed lint. The newer forwarding-layer change still requires
the complete runtime/lint gate before release. Subsequent verification passed debug/test build,
lint and all **24 local API 36 runtime tests in 95.324 seconds**, including four additional
authenticator discovery tests (exact owner, cyclic forwarding, ambiguity, size bounds and ignored
static/unrelated references). GitHub run `35246850378` passed the host debug/release build and code
checks for `cbe363f`; its API 34/36 runtime jobs were skipped, not passed.

Actual-app status remains incomplete: before the forwarding-layer change, Google Add account
still reached Android's protected Transport through its modular Binder wrappers. Apple Music
opened Home, Settings and Sign In with a real external WebView renderer, but HTML bootstrap
timed out in `LoadingHTML` after 30 seconds. No credential-entry form, completed login or
subscription playback is claimed from these observations.
After the forwarding-layer change, Google returned an actual login continuation Intent targeting
`UnpackingRedirectActivity` and attempted to initialize `com.google.android.gms.ui`; no
ACCOUNT_MANAGER rejection appeared in that attempt. The continuation did not become a visible
credential-entry form. This is partial progress, not successful Google login-screen compatibility.

A subsequent trace localized that continuation failure to Android killing the new proxy process
for `timeout publishing content providers`, before the container attach log appeared. The failed
provider acquisition also exposed a code defect: a null initialization Bundle was dereferenced,
leaving a published process record with a closed initialization latch. A second Add account attempt
then waited indefinitely on that stale record. Startup now rejects a null response, rolls back the
record on false/exception, and releases waiters in a finally path. Serialized startup no longer
waits on an earlier incomplete record, and delayed process-death removal is identity-checked.
Five new JVM cases cover success, missing response, exception, rollback failure and retry.
The updated debug/test build, 153 JVM tests, 34 prepared-source checks and both local API 36
fixture tests passed (64.621 seconds). Android's normal package compiler was run with `speed`
on the test host before this fixture run; this is a test-environment optimization, not a product
prerequisite, a timeout override or proof that the real-app continuation is fixed.

The next ordinary Google Add account attempt started the UI process and reached the real
`AccountIntroActivity`. It then crashed in `UserManager.isMainUser()` because `getUserInfo`
escaped to the device user service and required QUERY_USERS/CREATE_USERS/MANAGE_USERS.
The container now maps read-only user/profile queries to its persisted active-space record.
Unknown or other-space IDs return absence; full music spaces have no invented profile parent.
The returned full-user metadata does not mark the app as an Android administrator/system user,
grant permissions, create OS users or expose device profiles. Missing internal service remains
an error rather than a fabricated user. The ordinary-UID fixture verifies the main-space record,
absent-user lookup, one local profile, no profile parent and denied OS user-management permissions.
Debug/test builds, 153 JVM tests, 35 prepared-source checks and both API 36 fixture tests passed
(70.252 seconds). Actual Google credential-form verification remains pending for this change.

With the user metadata fix, Google's real AccountIntroActivity, WrapperControlledActivity and
PreAddAccountActivity were created. PreAddAccount then failed when getSeedAccountOptions escaped
to the host user service (MANAGE_USERS required). Music spaces do not provision device-setup
accounts: read-only seed name/type/options now return absence without querying host data or
intercepting protected seed-account writes. The fixture verifies all three absent values.
The follow-up debug/test build and lint passed (zero errors, nine existing warnings), along with
153 JVM tests, 35 prepared-source checks and all 24 API 36 runtime tests in 95.82 seconds.
These results still do not establish that Google's credential-entry form or Apple's HTML
bootstrap works; actual-app screen evidence must be recorded separately.

The next Google attempt again encountered a cold provider-start timeout. After ordinary Android
`cmd package compile -m speed -f` on the test host, a second attempt launched a fresh UI process
without resetting data or blocking on the previous failed record. It progressed beyond the seed
query, but a YouTube Music focus ANR and other defects still prevented credential entry:

- The control-process Intent sanitizer tried to deserialize Google's modular AddAccountController,
  could not load its class, and deleted the continuation payload. Application extras now remain
  opaque in transit; the receiving application sets its own class loader without marker rewriting
  or deleting unknown values. Two Android parcel tests passed (0.044 seconds), including a class
  loader that cannot load the payload, nested bundles/lists, a Class value and selector extras.
- Google check-in queried the host SIM serial number without Android's privileged identifier
  permission and crashed. Music spaces do not import SIM identities: ICCID/IMSI queries return
  absence, not real host identifiers, generated values or permission grants. The fixture checks
  absent identities and denied privileged phone permission.
- Restarting that service selected slot 0 even though YouTube Music still owned it. The engine
  mutated framework process records to guest names; Android 16 ActivityManager can cache this
  list (`rateLimitGetRunningAppProcesses`). Guest process reporting now copies the full parcelled
  record before renaming it, and allocation also excludes the container's own reserved slots.
  A dedicated Android test checks process name/package-array isolation and metadata preservation.

These are code fixes for observed failures, not a successful commercial login or release claim.
The combined debug/test build, 153 JVM tests, 38 prepared-source checks and APK product boundary
passed. All 27 local ordinary-UID API 36 tests passed in 102.512 seconds, including the new parcel
and process-snapshot tests plus the SIM-absence fixture assertions. This batch's full lint/release
gate is delegated to its exact-commit CI; the preceding seed-metadata batch passed local lint.

The next actual Google attempt preserved `AddAccountController` through the continuation and
reached `PreAddAccountActivity`, but ended in `ErrorActivity` with a server-communication message.
The underlying recorded event was `[CheckinHelper] Checkin timed out` after 10 seconds: the
check-in API received the request, but no successful completion was observed. This is **not**
evidence of a credential-entry form, nor sufficient evidence to attribute the failure to Google's
servers or the network. A prior cold UI-process attempt again hit Android's provider-publication
timeout, and the following attempt successfully allocated a fresh process.

Code review also found startup still discovering the newly initialized PID through Android's
potentially cached process list. The private, non-exported provider now returns its own PID with
its live client Binder. The control process validates the response before attaching the client,
rejects missing/legacy replies and its own PID, and sets the PID before any rollback can run.
It no longer declares successful initialization failed merely because a process snapshot is stale.
Three Android tests cover parcel round-trip identity, old/missing replies and invalid PID/client
publication. Debug/test builds, 153 JVM tests and 39 prepared-source checks passed. The combined
API 36 suite passed 30 tests in 93.627 seconds; a redundant MainActivity launch occurred during
the final UI test, so the UI class was rerun separately without manual interaction: all three
tests passed in 30.669 seconds. This does not
change the unresolved real-app credential-screen gate or either audio-output path.

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
It checks that the original app process remains alive after the window, but neither submits
credentials nor accepts agreements automatically. Normal CI does not wait.

```sh
adb push /path/to/legitimately-obtained.apks /data/local/tmp/virtualdap-app.apks
adb shell run-as com.virtualdap.host cp /data/local/tmp/virtualdap-app.apks cache/compat-app.apks
adb shell am instrument -w -r -e class com.virtualdap.host.ContainerInstrumentedTest \
  -e externalApk compat-app.apks -e externalPackage com.example.music \
  com.virtualdap.host.test/androidx.test.runner.AndroidJUnitRunner
```

For regular users, the Music space supports file import and copying installed catalog music apps.
Only APK code is copied; the original app's private data/accounts are not accessed.
