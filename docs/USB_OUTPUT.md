# Internal direct USB output

VirtualDAP contains its own USB output path. UAPP is a reference for behavior/architecture, not a
player that must be installed, a runtime dependency or a source of redistributed proprietary code.

## Selection and permissions

Diagnostics lists USB Audio streaming output interfaces. The user grants access through
`UsbManager.requestPermission`. Descriptor inspection does not claim an interface or change clocks.
After permission, the output selector offers a separate **Direct USB** route. It is opt-in; the
normal Android output/mixer route remains available.

The native engine wraps a duplicate of the descriptor obtained from `UsbManager.openDevice`.
It does not enumerate native USB device nodes, open arbitrary `/dev/bus/usb` paths, require root,
reset the device or change its active configuration. The selected audio interface is claimed with
kernel-driver handoff, idled for clock negotiation, then switched to the selected alternate setting.
Cleanup returns it to alternate zero and releases the interface.

## Implemented transport

- Source-pinned, separately linked libusb 1.0.30 at
  `87a55632db62c9bdc58cd31d3ccfa673f1bb017f`, built for all four Android ABIs.
- UAC1 endpoint and UAC2 clock rate requests with readback through the existing clock-control layer.
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
padding. Narrowing, float-to-integer conversion and application volume are explicit processing.
USB completion counters describe host-controller transfers, not measured DAC presentation.
Active playback holds a lifecycle-bound partial wake lock, including when AudioFlinger is bypassed.

The typed DSD adapter accepts canonical time-ordered DSD bytes and connects them directly to this
same bounded transport. Reference-qualified native U8/U16/U32 layouts preserve word/bit order.
DoP framing uses an exact 24-bit carrier and requires explicit confirmation that the selected DAC
supports DoP; a high advertised PCM rate alone is not treated as that proof. Neither DSD mode has a
volume, mixer or sample-rate-conversion entry point.

## Current boundaries

Direct USB currently reserves one active music stream. Use the Android output route for overlapping
tracks. Source rate/channel count must match an available direct profile; this path does not yet
perform sample-rate/channel fallback. Ambiguous multi-format and RAW alternatives are excluded from
the PCM path. Clock selectors/multipliers, implicit-feedback endpoints and vendor-specific feedback/
native-DSD quirks require further integration. The first matching PCM profile is tried; alternative
retry after a clock rejection is not yet implemented.

Native DSD/DoP framing, qualified mode selection and direct transport composition are invoked by
the foreground DSF/DSDIFF player and JVM-tested. A generic RAW descriptor or high PCM rate is never
treated as proof of DSD support. ITF mode-switch devices and other vendor sequences remain disabled
until their control transactions are implemented.

## Validation without a DAC

`bash scripts/verify_dsp.sh` builds the real native transport against a deterministic libusb test
backend and verifies exact bytes, fractional rates, feedback, pause/flush/drain, partial final
transfers, cancellation, short-packet failure, device disconnection, invalid feedback and descriptor
ownership. These tests also passed AddressSanitizer and UndefinedBehaviorSanitizer locally.

JVM tests cover PCM subslot packing, widening/narrowing, float handling, DSD word/DoP framing and the
complete PCM and DSD sinks with partial writes, discontinuities, truncation and failing negotiation.
Android instrumentation loads the actual native
library, rejects a non-USB descriptor without closing the caller's descriptor, and retains the
existing container/PCM/overlap/split-install tests.

No physical DAC, USB analyzer or analog output has been tested. This is code/protocol validation,
not a claim of measured hardware playback.

libusb's full LGPL license and source/build provenance are included in APK assets. Upstream:
[libusb Android integration](https://github.com/libusb/libusb/tree/v1.0.30/android),
[asynchronous transfers](https://libusb.sourceforge.io/api-1.0/group__libusb__asyncio.html).
