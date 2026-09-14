# DSD processing

The Tools menu accepts validated DSF and uncompressed DSDIFF. The readers stream bounded interleaved
DSD packets, preserve source bit order, reject malformed/truncated data and reject DST compression.

The user explicitly selects a source processing mode within the selected output transport:

- **DSD → PCM:** the stateful 96-tap decoder performs fixed 8:1 decimation. For official output a matching
  bit-perfect PCM format must exist at that exact rate and channel count. Float or integer sample
  representation is selected from advertised formats. No sample-rate or channel fallback runs.
  The USB mode negotiates the decoded PCM with the DAC and may perform rate/channel conversion.
  The UI identifies this as converted audio, never source DSD preservation.
- **DoP:** a packed 24-bit carrier at DSD rate / 16 passes through the exact official bit-perfect
  sink. The user confirms this DAC's DoP support; PCM capability discovery cannot certify DoP
  decoding. Alternating markers, channel order and packet-boundary state are preserved.
  Incomplete DoP frames fail; gain and resampling are never applied.

- **Native DSD (USB only):** only reference-qualified native layouts can be selected. Generic RAW
  descriptors are not enough. Native DSD and DoP use the typed transport without software gain or
  resampling, and require the user to confirm DAC support and safe hardware volume.

The default USB mode's DSF/DSDIFF tool is connected to direct PCM conversion, DoP and native DSD
adapters. The advanced official mode does not expose native DSD. Unsupported requests fail rather
than switching modes or falling back to PCM automatically.

The playback worker owns reader/output resources, applies pause between complete packets, and
closes/interrupts blocked input during cancellation. Failed or cancelled streams are not drained.
The bridge and local-file player are mutually exclusive.

JVM tests cover framing, parsing and lifecycle; native tests cover filter behavior; Android tests
exercise JNI state and unsupported-output rejection. These tests do not certify physical DAC output.
Required source licenses remain in the repository and APK notices.
