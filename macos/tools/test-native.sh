#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/native-tests
task_gme="$PWD/Vendor/game-music-emu/gme"
task_cxx="${CXX:-c++}"
task_cc="${CC:-cc}"
task_flags=(-O1 -g -fPIC -DNDEBUG -DBLARGG_LITTLE_ENDIAN=1 -DHAVE_ZLIB_H -DVGM_YM2612_NUKED -I "$task_gme")
task_sanitizer=()
if [ "${GREMLINAMP_ASAN:-0}" = 1 ]; then
  task_sanitizer=(-fsanitize=address -fno-omit-frame-pointer)
  task_flags+=("${task_sanitizer[@]}")
fi
task_objs=()
for task_source in "$task_gme"/*.cpp; do
  case "$task_source" in *Ym2612_GENS.cpp|*Ym2612_MAME.cpp) continue ;; esac
  task_obj="build/native-tests/$(basename "${task_source%.cpp}").o"
  "$task_cxx" -std=c++17 "${task_flags[@]}" -c "$task_source" -o "$task_obj"
  task_objs+=("$task_obj")
done
"$task_cc" -std=c99 "${task_flags[@]}" -c "$task_gme/ext/emu2413.c" -o build/native-tests/emu2413.o
"$task_cc" -std=c99 "${task_flags[@]}" -c Vendor/stb_vorbis.c -o build/native-tests/stb_vorbis.o
"$task_cxx" -std=c++17 "${task_flags[@]}" -c Native/GremlinAudio.cpp -o build/native-tests/GremlinAudio.o
"$task_cxx" -std=c++17 "${task_flags[@]}" -c Native/DecoderPort/Decoder.cpp -o build/native-tests/Decoder.o

"$task_cxx" -std=c++17 -g "${task_sanitizer[@]}" tests/NativeTests.cpp "${task_objs[@]}" build/native-tests/emu2413.o build/native-tests/stb_vorbis.o build/native-tests/GremlinAudio.o build/native-tests/Decoder.o -lz -pthread -o build/native-tests/check
build/native-tests/check tests/tone.nsf tests/tone.ogg tests/tone.vgz
"$task_cxx" -std=c++17 -g "${task_sanitizer[@]}" tests/YM2413Tests.cpp "${task_objs[@]}" build/native-tests/emu2413.o build/native-tests/stb_vorbis.o build/native-tests/GremlinAudio.o build/native-tests/Decoder.o -lz -pthread -o build/native-tests/check-ym2413
build/native-tests/check-ym2413 tests/ym2413-only.vgm tests/ym2413-only.vgz
if command -v swiftc >/dev/null 2>&1; then
  swiftc -swift-version 5 -parse-as-library Sources/Models.swift tests/RulesTests.swift -o build/native-tests/rules
  build/native-tests/rules
fi
