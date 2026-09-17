# Downloadable APK release

The user requested a downloadable GitHub Release when VirtualDAP reaches a usable level.
This is a delivery requirement, not permission to label an unverified development build stable.
Physical-device/DAC testing is outside the requested validation scope.

## Development checks and the pre-release integration gate

Routine pushes/PRs run code-first verification: JVM/native tests, prepared-source checks, lint,
debug/release builds and APK boundaries. They no longer start an emulator matrix automatically.
Pure Wi-Fi caller and provider-process policies have host-JVM tests; real framework/IPC/audio
integration fixtures remain available for the release gate.

After batching improvements, manually dispatch `build_virtualdap.yml` with `run_runtime=true`
for the selected release-candidate ref. Verify its resolved commit matches the release commit,
then require the host job and both API 34/36 runtime jobs to pass before publication. A skipped
runtime job on an ordinary push is **not** runtime evidence. Any subsequent product-code change
invalidates the previous candidate's runtime result. Signed-release artifact checks below still
apply. Do not publish simply because code-first CI is green.

## Published limited alpha 3

`v0.1.0-alpha.3` is a public **prerelease** from
`adb7a3a4f789a3c69b81053d165c6dcb9980412c`. The
[release](https://github.com/sampple-korea/VirtualDAP/releases/tag/v0.1.0-alpha.3) contains only
the installable APK and `SHA256SUMS`.

- APK SHA-256: `32836729e3e54f8169624bb01e660a60ee975d166e5391545095a66ac12969e3`;
  size 63,937,990 bytes, version code 3. The signing certificate is unchanged from alpha 1/2.
- An ordinary update install over the actual public alpha 2 succeeded without uninstalling or
  clearing its data. The signed, non-debuggable release APK passed **15 tests in 115.942 seconds**
  on the ordinary-UID API 36 emulator. The separately signed instrumentation APK is not published.
- Signature, minimum API 34, `debuggable=false`, 16 KB ZIP alignment and four-ABI USB/license/
  retired-code checks passed. Local release build/lint and **122 JVM tests** passed (6m 31s).
- Exact-commit GitHub run `35007733625` passed the host build and both API 34/36 runtime jobs.
  The preceding classifier-only run was cancelled by the release-preparation push; it is not
  counted as a separate passing run.
- UAC2 AudioControl ownership and error diagnostics address concrete omissions behind the user's
  failed clock negotiation. Physical FreeDSP output is not yet verified. Apple Music login and
  usable YouTube Music sessions remain unresolved, as the release notes explicitly state.

## Historical limited alpha 2

`v0.1.0-alpha.2` was published as a **prerelease**, not stable, on September 15, 2026,
from commit `2e22a46e1e5517cd263b52ac9224b7539df2271d`.
The [release](https://github.com/sampple-korea/VirtualDAP/releases/tag/v0.1.0-alpha.2) contains
only the installable APK and `SHA256SUMS`.

- APK SHA-256: `fd0648ede0a46ad64ae4589e6fbaaab7381d98f385f89afada6765cdf73cfdc5`;
  size 63,937,990 bytes, version code 2.
- The signing certificate matches alpha 1: SHA-256
  `273e0baf37614f54c634bd9cf7ae16826e7b2e2a38a7181a15db3ebd5c1b7c60`.
  An ordinary update install over the signed alpha 1 succeeded without uninstalling it.
- Signature, API 34 minimum, `debuggable=false`, four-ABI USB/license/retired-code boundary,
  and 16 KB ZIP alignment checks passed for the signed APK.
- GitHub run `34970516872` passed the host debug/release build and API 34/36 runtime jobs
  for this exact commit. Local release build/lint and 116 JVM tests also passed.
- The actual signed release APK passed all **15 instrumentation tests in 60.405 seconds** on
  the ordinary-UID API 36 test emulator. The test-only APK used the same signing identity
  for this check and is not distributed.
- Before testing, boot-time system/launcher ANR dialogs prevented an unobscured app screen.
  Bluetooth was disabled in the test emulator and it was rebooted without clearing app data;
  the remaining System UI dialog was dismissed before instrumentation. Product code, APK bytes,
  and test acceptance criteria were unchanged. No failed/obscured attempt is counted as a pass.
- USB alternate-selection and Apple Music welcome-screen evidence are described in
  [app compatibility](APP_COMPATIBILITY.md). These do not establish subscription playback,
  complete music-service support or measured physical USB output.

The broader final objective remains open. Release notes distinguish these improvements from
unverified account, provider and DAC compatibility.

## Historical limited alpha 1

`v0.1.0-alpha.1` was published as a **prerelease**, not stable, on September 15, 2026,
from commit `859997af68a35b4aea535f3ada7ff179049a9997`.
The [release](https://github.com/sampple-korea/VirtualDAP/releases/tag/v0.1.0-alpha.1) contains
only the installable release APK and `SHA256SUMS`, not test APKs, keys or personal data.

- APK SHA-256: `578ec249a4de03d4401f47a8af4fca391df99d157214de40aac76000851eb24d`.
- Signing certificate SHA-256: `273e0baf37614f54c634bd9cf7ae16826e7b2e2a38a7181a15db3ebd5c1b7c60`.
- APK signature verified, minimum API 34, `debuggable=false`, version `0.1.0-alpha.1`.
  Four-ABI USB/library-license and retired-code checks passed, as did 16 KB ZIP alignment.
- GitHub run `34899873829`: host debug/release build/lint and API 34/36 ordinary-UID runtime passed.
- Local debug/release build/lint and 114 JVM tests passed. The actual release APK was installed
  in a separate API 36 emulator; all 15 instrumentation tests passed in **55.004 seconds**.
  The test APK was signed with the same identity solely for local instrumentation; it is not distributed.
- Earlier release-APK attempts were not counted as passes: an emulator Google Play services ANR
  obscured two UI checks, and a later boot-time startup ANR killed instrumentation before test discovery.
  After ordinary app launch and emulator startup settled, the identical APK passed the full suite.
- The independent-player PCM signal check is documented in [app evidence](APP_COMPATIBILITY.md).
  Physical USB/DAC playback and broad music-service compatibility remain unverified.

The release notes explicitly state the supported scope, missing overlapping-output support,
USB limitations and installation/signature/data-loss cautions. The broader final objective remains open.

## Historical unsigned checks and future release gates

CI also builds and lints the unsigned release variant and checks its API 34 minimum,
non-debuggable manifest and product-output boundary. This validates packaging only; the unsigned
file is not published as an installable release. The same checks passed locally on September 14,
2026 (release lint: zero errors, seven warnings) before the subsequent account-adapter changes.
The default-USB/Korean-UI source at `eb5d5bd` also passed local unsigned release build/lint and the
all-ABI USB/license/retired-code boundary check. Its manifest reports minimum API 34 and
`debuggable=false`. It remains unsigned and is not an installable GitHub Release asset.

Before publishing a user-facing release:

- Build, lint and regression tests must pass for the exact release commit, including ordinary-UID
  API 34 and API 36 container tests and the dual-output APK boundary check.
- Validate the intended user flow: install, grant USB access and select a DAC (or explicitly select
  a supported advanced official output), import a music
  app, initialize it, and exercise supported capture/control/error recovery. Distinguish fixture,
  commercial-app initialization, account/login and playback evidence. A catalog entry or an
  Application.onCreate callback alone does not establish a usable music-service session.
- Software checks must cover the supported output contract; do not imply measured DAC playback
  from mixer preference, emulator capture or a test receiver. Do not add ordinary-mixer fallback
  or switch between USB and official mode automatically to make an unsupported device appear usable.
- Identify unsupported API paths and app/format boundaries explicitly. A limited prerelease must
  describe its verified scope; it must not imply the broader final goal has been achieved.
- Produce an installable, non-debuggable release APK, verify its signature, API 34 minimum,
  native ABI contents and absence of retired emulation/authentication code. Keep a stable signing identity
  for future updates; private keys/passwords must never enter Git or public build artifacts.
- Publish the APK as a GitHub Release asset with a checksum, version/commit identifier, installation
  instructions, supported scope and known limitations. Provide the user with its download link.
  A CI artifact alone is not the requested downloadable release.

Current evidence and unverified commercial-service stages are tracked in
[APP_COMPATIBILITY.md](APP_COMPATIBILITY.md). No release is asserted by this checklist.
