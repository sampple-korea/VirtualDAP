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
