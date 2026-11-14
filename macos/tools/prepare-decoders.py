#!/usr/bin/env python3
"""Prepare the shared, pinned C/C++ decoder sources for the macOS build."""
from pathlib import Path
import re
import sys

project = Path(__file__).resolve().parent.parent
# GitHub keeps macOS beside the Android modules in the same checkout.
android = project.parent
if not (android / 'app/src/main/cpp/CMakeLists.txt').is_file():
    android = android / 'GremlinAmp'
source = android / 'app/src/main/cpp'
out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)

def write(name, data):
    path = out / name
    raw = data.encode() if isinstance(data, str) else data
    if not path.exists() or path.read_bytes() != raw:
        path.write_bytes(raw)

write("atari_native.c", (source / "atari_native.c").read_bytes())

for path in sorted(source.glob('*_bridge.cpp')):
    content = path.read_text().replace('Java_ru_oopssre_gremlinamp_', 'gd_')
    content = content.replace('("libgremlinamp-" + name + ".so").c_str()', '("@loader_path/libgremlinamp-" + name + ".dylib").c_str()')
    write(path.name, content)
write('psf_files.h', (source / 'psf_files.h').read_bytes())

for name in ['CMakeLists.txt', 'native-boards.cmake', 'native-machines.cmake', 'native-streams.cmake']:
    content = (source / name).read_text()
    content = re.sub(r'^target_link_options\(.*\)\n|^\s+target_link_options\(.*\)\n', '', content, flags=re.M)
    content = content.replace('get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)',
                              f'set(REPO_ROOT [=[{android}]=])\ninclude_directories(BEFORE [=[{project / "Native/DecoderPort"}]=])\n'
                              'set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/decoders")\n'
                              'set(CMAKE_BUILD_WITH_INSTALL_RPATH ON)\nset(CMAKE_INSTALL_RPATH "@loader_path")')
    content = content.replace('add_subdirectory(game-music-emu)\n', '').replace('SHARED bridge.cpp vgm_bridge.cpp', 'SHARED vgm_bridge.cpp')
    content = content.replace('gme_static vgm-player vgm-emu vgm-utils log z dl', 'vgm-player vgm-emu vgm-utils z')
    content = content.replace('${REPO_ROOT}/tools/build-native-machine.py', '${CMAKE_CURRENT_SOURCE_DIR}/build-native-machine.py')
    content = content.replace('/machine-libs/libgremlinamp-${name}.so', '/decoders/libgremlinamp-${name}.dylib')
    content = content.replace('add_custom_target(build-machine-${name} DEPENDS', 'add_custom_target(build-machine-${name} ALL DEPENDS')
    content = re.sub(r'^\s*target_link_libraries\(gremlinamp PRIVATE (gremlinamp-\$\{name\}|mgba_libretro)\)\n', '\n', content, flags=re.M)
    content = content.replace('target_compile_features(gremlinamp-openmpt PRIVATE cxx_std_23)',
                              'target_compile_options(gremlinamp-openmpt PRIVATE -std=c++23)')
    if name == 'CMakeLists.txt':
        content += '''
foreach(frontend gremlinamp gremlinamp-psf gremlinamp-gsf gremlinamp-streams gremlinamp-gme-plus)
    target_compile_features(${frontend} PRIVATE cxx_std_17)
endforeach()
target_link_libraries(gremlinamp-psf PRIVATE iconv)

target_compile_definitions(lazygsf PRIVATE HAVE_STRLCPY=1 HAVE_STRLCAT=1)
target_compile_options(lazygsf PRIVATE -include xlocale.h)
'''
    content += '\n' if content.endswith('\n') else '\n\n'
    if name == 'native-machines.cmake':
        content += 'add_custom_target(stage-gba ALL DEPENDS mgba_libretro)\n'
    write(name, content)

machine = (android / 'tools/build-native-machine.py').read_text()
machine = re.sub(r"\[\*cxx, '-shared'.*?'\-llog',", "[*cxx, '-dynamiclib', f'-Wl,-install_name,@rpath/{args.output.name}', *objects, str(archive), '-lz', '-lm',", machine, flags=re.S)
machine = machine.replace('import os', 'import os\nimport subprocess as sdk_tool')
machine = machine.replace("'-fPIC', '-fwrapv'", "'-isysroot', sdk_tool.check_output(['xcrun', '--sdk', 'macosx', '--show-sdk-path'], text=True).strip(), '-fPIC', '-fwrapv'")
write('build-native-machine.py', machine)
