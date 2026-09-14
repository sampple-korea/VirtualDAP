# Downloadable APK release

The user requested a downloadable GitHub Release when VirtualDAP reaches a usable level.
This is a delivery requirement, not permission to label an unverified development build stable.
Physical-device/DAC testing is outside the requested validation scope.

Before publishing a user-facing release:

- Build, lint and regression tests must pass for the exact release commit, including ordinary-UID
  API 34 and API 36 container tests and the official-output APK boundary check.
- Validate the intended user flow: install, select an officially supported output, import a music
  app, initialize it, and exercise supported capture/control/error recovery. Distinguish fixture,
  commercial-app initialization, account/login and playback evidence. A catalog entry or an
  Application.onCreate callback alone does not establish a usable music-service session.
- Software checks must cover the supported output contract; do not imply measured DAC playback
  from mixer preference, emulator capture or a test receiver. Do not add ordinary-mixer or direct
  USB fallback to make an unsupported device appear usable.
- Identify unsupported API paths and app/format boundaries explicitly. A limited prerelease must
  describe its verified scope; it must not imply the broader final goal has been achieved.
- Produce an installable, non-debuggable release APK, verify its signature, API 34 minimum,
  native ABI contents and absence of dormant output/emulation code. Keep a stable signing identity
  for future updates; private keys/passwords must never enter Git or public build artifacts.
- Publish the APK as a GitHub Release asset with a checksum, version/commit identifier, installation
  instructions, supported scope and known limitations. Provide the user with its download link.
  A CI artifact alone is not the requested downloadable release.

Current evidence and unverified commercial-service stages are tracked in
[APP_COMPATIBILITY.md](APP_COMPATIBILITY.md). No release is asserted by this checklist.
