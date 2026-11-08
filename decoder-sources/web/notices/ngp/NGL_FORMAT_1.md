# NGP Register Log 1 (NGL / NGZ)

NGL1 is a research container for events produced by an unchanged original NGP
music driver in pinned Beetle NeoPop. It is a new open format, not an official
console format. NGZ is an ordinary gzip stream of one NGL. No ROM, BIOS or
rendered PCM is needed by the player. The exact original source cartridge is
identified by SHA256 and retained separately for reproducibility.

All integers are little endian. Header length is 96 bytes.

| Offset | Type | Meaning |
| --- | --- | --- |
| 0 | 4 bytes | `NGL` followed by byte 0x1a |
| 4 | u32 | Version 1 |
| 8 | u32 | Event clock: 3072000 Hz |
| 12 | u32 | Pinned source output rate: 44100 Hz |
| 16 | u32 | Event count, 1..5000000 |
| 20 | u32 | Event offset: 96 |
| 24 | u64 | Original PCM frames to discard as hidden initialization preroll |
| 32 | u64 | Available selected-cue PCM frames after preroll |
| 40 | 32 bytes | SHA256 of original cartridge bytes |
| 72 | u64 | Total cumulative event-clock ticks, ending on a frame flush |
| 80 | 16 bytes | Reserved, all zero |

Each event occupies 8 bytes: u32 delta ticks, u8 operation, u8 value, u16 zero.
The first delta is relative to tick zero. Later deltas are relative to the
previous event, including frame events. Repeated timestamps preserve original
write order. Delta encoding is lossless; it does not quantize event times.

| Operation | Meaning |
| --- | --- |
| 0 | Source `Write_SoundChipLeft(value)` |
| 1 | Source `Write_SoundChipRight(value)` |
| 2 | Source `dac_write_left(value)` / original port A2 |
| 3 | Source `dac_write_right(value)` / original port A3 |
| 4 | Source `MDFNNGPCSOUND_SetEnable(value)`, value 0 or 1 |
| 5 | Original frontend sound-frame flush, value 0 |

The reference profile is libretro/beetle-ngp-libretro commit
`a50d5ac288a81f2104ddf43195a4efdd15c72227`, its original T6W28, 8-bit stereo DAC,
Blip and Stereo_Buffer code. Every source write uses the same local timestamp
as that source core: `ngpc_soundTS >> 1`. At each original frame boundary the
unmodified core flushes using that same half-clock value. Cumulative NGL time
adds those frame lengths. These are emulator instruction-scheduled timestamps,
not independently measured physical bus phases.

The decoder resets all frontend sound/filter objects, executes the full hidden
preroll, discards exactly the declared original PCM frames, and begins the
selected cue. This preserves original filter and oscillator history without
storing PCM. Events include all T6W28 writes, DAC writes and enable/reset
operations; none are simplified or silently replaced. Frame flushes preserve
the source resampling phase. Playback beyond the captured window returns zero.

Source output is signed 16-bit stereo at 44100 Hz, represented as Float32 by
division by 32768. Other output rates 8000..192000 Hz use deterministic rational
linear interpolation. Player 0.14 is required. Each file has one selected cue.
No artificial playback loop is declared; repeated passages inside the finite
capture are produced by the original driver.

Limits: at most one hour of captured events, at most 120 seconds of hidden
preroll, maximum 0.25 seconds between original frame boundaries, strict event
count/boundaries/opcodes/reserved bytes. The browser additionally bounds gzip
expansion at 40000096 bytes. Invalid files disable the previous stream.

Source DAC channel names follow NeoPop and its accompanying memory-map document.
The pinned MAME driver labels A2/A3 DAC sides in the opposite order. This
difference is recorded rather than presented as a physical stereo measurement.
The Card Fighters Capcom music bank has no DAC writes; the separate Card
Fighters 2 boot fixture checks both original DAC streams. Physical analog
mixing and a console capture have not been measured.

Research extraction invokes the game's original bank-selection, score-request
and queue-flush functions. The current original interrupt completes normally;
a documented host return frame then enters the original function. A two-byte
`JR T,-2` in unused BIOS workspace 0x6ef0 suspends the main game loop afterward.
Original IRQs, Z80, timers, music data and chip routines continue unchanged.
No original musical ROM or score bytes are patched. This control state and all
source function addresses are retained in per-cue proof files.

The player bridge is an altered distribution, not an upstream NeoPop frontend.
Upstream GPL-2.0-or-later notices and LGPL-2.1-or-later T6W28/Blip notices are
retained with full source, bridge, patch and build recipe.
