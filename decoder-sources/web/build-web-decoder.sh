#!/bin/sh
# Compile the existing GremlinAmp decoder. No independent chip implementation.
set -eu
SOURCE=${1:-app/src/main/cpp/game-music-emu}
DESTINATION=${2:-build/web-codec}
BUILD=${3:-build/web-codec-cmake}
mkdir -p "$DESTINATION"
emcmake cmake "$SOURCE" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release \
 -DGME_BUILD_SHARED=OFF -DGME_BUILD_STATIC=ON -DGME_BUILD_EXAMPLES=OFF \
 -DGME_BUILD_TESTING=OFF -DGME_YM2612_EMU=Nuked -DGME_ZLIB=OFF
cmake --build "$BUILD" -j4
em++ "$BUILD/gme/libgme.a" -O3 --no-entry \
 -sALLOW_MEMORY_GROWTH=1 -sINITIAL_MEMORY=33554432 -sMAXIMUM_MEMORY=134217728 \
 -sENVIRONMENT=worker,node -sFILESYSTEM=0 \
 '-sEXPORTED_FUNCTIONS=["_gme_open_data","_gme_delete","_gme_clear_playlist","_gme_ignore_silence","_gme_start_track","_gme_play","_gme_mute_voices","_gme_track_count","_gme_type_list","_gme_identify_extension","_gme_type_system","_malloc","_free"]' \
 '-sEXPORTED_RUNTIME_METHODS=["UTF8ToString","HEAPU8","HEAP16","HEAP32"]' \
 -o "$DESTINATION/libgme.js"
python3 - "$SOURCE" "$DESTINATION" <<'PY'
from pathlib import Path
import hashlib,json,subprocess,sys,zipfile
source=Path(sys.argv[1]);destination=Path(sys.argv[2])
with zipfile.ZipFile(destination/'source.zip','w',zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
 for path in sorted(source.rglob('*')):
  if path.is_file():archive.write(path,Path('game-music-emu')/path.relative_to(source))
provenance=dict(component='Game Music Emu',version='0.6.5',repository='https://github.com/GremlinEngineering/gremlinamp',commit=subprocess.run(['git','rev-parse','HEAD'],capture_output=True,text=True,check=True).stdout.strip(),codec_revision='7d0e0dd2019fef0ec691db3d17ab995c8d8e0572',source_modified=True,emscripten='6.0.13',ym2413_backend='emu2413; exact GremlinAmp 0.2.4 adapter',ym2612_backend='Nuked',license='LGPL-2.1-or-later; emu2413 and adapter MIT',source_archive='source.zip',build_script='build-web-decoder.sh',artifacts={name:hashlib.sha256((destination/name).read_bytes()).hexdigest() for name in ['libgme.js','libgme.wasm']})
(destination/'PROVENANCE.json').write_text(json.dumps(provenance,indent=2)+'\n')
for name in ['LICENSE.txt','LICENSE','license.txt']:
 if (source/name).is_file():(destination/'LGPL-2.1.txt').write_bytes((source/name).read_bytes());break
if (source/'gme/ext/LICENSE').is_file():(destination/'EMU2413_LICENSE.txt').write_bytes((source/'gme/ext/LICENSE').read_bytes())
PY
