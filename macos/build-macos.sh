#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
if [ "$(uname -s)" != Darwin ]; then
  echo 'Сборка приложения выполняется на macOS 12 или новее.' >&2
  exit 1
fi
if ! xcrun --find swiftc >/dev/null 2>&1; then
  echo 'Нужны Command Line Tools Apple. Выполните xcode-select --install и повторите сборку.' >&2
  exit 1
fi
task_arch="${GREMLINAMP_ARCH:-$(uname -m)}"
case "$task_arch" in
  universal) task_arches=(arm64 x86_64) ;;
  arm64|x86_64) task_arches=("$task_arch") ;;
  *) echo 'GREMLINAMP_ARCH: arm64, x86_64 или universal.' >&2; exit 1 ;;
esac
task_sdk="$(xcrun --sdk macosx --show-sdk-path)"
task_sdk_version="$(xcrun --sdk macosx --show-sdk-version)"
if [ "${task_sdk_version%%.*}" -lt 13 ]; then
  echo 'Нужны Command Line Tools 14 или новее (macOS SDK 13+ и Swift 5.7+) для настройки автозапуска.' >&2
  exit 1
fi
task_project="$PWD"
task_build="$PWD/build"
task_app="$task_build/GremlinAmp.app"
task_version="$(/usr/libexec/PlistBuddy -c "Print CFBundleShortVersionString" Resources/Info.plist)"
task_dmg="$task_build/GremlinAmp-$task_version-$task_arch.dmg"
mkdir -p "$task_build"
for task_cpu in "${task_arches[@]}"; do
  echo "Проверка Swift: $task_cpu"
  xcrun swiftc -module-cache-path "$task_build/module-cache" -swift-version 5 -typecheck -parse-as-library -sdk "$task_sdk" -target "$task_cpu-apple-macosx12.0" \
    -import-objc-header Native/GremlinAudio.h Sources/*.swift
  echo "Сборка нативных декодеров: $task_cpu"
  bash tools/build-decoders.sh "$task_cpu"
  echo "Сборка звука и приложения: $task_cpu"
  task_objects="$task_build/objects-$task_cpu"
  mkdir -p "$task_objects"
  task_gme="$task_project/Vendor/game-music-emu/gme"
  task_common=(-arch "$task_cpu" -isysroot "$task_sdk" -mmacosx-version-min=12.0 -O2 -fPIC -DNDEBUG -DBLARGG_LITTLE_ENDIAN=1 -DHAVE_ZLIB_H -DVGM_YM2612_NUKED -I "$task_gme")
  task_objs=()
  for task_source in "$task_gme"/*.cpp; do
    case "$task_source" in *Ym2612_GENS.cpp|*Ym2612_MAME.cpp) continue ;; esac
    task_obj="$task_objects/$(basename "${task_source%.cpp}").o"
    xcrun clang++ -std=c++17 "${task_common[@]}" -c "$task_source" -o "$task_obj"
    task_objs+=("$task_obj")
  done
  xcrun clang -std=c99 "${task_common[@]}" -c "$task_gme/ext/emu2413.c" -o "$task_objects/emu2413.o"
  xcrun clang -std=c99 "${task_common[@]}" -c Vendor/stb_vorbis.c -o "$task_objects/stb_vorbis.o"
  xcrun clang++ -std=c++17 "${task_common[@]}" -c Native/GremlinAudio.cpp -o "$task_objects/GremlinAudio.o"
  xcrun clang++ -std=c++17 "${task_common[@]}" -c Native/TrackerAudio.cpp -o "$task_objects/TrackerAudio.o"
  xcrun clang++ -std=c++17 "${task_common[@]}" -c Native/DecoderPort/Decoder.cpp -o "$task_objects/Decoder.o"
  xcrun clang++ "${task_common[@]}" -dynamiclib "${task_objs[@]}" "$task_objects/emu2413.o" "$task_objects/stb_vorbis.o" "$task_objects/GremlinAudio.o" "$task_objects/Decoder.o" "$task_objects/TrackerAudio.o" -lz -framework Accelerate -install_name @rpath/libGremlinAudio.dylib -o "$task_objects/libGremlinAudio.dylib"
  xcrun swiftc -module-cache-path "$task_build/module-cache" -swift-version 5 -O -parse-as-library -sdk "$task_sdk" -target "$task_cpu-apple-macosx12.0" \
    -import-objc-header Native/GremlinAudio.h Sources/*.swift \
    -L "$task_objects" -lGremlinAudio -Xlinker -rpath -Xlinker @executable_path/../Frameworks \
    -framework SwiftUI -framework AppKit -framework AVFoundation -framework MediaPlayer -framework CryptoKit -framework ServiceManagement \
    -o "$task_objects/GremlinAmp"
  for task_decoder in "$task_build/decoder-$task_cpu/decoders/"*.dylib; do
    cp -L "$task_decoder" "$task_objects/"
  done
  if [ "$task_cpu" = "$(uname -m)" ]; then
    echo "Проверка декодера YM2413: $task_cpu"
    xcrun clang++ -std=c++17 -g -arch "$task_cpu" -isysroot "$task_sdk" -mmacosx-version-min=12.0 \
      tests/YM2413Tests.cpp -L "$task_objects" -lGremlinAudio -Xlinker -rpath -Xlinker "$task_objects" \
      -o "$task_objects/ym2413-tests"
    "$task_objects/ym2413-tests" tests/ym2413-only.vgm tests/ym2413-only.vgz
    echo "Проверка коллекций и офлайн-подбора: $task_cpu"
    xcrun swiftc -module-cache-path "$task_build/module-cache" -swift-version 5 -parse-as-library -sdk "$task_sdk" -target "$task_cpu-apple-macosx12.0" \
      -import-objc-header Native/GremlinAudio.h Sources/Models.swift Sources/FeatureRules.swift Sources/SiteHTTP.swift Sources/LibraryStore.swift tests/LibraryTests.swift \
      -L "$task_objects" -lGremlinAudio -Xlinker -rpath -Xlinker "$task_objects" \
      -framework CryptoKit -o "$task_objects/library-tests"
    "$task_objects/library-tests"
    echo "Проверка аудиовывода AVAudioEngine: $task_cpu"
    xcrun swiftc -module-cache-path "$task_build/module-cache" -swift-version 5 -parse-as-library -sdk "$task_sdk" -target "$task_cpu-apple-macosx12.0" \
      -import-objc-header Native/GremlinAudio.h Sources/Models.swift Sources/DecoderInputs.swift Sources/NativeAudio.swift tests/AudioOutputTests.swift \
      -L "$task_objects" -lGremlinAudio -Xlinker -rpath -Xlinker "$task_objects" \
      -framework AVFoundation -o "$task_objects/audio-output-tests"
    "$task_objects/audio-output-tests"
    echo "Проверка девяти трекеров и данных сонификации: $task_cpu"
    xcrun swiftc -module-cache-path "$task_build/module-cache" -swift-version 5 -O -parse-as-library \
      -sdk "$task_sdk" -target "$task_cpu-apple-macosx12.0" -import-objc-header Native/GremlinAudio.h \
      Sources/Models.swift Sources/SiteHTTP.swift Sources/DecoderInputs.swift Sources/NativeAudio.swift Sources/TrackerAudio.swift Sources/TrackerData.swift tests/TrackerTests.swift \
      -L "$task_objects" -lGremlinAudio -Xlinker -rpath -Xlinker "$task_objects" \
      -framework AVFoundation -o "$task_objects/tracker-tests"
    "$task_objects/tracker-tests"
    echo "Проверка аранжировок в очереди: $task_cpu"
    xcrun swiftc -module-cache-path "$task_build/module-cache" -swift-version 5 -O -parse-as-library \
      -sdk "$task_sdk" -target "$task_cpu-apple-macosx12.0" -import-objc-header Native/GremlinAudio.h \
      Sources/{Models,FeatureRules,SiteHTTP,LibraryStore,DecoderInputs,NativeAudio,TrackerAudio,TrackerData,TrackerSession,TerminalLevels,QueueArrangement,Playback}.swift tests/QueuePlaybackTests.swift \
      -L "$task_objects" -lGremlinAudio -Xlinker -rpath -Xlinker "$task_objects" \
      -framework AVFoundation -framework AppKit -framework MediaPlayer -framework Accelerate -framework CryptoKit \
      -o "$task_objects/queue-playback-tests"
    "$task_objects/queue-playback-tests" -shortSeconds 0 -unknownDuration show
  fi
done
mkdir -p "$task_app/Contents/MacOS" "$task_app/Contents/Frameworks" "$task_app/Contents/Resources"
if [ "${#task_arches[@]}" -eq 2 ]; then
  xcrun lipo -create "$task_build/objects-arm64/GremlinAmp" "$task_build/objects-x86_64/GremlinAmp" -output "$task_app/Contents/MacOS/GremlinAmp"
  xcrun lipo -create "$task_build/objects-arm64/libGremlinAudio.dylib" "$task_build/objects-x86_64/libGremlinAudio.dylib" -output "$task_app/Contents/Frameworks/libGremlinAudio.dylib"
else
  cp "$task_objects/GremlinAmp" "$task_app/Contents/MacOS/GremlinAmp"
  cp "$task_objects/libGremlinAudio.dylib" "$task_app/Contents/Frameworks/libGremlinAudio.dylib"
fi
for task_decoder in "$task_objects"/libgremlinamp*.dylib "$task_objects"/liblazygsf*.dylib; do
  task_name="$(basename "$task_decoder")"
  task_output="$task_app/Contents/Frameworks/$task_name"
  if [ "${#task_arches[@]}" -eq 2 ]; then
    xcrun lipo -create "$task_build/objects-arm64/$task_name" "$task_build/objects-x86_64/$task_name" -output "$task_output"
  else
    cp "$task_decoder" "$task_output"
  fi
done
cp Resources/Info.plist "$task_app/Contents/Info.plist"
cp Resources/*.txt Resources/AppIcon.icns "$task_app/Contents/Resources/"
cp LICENSE "$task_app/Contents/Resources/MIT.txt"
ditto Resources/Trackers "$task_app/Contents/Resources/Trackers"
for task_preset in personal-radio.json personal-radio.sha256; do
  if [ -f "Resources/$task_preset" ]; then
    cp "Resources/$task_preset" "$task_app/Contents/Resources/"
  else
    rm -f "$task_app/Contents/Resources/$task_preset"
  fi
done
for task_library in "$task_app/Contents/Frameworks/"*.dylib; do
  codesign --force --sign - "$task_library"
done
codesign --force --sign - "$task_app"
codesign --verify --deep --strict "$task_app"
echo 'Проверка структуры приложения'
plutil -lint "$task_app/Contents/Info.plist"
xcrun swiftc -module-cache-path "$task_build/module-cache" -swift-version 5 -parse-as-library Sources/Models.swift Sources/FeatureRules.swift tests/RulesTests.swift -o "$task_build/rules-tests"
"$task_build/rules-tests"
task_stage="$(mktemp -d "$task_build/dmg-stage.XXXXXX")"
trap 'rm -rf "$task_stage"' EXIT
ditto "$task_app" "$task_stage/GremlinAmp.app"
ln -s /Applications "$task_stage/Applications"
hdiutil create -volname 'GremlinAmp' -srcfolder "$task_stage" -format UDZO -ov "$task_dmg"
xcrun swiftc -module-cache-path "$task_build/module-cache" -swift-version 5 -parse-as-library Sources/Models.swift Sources/FeatureRules.swift tools/MakeRelease.swift -framework CryptoKit -o "$task_build/make-release"
"$task_build/make-release" "$task_dmg" "$task_arch" Resources/Info.plist "$task_build/macos-release.json"
echo "Данные релиза: $task_build/macos-release.json"
echo "Готово: $task_app"
echo "DMG: $task_dmg"
if [ "${GREMLINAMP_REVEAL:-1}" = 1 ]; then open -R "$task_dmg"; fi
