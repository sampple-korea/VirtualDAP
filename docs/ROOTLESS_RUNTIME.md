# Ordinary-APK runtime

Minimum Android version is 14 / API 34. Music-space apps use the host Android framework and run
under the host application's real kernel UID. Root, platform signing, a device allowlist and a
separate Android OS image are not required. App data is separate, but the shared-UID container is
not a security sandbox.

Current components are APK/split import, app launch/stop, decoded PCM interception and bounded
Unix-socket delivery. The receiver checks kernel credentials and accepts only its own application UID.

System media-controller and connectivity calls use the real host package only in their explicit
caller-attribution fields. Media tokens and command payloads are preserved. Network state, DNS,
private DNS, metering, validation, callbacks and permission errors come from Android unchanged;
the container does not invent an available network or report a failed registration as successful.
These adapters do not confer system privileges on imported Google/account services.

Default output follows [direct USB negotiation](USB_OUTPUT.md); the advanced mode follows
[the official bit-perfect contract](OFFICIAL_OUTPUT.md). An Android version alone does not prove
that a device/DAC/format exposes either capability. Unsupported combinations are reported without
automatically opening another output transport.
