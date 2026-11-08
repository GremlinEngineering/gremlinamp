K0 Konami K005289 original PROM/counter renderer0.39

Prepared k0-source is self-contained. On macOS with Python3, clang++ and Emscripten6.0.13:
GREMLIN_EMSDK=/absolute/path/to/emsdk python3 build_k0.py

Playback combines two original AY8910 register streams, rendered by the separately supplied unchanged libvgm runtime, with original MAME K005289 counter/volume methods and the original512byte waveform PROM. All six K control actions and their order come from the original sound program. The Z80 executes during source capture; playback runs the captured events rather than the CPU.

The K core runs at3,579,545 clock ticks per second. Its original clock samples are averaged by the host to48kHz; the final output rate uses linear interpolation. This resampler is documented explicitly and is not the MAME sound-stream resampler or a physical PCB measurement.

The selected original source keeps the optional AY capacitor filters off. AY volumes use the nearest VGM8.8 values for the documented MAME route gains0.20 and1.0; K gain is0.35.

Original upstream source and exact extracted-method checksums are included. Research/capsule scripts document the project layout and are not needed to compile the prepared core. The runtime also requires the separately delivered libvgm JS/Wasm and its existing source archive/licenses.
