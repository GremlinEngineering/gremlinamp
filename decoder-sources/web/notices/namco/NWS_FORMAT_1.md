# Native Wave Stream, version 1

NWS is a new transparent register-log container, specified for this collection. It is not an existing console/arcade file standard, and is not VGM with a different suffix. NWZ is exactly gzip(NWS). These files contain the original waveform PROM (profiles1/2), or captured initialization and changes of packed waveform RAM (profile3), and original sound-driver register writes, not PCM, MIDI, MP3 reconstruction or generated notes.

Supported profiles: 1 = Namco PROM WSG, three mono voices; 2 = Namco 15XX PROM WSG, eight mono voices; 3 = CUS30 mono, eight voices with packed dynamic wave RAM and noise, verified at24000Hz input clock. CUS30 stereo, Pole Position, 54XX/62XX discrete or MCU effects and other devices are not supported by these profiles. They must not be silently omitted from claimed complete game audio.

All integers are little-endian unsigned 32-bit. Header is 64 bytes, initial PROM/wave RAM block is256bytes, followed by 12-byte events.

| Offset | Field |
|---|---|
| 0 | Four bytes `NWS` followed by `1A` hex |
| 4 | Version, 1 |
| 8 | Profile, 1, 2 or 3 |
| 12 | Physical WSG input clock: 96000 or 24000 Hz for the prepared games |
| 16 | Source event timestamp rate; older captures44100, CUS30 captures1536000ticks/second |
| 20 | Event count |
| 24 | End tick, exclusive for generated audio |
| 28 | Loop event index, or FFFFFFFF |
| 32 | Loop tick, or FFFFFFFF |
| 36 | PROM/initial wave RAM length,256 |
| 40 | PROM/initial wave RAM offset,64 |
| 44 | Event offset, 320 |
| 48 | Playback start tick, preserving earlier initialization and oscillator phase as preroll |
| 52–63 | Reserved, zero |

Each event is `[absolute_tick, opcode, argument]`, ordered by tick, with original ordering retained at equal ticks. Events are applied on the first internal chip sample at or after their timestamp. Timestamp resolution is1/event_rate seconds; this is not a claim of exact CPU-cycle bus timing.

| Opcode | Argument / behavior |
|---|---|
| 0 | `(register << 8) | raw_data`; profile 1 registers 0..31, profile 2 registers 0..63 |
| 1 | Abstract global enable, 0 or 1; used in software verification fixtures |
| 2 | Raw physical enable input `(offset << 8) | data`: profile 1 offset 0 and enable from nonzero data; profile 2 offset 0/1 and enable from offset, ignoring data |

The PROM is preserved byte-for-byte; its low four bits form eight 32-sample waveforms. Internal chip sample rate is the input clock repeatedly doubled until at least 192000 Hz, with frequency fractional bits increased from 15 by the same number of doublings. Muted voices and globally disabled output freeze counters, following pinned MAME. Profile 2 register offset 2 sets the waveform counter position, including the MAME unchanged-register early return. Volume, frequency and waveform registers follow the pinned source.

Output is stereo Float32 with mono duplicated, divided by `128 * voice_count`, without clipping. Exact rational box integration converts internal chip samples to the requested output sample rate. Analog board filtering is not modeled. Prepared albums use a conservative all-rates digital peak bound and optional common MD playback gain, not modified notes or PROM values.

Preroll executes all earlier writes and counters silently, preserving initialization. A loop replays events from the loop event index with timestamps offset by the loop duration, preserving oscillator state. The prepared ROM captures currently do not declare inferred loops: original driver repetition is retained inside the 180-second capture. MD specifies a finite playback window, with fade only for still-running cues.

A semantics-preserving optimization removes repeated identical writes only to documented ordinary frequency/volume/waveform latches. Counter-position writes, enable writes and other registers remain. Every prepared capture was rendered over the entire available duration before and after this optimization; Float32 SHA256 matched exactly. This verifies the optimization, not independent physical hardware accuracy.

Chip reference: BSD-3-Clause MAME `namco.cpp`/`namco.h`, commit `40d0f1125befade5edc08ec5699442ca3a229c01`. Independent Python oscillator/integration calculations, a 1500 Hz frequency fixture, invalid input rejection, native/browser full-duration comparisons and browser controls are archived with the player. No comparison with a physical arcade PCB is claimed.

Profile3 extension (player0.12): the256-byte block is initial packed wave RAM. Opcode3 writes `(wave_ram_address << 8) | data`; addresses0..255. Opcode0 accepts registers0..63 with CUS30 semantics. Opcode2 is rejected for profile3. The prepared CPU captures use1536000ticks/second,1536000Hz internal CPU cycles and25344cycles between video IRQs. Writes are timestamped at the beginning of the executing CPU instruction; exact bus-phase timing within the instruction is not claimed. Events are applied at the next192kHz chip sample. Supported event rates are1000..2000000Hz.

For profile3, CUS30 MAME method bodies were compiled verbatim in a separate device/stream shim, then compared over178seconds per original command. Full original traces and optimized streams matched exactly at192kHz. New browser/native checks cover all64 command probes at48kHz plus two main themes at44.1kHz. Legacy112 PROM/15XX files retain exact PCM over a10-second regression window. This is chip-port and browser validation, not an independent full-board CPU/physical measurement. YM2151 initialization-only writes in Hopping Mappy are preserved in the source trace; they do not key on channels or carry music in any of the32 tested commands.
