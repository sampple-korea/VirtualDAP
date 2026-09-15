# Direct USB output

**Default product mode under validation.** Kotlin sources live under the historical
`compatibility/direct_usb/kotlin` directory; native sources are under `app/src/main/cpp/usb`.
The product build includes both layers for all four ABIs. JVM and simulated native tests cover
protocol behavior; they do not establish successful playback on a particular physical DAC.

## Reported UAC2 clock failure and interface ownership

The user reported successful USB permission/selection but failure of the 48 kHz PCM self-test
on every alternate with `USB clock returned a truncated response`. In alpha 2 this message also
covered **negative libusb errors**, so it did not prove that the DAC sent an incomplete payload.
The native output claimed AudioStreaming, but not the AudioControl interface addressed by UAC2
clock requests. Android's kernel USB path checks ownership of interface-recipient requests
(`drivers/usb/core/devio.c`, `check_ctrlrecip` / `checkintf`); an implicit claim cannot perform the
explicit kernel-driver handoff that the native transport requests through libusb.

The output now validates and claims the descriptor-declared AudioControl interface before the
streaming interface for UAC2, and releases both, including partial-open failures. UAC1 retains its
endpoint-based path. Clock reads distinguish negative transport errors from actual short reads
and include the request, target and byte counts. No rates are invented, no clock readback is
skipped, and no mixer or other output is used as a fallback.

The native test models refusal of an interface control request without ownership, successful
control/stream claims, reverse release, denied control access, denied stream access after control
claim, and a missing control descriptor. Native DSP/USB tests, 118 JVM tests, debug build/lint,
and all 15 local ordinary-UID API 36 tests passed (76.086 seconds for the runtime suite).
This fixes a concrete ownership omission and misleading diagnostics, but the user's DAC's actual
negative status was hidden in alpha 2: physical resolution is not yet proven by these tests.


VirtualDAP contains its own independently implemented USB output path.

## Selection and permissions

The output screen provides explicit Android USB permission and device selection. USB output is
the default; Android official bit-perfect is an advanced mode and does not take over automatically.
Initial USB software volume is 25%, independent of Android system volume. Changing volume away
from unity invalidates bit-preserved status. DoP/native DSD do not apply software volume and need
an explicit DAC-support and safe-hardware-volume confirmation.

The native engine wraps a duplicate of the descriptor obtained from `UsbManager.openDevice`.
It does not enumerate native USB device nodes, open arbitrary `/dev/bus/usb` paths, require root,
reset the device or change its active configuration. The selected audio interface is claimed with
kernel-driver handoff, idled for clock negotiation, then switched to the selected alternate setting.
Cleanup returns it to alternate zero and releases the interface.

## Implemented transport

- Source-pinned, separately linked libusb 1.0.30 at
  `87a55632db62c9bdc58cd31d3ccfa673f1bb017f`, built for all four Android ABIs.
- UAC1 endpoint and UAC2 clock rate requests with readback through the existing clock-control layer.
- Descriptor-derived UAC2 clock source/selector/multiplier topology, including recursive-graph
  rejection, verified selector switching with rollback, exact rational multiplier mapping,
  read-only clocks and bounded post-change clock-validity polling.
- Full-/high-/SuperSpeed isochronous output with bounded queued/in-flight bytes.
- Four asynchronous transfer slots, exact nominal fractional packet lengths, explicit 10.14/16.16
  feedback and validation of each completed packet's status and length.
- Pause drains already submitted packets and retains the source queue; flush discards only queued
  data after pause. Finish drains the remaining source, including a partial final transfer.
  A short settling interval follows USB completion before interface release; DAC presentation
  latency remains unmeasured.
- Cancellation callbacks complete before transfer buffers are freed. The caller's original USB
  descriptor is not closed by the native layer.
- No artificial PCM, DoP or DSD silence is inserted on starvation. Underruns and transfer failures
  invalidate output verification instead of silently claiming uninterrupted playback.

The PCM adapter preserves integer precision when widening or adding left-justified USB subslot
padding, including 20-bit samples left-aligned in three-byte subslots. Narrowing,
float-to-integer conversion and application volume are explicit processing.
USB completion counters describe host-controller transfers, not measured DAC presentation.
Active playback holds a lifecycle-bound partial wake lock, including when AudioFlinger is bypassed.

Exact source PCM is always negotiated first. If a DAC rejects it, the sink queries UAC2 clock
ranges (or uses validated UAC1 descriptor ranges), favors another rate in the same 44.1/48 kHz
family, and retries compatible alternate settings. Rate changes use libsamplerate's
`SRC_SINC_BEST_QUALITY` state across packets; channel/encoding conversion remains explicit. A
converted path cannot report bit-perfect/source-preserved status. Flush replaces the filter state,
and finish writes its final filter tail before draining USB.

The typed DSD adapter accepts canonical time-ordered DSD bytes and connects them directly to this
same bounded transport. Reference-qualified native U8/U16/U32 layouts preserve word/bit order.
DoP framing uses an exact 24-bit carrier and requires explicit confirmation that the selected DAC
supports DoP; a high advertised PCM rate alone is not treated as that proof. Neither DSD mode has a
volume, mixer or sample-rate-conversion entry point.

## Current boundaries

The direct sink reserves one active music stream. Neither the official route nor the direct sink
supports overlapping output tracks. Ambiguous multi-format and RAW alternatives are excluded from the PCM path. Channel fallback
supports exact layouts, mono/stereo expansion, stereo/mono conversion and multichannel-to-stereo or
mono downmix; it does not invent arbitrary surround channels. Implicit-feedback endpoints and
vendor-specific feedback/native-DSD quirks require further integration.

Native DSD/DoP framing, qualified mode selection and direct transport composition are connected
to the foreground DSF/DSDIFF tool. A generic RAW descriptor or high PCM rate is never
treated as proof of DSD support. ITF mode-switch devices and other vendor sequences remain disabled
until their control transactions are implemented.

## Validation without a DAC

`bash scripts/verify_dsp.sh` builds the real native transport against a deterministic libusb test
backend and verifies exact bytes, fractional rates, feedback, pause/flush/drain, partial final
transfers, cancellation, short-packet failure, device disconnection, invalid feedback and descriptor
ownership. These tests also passed AddressSanitizer and UndefinedBehaviorSanitizer locally.

JVM tests cover PCM subslot packing, 20/24/32-bit widening/narrowing, float handling, format-family
planning, alternate-setting retry, bounded conversion, DSD word/DoP framing and the complete PCM and
DSD sinks with partial writes, discontinuities, truncation and failing negotiation. Current Android
instrumentation verifies best-sinc packet continuity and pass/stop-band behavior. Additional
USB-mode checks load the packaged JNI with an invalid descriptor, reject a missing USB device
without Android fallback, and check Korean home/Tools navigation. Passing results must be recorded
after running these checks; test source alone is not evidence.
The container/PCM/overlap/split-install tests use a separate paced capture receiver.

The initial default-USB/Korean-UI integration passed debug build/lint, 112 JVM tests, all 15
ordinary-UID API 36 instrumentation tests (98.914 seconds), three DSP/USB native tests, two
container transport tests, 12 prepared-source checks and the updated APK boundary check.
The new instrumentation executed the packaged USB JNI rejection path, missing-device/no-fallback
path, Korean music-home/Tools navigation and explicit USB/official mode changes. Actual physical
DAC transfers remain outside the test scope; this is not a FreeDSP certification.

No physical DAC, USB analyzer or analog output has been tested. This is code/protocol validation,
not a claim of measured hardware playback.

libusb's full LGPL license and source/build provenance remain in the repository; its full license
is copied into the APK by `prepareUsbNotices`. The BSD-licensed
libsamplerate source is pinned at `0844c208f683527c08ea8a80acc13b398aa9c8bf`, and its license is
included in the same assets. Upstream:
[libusb Android integration](https://github.com/libusb/libusb/tree/v1.0.30/android),
[asynchronous transfers](https://libusb.sourceforge.io/api-1.0/group__libusb__asyncio.html),
[libsamplerate](https://github.com/libsndfile/libsamplerate/tree/0844c208f683527c08ea8a80acc13b398aa9c8bf).
