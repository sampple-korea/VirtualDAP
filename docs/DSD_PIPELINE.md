# DSD processing

The Player accepts validated DSF and uncompressed DSDIFF. The readers stream bounded interleaved
DSD packets, preserve source bit order, reject malformed/truncated data and reject DST compression.

The user explicitly selects one of two product modes:

- **DSD → PCM:** the stateful 96-tap decoder performs fixed 8:1 decimation. A matching official
  bit-perfect PCM format must exist at that exact rate and channel count. Float or integer sample
  representation is selected from advertised formats. No sample-rate or channel fallback runs.
  The UI identifies this as converted audio, never source DSD preservation.
- **DoP:** a packed 24-bit carrier at DSD rate / 16 passes through the exact official bit-perfect
  sink. The user confirms this DAC's DoP support; PCM capability discovery cannot certify DoP
  decoding. Alternating markers, channel order and packet-boundary state are preserved.
  Incomplete DoP frames fail; gain and resampling are never applied.

The direct/native DSD implementation is retained in the compatibility source area and is unavailable
from the current product UI and output service.

The playback worker owns reader/output resources, applies pause between complete packets, and
closes/interrupts blocked input during cancellation. Failed or cancelled streams are not drained.
The bridge and local-file player are mutually exclusive.

JVM tests cover framing, parsing and lifecycle; native tests cover filter behavior; Android tests
exercise JNI state and unsupported-output rejection. These tests do not certify physical DAC output.
Required source licenses remain in the repository and APK notices.
