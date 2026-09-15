# Direct USB output source

This implementation is now integrated as the default USB output mode. The directory name is
historical; official Android bit-perfect output remains a separately selected advanced mode.

- Kotlin sources in `kotlin/` are included in the app main source set and tested by JVM regressions.
- Native transport sources and simulated transfer tests remain in `app/src/main/cpp/usb`
  and `app/src/main/cpp/tests/UsbIsoOutputTest.cpp`.
- Native Android transport targets require `VIRTUALDAP_BUILD_DIRECT_USB`; the product enables it
  for all four ABIs. The Korean output screen handles mode, device and permission selection.
- USB descriptors, clock topology/rate negotiation, PCM packing, native DSD layouts, DoP and
  partial-transfer behavior retain their regression tests.
- Required dependency licenses and provenance remain available in the repository.

Permission lifecycle, output ownership, disconnect, format negotiation and UI disclosures remain
release checks. Automatic fallback between USB and official output is forbidden.

PCM negotiation checks all compatible alternate settings for source-preserving playback before
trying same-rate/channel packing conversion, and checks those before rate/channel conversion.
An early alternate rejecting the requested clock must not cause resampling when another alternate
accepts the original samples. UAC2 clock-range discovery follows the same ordering. Conversion
candidates and unusable profiles are cached only for the current configuration attempt; actual
clock acceptance is still verified when starting each candidate.

The regression suite covers both a rejected 96 kHz UAC1 clock with another exact alternate and
UAC2 range discovery where an earlier alternate exposes only 48 kHz. These simulated transports
exercise negotiation and PCM packing, not physical DAC compatibility.
