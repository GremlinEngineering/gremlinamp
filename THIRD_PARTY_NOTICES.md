# Scope of the MIT license

The root LICENSE covers the original player application code (Java UI/services, JNI bridge, and application build scripts) by Dmitry Malykhin.

## Game Music Emu

The third-party code under `app/src/main/cpp/game-music-emu` retains its own notices and licenses, principally LGPL-2.1-or-later. Its upstream license texts and per-file notices are authoritative. The root MIT license does not relicense it. Corresponding sources, provenance, JNI bridge and reproducible build script are included in this repository, allowing a modified library to be rebuilt and relinked. Use a personal signing key when installing rebuilt Android packages.

The inherited PROVENANCE.json records the upstream source version and the website's original WASM build. The Android app uses those corresponding upstream sources, but builds native Android binaries via tools/build-sdk.sh; the WASM artifact hashes do not describe the APK.

## Site material and audio

Music, game trademarks, game and series posters, and other fetched site materials are not relicensed under MIT. Their respective rightsholders and the site's material-specific terms apply. This repository does not contain the downloaded music library or game posters.

The local YM2413 adapter (gme/Ym2413_Emu.cpp and its header) replaces the disabled stub and uses bundled emu2413 by Mitsutaka Okazaki and contributors under MIT (gme/ext/LICENSE). The GME CMake source list ensures this core is also included for VGM-only builds. These changes do not relicense the remaining GME library.


## Native music decoders

The Android NDK builds the decoder components from their corresponding source archives. Each archive includes the upstream license and notices. See [Decoder sources for GremlinAmp Android](decoder-sources/README.md) for the archives, source versions and inventory of the earlier phone and TV releases. The MIT license covers the GremlinAmp adapter and application code.

## Internet radio playback

AndroidX Media3 1.8.1 (ExoPlayer, HLS and the OkHttp data source), Square OkHttp and Okio, and the bundled AndroidX, Guava and Kotlin runtime libraries retain their Apache 2.0 licenses. The license text is in `app/src/main/assets/THIRD_PARTY_NOTICES.txt`. Radio streams come directly from their stations and retain their original terms.

### Cosmic sonification profiles

The Android generator packages spectral features and one excerpt of the Voyager 1 PWS audification.
Original Voyager 1 PWS audio is courtesy of NASA/JPL-Caltech and the University of Iowa
([source](https://space.physics.uiowa.edu/plasma-wave/voyager/v1pws_interstellar_epo.html), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)).
Kepler KIC 12268220 light curve and Voyager's Jupiter lightning audio come from [NASA Historical Sounds](https://www.nasa.gov/historical-sounds/).
Cassini RPWS Saturn data from April 26, 2017 are courtesy of NASA and the University of Iowa
([source](https://space.physics.uiowa.edu/space-audio/cassini/rev271rpx/), [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)).
The recordings were transformed into dominant-band, energy and centroid values by `tools/space-features.py`.
Voyager excerpt: 0.5–12.0 s of the published animation soundtrack, converted to 16 kHz mono PCM. Playback adds fades and mixes it with the tracker.
On a clean checkout the phone build prepares the excerpt from the university's original video using
`tools/prepare-voyager-audio.sh`. The script requires curl, Python 3 and FFmpeg.

The Orbits generator and its bundled fallback use [NASA/JPL Horizons](https://ssd-api.jpl.nasa.gov/doc/horizons.html)
geocentric lunar vectors and [NOAA SWPC solar-wind](https://www.swpc.noaa.gov/products/solar-wind)
and [GOES X-ray and flare data](https://www.swpc.noaa.gov/products/goes-x-ray-flux). The built-in data were retrieved on November 13, 2026 UTC.

The Moscow generator's mapping of movement and processed street audio is inspired by
[Sonic City by Lalya Gaye, Ramia Mazé and Lars Erik Holmquist](https://nime.org/proc/nime2003_gaye/).
