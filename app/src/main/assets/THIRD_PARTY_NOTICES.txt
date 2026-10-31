# Scope of the MIT license

The root LICENSE covers the original player application code (Java UI/services, JNI bridge, and application build scripts) by Dmitry Malykhin.

## Game Music Emu

The unmodified third-party code under `app/src/main/cpp/game-music-emu` retains its own notices and licenses, principally LGPL-2.1-or-later. Its upstream license texts and per-file notices are authoritative. The root MIT license does not relicense it. Corresponding sources, provenance, JNI bridge and reproducible build script are distributed in the source archive, allowing a modified library to be rebuilt and relinked. Use a personal signing key when installing rebuilt Android packages.

The inherited PROVENANCE.json records the upstream source version and the website's original WASM build. The Android app uses those corresponding upstream sources, but builds native Android binaries via tools/build-sdk.sh; the WASM artifact hashes do not describe the APK.

## Site material and audio

Music, game trademarks, game and series posters, and other fetched site materials are not relicensed under MIT. Their respective rightsholders and the site's material-specific terms apply. The application source archive does not contain the downloaded music library or game posters.
