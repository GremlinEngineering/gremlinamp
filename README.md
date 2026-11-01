# GremlinAmp

**English** · [Русский](README.ru.md)

**Game soundtracks from the Imperial School Arcade, on Android and offline.**

GremlinAmp is an Android companion to the [Imperial School Arcade music library](https://games.oopssre.ru/gremlin.html). Pick a game or series, play its soundtrack, or download a collection to your phone. The app uses the site's existing catalog for artwork, series membership and game information.

Its cassette Walkman-inspired appearance combines a retro feel with search, filters and background playback.

## Why use it?

The website already plays music in a browser. GremlinAmp brings that library to your phone with background playback, headset controls and downloaded collections you can listen to without an internet connection.

For example, find a favourite series, narrow its games down to a platform, download their music and listen on a trip. A duration filter can hide short sound effects.

## What makes it different?

GremlinAmp is organised around **the Arcade's games and series**:

- Each game is a music collection with its own poster; series bring together music from their games.
- Search, alphabet navigation, platform and year filters use the existing game catalog.
- NSF, SPC, VGM and other game music formats play without preparing MP3 copies. Individual compositions inside one file are treated as separate tracks.
- Download a game, a series or the whole library. Shared files are stored only once.
- Music stays connected to its game through titles, artwork and collection membership.

The app is intended primarily for the site's listeners and game music enthusiasts. Importing arbitrary folders from your phone is not currently supported.

## Install and start listening

Requires **Android 8.0+**. Supports ARM64, ARMv7 and x86_64. Current version: **0.2.5**. The app interface is currently **Russian**.

Application downloads are listed in the [site's media library](https://games.oopssre.ru/gremlin.html). You can also build the APK from source using the instructions below.

1. Install the APK. Android may ask you to allow installation from your browser or file manager.
2. Open «Игры» (Games) or «Серии» (Series), find a collection and select a track.
3. Download music through the collection menu. Open «Скачано» (Downloaded) to listen offline.

Playback includes a queue, shuffle, repeat, seeking, notification and headset controls. Tap an artwork thumbnail to open the poster with gesture zoom. Light, dark and system themes are available in settings. The resources and licenses screen includes a direct link to this GitHub repository.

Use «Скачать всё» (Download all) to download all music from the site regardless of your filters. The confirmation shows the size of missing files. Duration controls can hide short tracks and separately show or hide tracks whose duration is unknown.

## Downloads and updates

Collections show whether they need downloading, are partial, are downloaded or have updates. Existing music files are reused. If playback has already cached a complete collection, «Сохранить» (Save) adds it to your downloaded library.

In Settings → Music updates and network, enable automatic updates hourly or daily after 07:00 Moscow time. Android may defer background checks. Choose Wi-Fi only or Wi-Fi and mobile data; this also controls downloading uncached tracks for playback. Downloaded files remain playable offline.

Follow selected series or the entire site library to include new games with music. Manage these subscriptions in the same settings screen. Automatic updates are off and Wi-Fi only is selected by default.

## After a collection

The «После коллекции…» button offers Stop, Same series, Same genre and Similar games. Similarity uses series, genre and platform metadata from the site, rather than analysing audio. Continuation can be restricted to downloaded music. Games already played during the current queue session are excluded, and platform, year and duration filters still apply. Playback stops if no suitable game remains.

Shuffle, repeat track and repeat queue are in the same panel. Repeat takes precedence over starting another collection. Stop is the default.

## Supported formats

[Game Music Emu](https://github.com/libgme/game-music-emu) handles NSF, NSFE, GBS, HES, SPC, VGM/VGZ, AY and SAP. Standard audio uses Android's media playback; the site also contains MP3 and OGG. Other standard codecs depend on the Android device.

Game files may contain multiple compositions. Each composition appears as a separate track. Audio is decoded for playback without saving converted copies.

## Current limitations

- Looping game music has no single natural duration. If accelerated analysis finds no end within 180 seconds, the duration remains unknown.
- Duration is a practical filter, not a sound-effect classifier: it can also hide short musical jingles.
- There is no dedicated screen for deleting downloaded collections.
- After Android force-stops the app, start the download again. Completed and partial files are retained.
- Current APKs use a development signing key. Updates to an existing installation must retain the same signature.

## Build from source

Requirements: Java 17, Android SDK Platform 35, Build Tools 35.0.0, NDK 27.0.12077973, CMake 3.22.1, `zip` and `keytool`.

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export GREMLINAMP_STOREPASS=android
bash tools/build-sdk.sh
```

Output: `build/GremlinAmp-0.2.5.apk`. Unless another key is supplied, the script creates a local development key at `build/development.keystore`. Never commit signing keys.

For repeatable update signing, set `GREMLINAMP_KEYSTORE` to your private keystore and `GREMLINAMP_STOREPASS` to its password. The script uses the alias `gremlinamp`.

The SDK build script is the supported command-line build method. A Gradle project is included; a Gradle wrapper is not provided.

Run the state-model tests with `bash tools/test-state.sh`.

## Licenses and rights

Original application code is licensed under **[MIT](LICENSE)**, © 2026 Дмитрий Малыхин.

Game Music Emu and its components retain their own licenses, primarily **LGPL-2.1-or-later**. Corresponding source, license texts, JNI code and rebuild instructions are included. The application's MIT license does not relicense these dependencies. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Music, game names, posters and other site materials retain their respective rights and terms. They are not covered by this project's MIT license. The music library and game posters are not included in this repository.

## Project links

[Arcade](https://games.oopssre.ru/) · [Gremlin media library](https://games.oopssre.ru/gremlin.html) · [Laboratory](https://games.oopssre.ru/lab/) · [Imperial School Engineer](https://oopssre.ru/) · [Telegram](https://t.me/oopsSRE)


App updates: Settings → Обновление приложения. Optional automatic APK download (hourly or daily after 07:00 Moscow), Wi-Fi or mobile data. Installation requires Android confirmation. APK size, SHA-256, package, version and signing certificate are checked.

Audio continuation: “По ритму” and “По звучанию” use the site analysis index after the current queue finishes. Downloaded-only playback, filters and session history apply. The index is imported into a local database for offline use; a missing server index does not affect ordinary playback. Refresh it with the catalog or in queue settings.

Unavailable audio modes are disabled with a reason before selection. Repeat-one and repeat-queue are mutually exclusive; continuation is suspended while repeat is active. Index refresh shows progress and prevents duplicate requests.

VGM/VGZ YM2413 audio uses the bundled MIT-licensed emu2413 core through a local adapter. The decoder regression test generates its own YM2413-only VGM; run tools/test-ym2413.py against a host-built libgme shared library.
