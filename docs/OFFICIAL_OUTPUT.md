# Official bit-perfect output

This is the explicitly selected advanced mode. Default playback uses the independent
[USB output path](USB_OUTPUT.md); no transport switches automatically on failure.

Android 14 / API 34 is the minimum supported system version. Capability is discovered per output
with `AudioManager.getSupportedMixerAttributes`. Only entries with
`AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT` are eligible.

The user selects a supported output. The exact sample rate, PCM encoding and supported channel
layout must match an advertised mixer format. The service sets preferred mixer attributes before
creating the AudioTrack, requests that device, and verifies preference and routing during output.
Failure terminates submission. No ordinary mixer, system-default route, resampling/downmix or
direct USB fallback is used.

Only one output session may own the route. Application gain must remain 1.0 on both channels.
Crossfade and overlapping active streams are unsupported. Output status distinguishes the source
format, configured PCM/carrier and observed route. API preference is software evidence, not a
physical measurement of DAC bits.

DoP requires an exact packed-24-bit carrier and explicit DAC capability confirmation. DSD-to-PCM
is a user-selected source conversion; its fixed decoded clock must be supported. Neither mode
automatically switches to the other.

Unsupported routes stay visible with an unsupported label. Device/DAC support is not implied by
Android version, USB connection or the service catalog.
