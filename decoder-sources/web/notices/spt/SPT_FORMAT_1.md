# SPT1 / SPZ version 1

Transparent music capture for original SNES games whose S-CPU streams music to the S-SMP during playback. This is not standard SPC, a reconstructed score, or a PCM recording. A .spz is gzip of exactly one .spt. The file contains the original running S-SMP/S-DSP state and the original host-to-APU port writes. It contains no complete game ROM.

Little-endian 32-byte header:

| Offset | Type | Meaning |
| --- | --- | --- |
|0|4 bytes|ASCII SPT1|
|4|u32|format/engine ABI version 1|
|8|u32|APU clock 1025280 Hz|
|12|u32|state size 66560 bytes|
|16|u32|end time, APU clocks from capture origin|
|20|u32|event count|
|24|u32|reserved 0|
|28|u32|reserved 0|

Then exactly 66560 state bytes, serialized by the bundled Snes9x bapu implementation: SMP::save_state, DSP::save_state, source reference_time, source fractional remainder, DSP.clock, CPU port registers, zero padding. SMP contains the original 64 KiB APURAM and complete micro-instruction/timer state; DSP includes echo and synthesis pipeline. Decoder ignores the source S-CPU reference_time and remainder, using captured integer APU clocks instead. ABI1 is tied to the exact bundled source and checksums; it is not an arbitrary Snes9x save-state interchange format. S-SMP/S-DSP code and algorithms remain those of the bundled upstream files; standalone integration replaces frontend dependencies with a PCM queue and fixed settings.

Each following event is 8 bytes: u32 absolute APU time; u8 port 0..3; u8 value 0..255; u16 zero. Events are stable sorted in their actual observed order. Replay advances the original S-SMP to each event time, synchronizes the S-DSP, then writes the original CPU port register. End time is final source synchronization. Native output is signed16 stereo at 32040 Hz, corresponding to this NTSC source emulator's 1025280/32 clock. Browser output uses linear sample-rate conversion; it does not invent notes or change the original musical bytes. This clock is an emulator timing choice; physical oscillator tolerance is not claimed.

The source capture harness selects one existing musical table entry, suppresses later UI track changes and nonmusic calls, and suppresses SimCity title-screen fades/stops. The source musical driver and musical data are not modified. SimCity: original selector D612B8, 14 valid entries at D613B5; Mario: C0B6E7, 8 valid entries at C0BBFA. Selection is a control operation, not a newly authored theme. Captures start immediately before the first original musical selector call, after the original APU initialization.

22 complete source/replay PCM comparisons have exact matching SHA256 and zero integer sample difference, over250s or262s per command. 24 full browser comparisons at48/44.1kHz have maximum float difference2.9802322387695312e-8 after conversion. Two additional source-rate browser comparisons at32040Hz have error0. These checks compare emulators and portability, not a physical SNES waveform.

At the original table level, SimCity commands03 and10 have unconfirmed display names; keep numeric names. The preserved ScenarioClear recording matches a short excerpt of command02, also used for Ending; it is not an independently identified additional source command.
