# Direct USB compatibility source

This implementation is retained for a future explicitly selected compatibility mode.
It is not part of the current product output.

- Kotlin sources in `kotlin/` are included only in the app JVM test source set.
- Native transport sources and simulated transfer tests remain in `app/src/main/cpp/usb`
  and `app/src/main/cpp/tests/UsbIsoOutputTest.cpp`.
- Native Android transport targets require `VIRTUALDAP_BUILD_DIRECT_USB`; the product does not
  enable it. There is no Gradle runtime mode or UI toggle that enables compatibility output.
- USB descriptors, clock topology/rate negotiation, PCM packing, native DSD layouts, DoP and
  partial-transfer behavior retain their regression tests.
- Required dependency licenses and provenance remain available in the repository.

Before integrating a future mode, review permission lifecycle, output ownership, disconnect,
format negotiation and UI disclosures. Automatic fallback from official output is forbidden.
