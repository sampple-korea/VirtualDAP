# Retained direct USB compatibility implementation

**Inactive in the product.** Sources are retained under `compatibility/direct_usb/kotlin`; native
sources remain under `app/src/main/cpp/usb`. JVM and simulated native tests cover them. The normal
APK contains no direct-USB Kotlin classes or USB transport shared library. The following describes
the retained implementation, not a currently available output option.


VirtualDAP contains its own independently implemented USB output path.

## Selection and permissions

The retained controller supports explicit Android USB permission and descriptor inspection.
A future compatibility mode must wire these controls deliberately; current Diagnostics and the
output selector expose only official Android bit-perfect capability discovery.

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

The retained direct sink reserves one active music stream. Neither the current official route nor
the retained sink supports overlapping output tracks. Ambiguous multi-format and RAW alternatives are excluded from the PCM path. Channel fallback
supports exact layouts, mono/stereo expansion, stereo/mono conversion and multichannel-to-stereo or
mono downmix; it does not invent arbitrary surround channels. Implicit-feedback endpoints and
vendor-specific feedback/native-DSD quirks require further integration.

Native DSD/DoP framing, qualified mode selection and direct transport composition remain
JVM-tested but are disconnected from the foreground DSF/DSDIFF player. A generic RAW descriptor or high PCM rate is never
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
instrumentation loads only the product DSP libraries and verifies best-sinc packet continuity and
pass/stop-band behavior. Direct USB JNI is no longer loaded by product instrumentation.
The container/PCM/overlap/split-install tests use a separate paced capture receiver.

No physical DAC, USB analyzer or analog output has been tested. This is code/protocol validation,
not a claim of measured hardware playback.

libusb's full LGPL license and source/build provenance remain in the repository; the current APK
does not package its binary or generated license asset. The BSD-licensed
libsamplerate source is pinned at `0844c208f683527c08ea8a80acc13b398aa9c8bf`, and its license is
included in the same assets. Upstream:
[libusb Android integration](https://github.com/libusb/libusb/tree/v1.0.30/android),
[asynchronous transfers](https://libusb.sourceforge.io/api-1.0/group__libusb__asyncio.html),
[libsamplerate](https://github.com/libsndfile/libsamplerate/tree/0844c208f683527c08ea8a80acc13b398aa9c8bf).
