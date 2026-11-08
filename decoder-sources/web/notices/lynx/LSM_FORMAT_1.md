# Lynx Sound Module, version 1

LSM is a transparent collection container introduced for these original Lynx sound-driver extractions. It is not an established console format. LSZ is exactly gzip(LSM). It contains an initialized original music-driver machine state, not rendered PCM, MP3, MIDI, reconstructed notes, or a complete game cartridge.

The player executes the original 65C02 code with the original saved Mikey timers, registers, RAM, IRQ state and 16 MHz system clock, using Handy/libretro commit `bc55d462f0b2d6b073ea93dc552ebd73cec60fd1`. The source title's music routines, score bytes and instruments are unchanged. The research host executes original stop/play entry points, then replaces three bytes of no-longer-used boot scratch RAM at `$0200` with `JMP $0200` and sets the main PC there. Original IRQ-driven music continues. Ordinary game logic and input are absent from this playback loop.

All multibyte fields are unsigned little-endian integers. Header is 96 bytes.

|Offset|Field|
|---|---|
|0|`LSM` followed by byte `1A`|
|4|Version 1, u32|
|8|System master clock, 16000000|
|12|Pinned machine-state ABI, 1|
|16|LSS3 body byte length, currently 134422|
|20|Body offset, 96|
|24|Original source cartridge CRC32|
|28|Original source cartridge bytes, 131072 or 262144|
|32|Main idle address, 512|
|36|Flags, 1 = cartridge-independent driver state|
|40–71|Original source cartridge SHA256, 32 raw bytes|
|72–95|Reserved, zero|

The body is the pinned Handy `LSS3` state serialization. It contains 64 KB machine RAM, saved registers/timers/device state and the core's empty shadow SRAM. Cartridge ROM bytes and BIOS ROM bytes are not in the body. The original cartridge checksum remains in the file's LSS3 header. For restoration into a blank cartridge host, the decoder normalizes only that checksum field in a private in-memory copy. The file, original source CRC/SHA and all music data remain unchanged.

Every accepted extraction is tested for zero cartridge reads over its full verification window. The player counts reads and fails if a driver requests any cartridge data. It never substitutes zeros for a missing music dependency. The fixed state ABI, source identity fields, RAM mask, CPU idle PC, idle instruction, header boundaries and flags are checked before playback.

Original stop-all-voices and score-start routines run with IRQs masked during initialization. This clears prior scene effects while keeping original instruments, sequences and the selected music timer period. IRQs resume in the idle host. Game-state changes used to reach a cue, such as setting Gauntlet player health to trigger the original Game Over path, are recorded separately from music data.

Handy generates stereo 16-bit PCM at 48000 Hz with its original Mikey/Blip path. The bridge exposes those samples as Float32 divided by 32768. Other output rates use deterministic rational linear interpolation of that original 48 kHz stream. This is an emulated software signal path, including Handy's filter model; physical board/analog measurements are not claimed. The bridge resets the core's otherwise-static filter delta when a new module loads. That makes every module start with the same history as a fresh process.

Reference checks use unmodified stock Handy with the original cartridge, restoring the identical isolated state in a fresh process. Native LSM playback uses a blank cartridge. Full-window PCM comparison, native/browser comparison at 48/44.1 kHz, input validation, reload and actual MusicEngine controls are archived with the release. Matching software PCM is not an independent physical console or separate full-MAME comparison.

The format declares no inferred loop. The original driver performs its own repetition or natural stop; music.md supplies a presentation window and optional ending fade. The asset has no authored/composed marker and no FUR surrogate.
