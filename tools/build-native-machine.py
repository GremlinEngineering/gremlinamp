#!/usr/bin/env python3
"""Build the pinned libretro sources and original music frontend with Android Clang."""
import argparse
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import zipfile


def unpack(archive, destination):
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            if entry.is_dir():
                continue
            path = Path(entry.filename)
            if path.is_absolute() or '..' in path.parts:
                raise ValueError(entry.filename)
            target = destination.joinpath(*path.parts[1:])
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read(entry))


def run(args, directory):
    subprocess.run(args, cwd=directory, check=True)


def build(args, work):
    core = work / 'core'
    source, name = args.source, args.name
    makefile = 'Makefile'
    subdir = ''
    flags = []
    definitions = []
    compat = []
    common = None
    frontend = name
    if name == 'jag':
        shutil.copytree(source / 'emulator', core)
        for script in (core / 'scripts').glob('*.sh'):
            script.chmod(0o755)
        host = source / 'source/jaguar_host.c'
        flags = ['TEST_EXPORTS=0']
        common = core / 'libretro-common'
    elif name == 'md':
        for archive, sub in [
            ('picodrive', ''), ('libpicofe', 'platform/libpicofe'),
            ('emu2413', 'pico/sound/emu2413'), ('dr-libs', 'platform/common/dr_libs'),
        ]:
            unpack(source / f'archives/099-{archive}-source.zip', core / sub)
        flags = ['NO_ARM_ASM=1', 'use_sh2drc=0', 'use_svpdrc=0', 'use_libchdr=0']
        definitions = ['-D__LIBRETRO__', '-DUSE_LIBRETRO_VFS']
        makefile = 'Makefile.libretro'
        common = core / 'platform/libretro/libretro-common'
        compat = [
            'formats/png/rpng.c', 'streams/trans_stream.c', 'streams/trans_stream_pipe.c',
            'streams/trans_stream_zlib.c', 'file/file_path_io.c', 'file/file_path.c',
            'vfs/vfs_implementation.c', 'time/rtime.c', 'string/stdstring.c',
            'encodings/encoding_utf.c', 'compat/compat_strl.c',
            'compat/compat_posix_string.c', 'compat/fopen_utf8.c',
            'streams/file_stream.c', 'streams/file_stream_transforms.c', 'memmap/memmap.c',
        ]
        host = source / 'md_host.c'
    elif name in ('nf', 'a5'):
        version = '120' if name == 'nf' else '121'
        upstream = 'fceumm' if name == 'nf' else 'a5200'
        source = source / f'work-{version}'
        unpack(source / f'sources/{upstream}-source.zip', core)
        host = source / ('nes_host.c' if name == 'nf' else 'a5_host.c')
        definitions = ['-D__LIBRETRO__']
        if name == 'nf':
            common = core / 'src/drivers/libretro/libretro-common'
            compat = [
                'compat/compat_posix_string.c', 'compat/compat_snprintf.c',
                'compat/compat_strcasestr.c', 'compat/compat_strl.c', 'compat/fopen_utf8.c',
                'encodings/encoding_utf.c', 'file/file_path.c', 'file/file_path_io.c',
                'streams/file_stream.c', 'streams/file_stream_transforms.c',
                'string/stdstring.c', 'time/rtime.c', 'vfs/vfs_implementation.c',
            ]
        else:
            flags = ['HAVE_CDROM=0']
            common = core / 'libretro/libretro-common'
    else:
        unpack(source / 'original-sources.zip', core)
        if name == 'gb':
            makefile = 'Makefile.libretro'
            flags = ['HAVE_NETWORK=0']
            common = core / 'libgambatte/libretro-common'
            definitions = ['-D__LIBRETRO__', '-DVIDEO_RGB565', '-DCC_RESAMPLER_NO_HIGHPASS']
        elif name == 'iv':
            common = core / 'src/deps/libretro-common'
            definitions = ['-D__LIBRETRO__']
        elif name == 'sv':
            subdir = 'platform/libretro'
        elif name == 'vx':
            makefile = 'Makefile.libretro'
        elif name == 'ws-headroom':
            run(['patch', '-p1', '-i', str(args.headroom_patch)], core)
            frontend = 'ws'
        if frontend == 'ws':
            common = core / 'libretro-common'
            compat = ['compat/compat_strl.c']
        host = source / f'{frontend}_host.c'

    if common and not compat:
        compat = re.findall(r'\$\(LIBRETRO_COMM_DIR\)/([^\\\s]+\.c)', (core / 'Makefile.common').read_text())
    if not common:
        common = core / 'libretro-common'
    include_paths = [core, core / 'src', core / subdir, common / 'include']
    if name == 'nf':
        include_paths.append(core / 'src/drivers/libretro')
    if name == 'a5':
        include_paths.append(core / 'libretro')
    if name in ('iv', 'nf', 'a5'):
        frontend_text = host.read_text()
        prefix = 'nes' if name == 'nf' else name
        declaration = (
            '#include <limits.h>\n'
            'static char system_directory[PATH_MAX] = ".";\n'
            'static char cartridge_path[PATH_MAX];\n'
            f'API void gremlin_{prefix}_system_directory(const char *dir) {{\n'
            '    snprintf(system_directory, sizeof(system_directory), "%s", dir);\n'
            '    snprintf(cartridge_path, sizeof(cartridge_path), "%s/'
            + ('5200.rom' if name == 'a5' else 'original.bin') + '", dir);\n'
            '}'
        )
        frontend_text = frontend_text.replace('#define API __attribute__((visibility("default")))',
                                               '#define API __attribute__((visibility("default")))\n' + declaration)
        frontend_text = frontend_text.replace('*(const char**)data="."', '*(const char**)data=system_directory')
        if name == 'iv':
            start = frontend_text.index(' uint32_t cart_bytes=bytes-10240;')
            end = frontend_text.index(' original_rom=(uint8_t*)malloc', start)
            frontend_text = (frontend_text[:start] + ' uint32_t cart_bytes=bytes-10240;\n' + frontend_text[end:])
            frontend_text = frontend_text.replace('="original.bin"', '=cartridge_path').replace('game_ext.dir="."', 'game_ext.dir=system_directory')
        elif name == 'a5':
            frontend_text = frontend_text.replace('fopen("5200.rom","rb")', 'fopen(cartridge_path,"rb")')
        host = work / host.name
        host.write_text(frontend_text)
    flags += ['GIT_VERSION=', 'STATIC_LINKING=1']
    cc = [str(args.cc), '--target=' + args.target, '-fPIC', '-fwrapv', '-ffp-contract=off']
    if name == 'a5':
        cc += ['-Drand=gremlin_a5_rand', '-fsigned-char']
    cxx = [str(args.cxx), *cc[1:]]
    archive = work / 'core.a'
    # Upstream portable make rules select the C interpreter and omit platform
    # assembly. The compilers and archiver are exclusively from the Android NDK.
    run(['make', '-j2', '-f', makefile, 'platform=emscripten',
         f'TARGET={archive}', f'CC={shlex.join(cc)}', f'CXX={shlex.join(cxx)}',
         f'AR={shlex.quote(str(args.ar))}', *flags], core / subdir)
    objects = []
    for i, file in enumerate([host, *(common / p for p in compat)]):
        obj = work / f'frontend-{i}.o'
        run([*cc, '-O2', '-std=gnu11', '-DNDEBUG', *definitions,
             *(f'-I{p}' for p in include_paths), '-c', str(file), '-o', str(obj)], work)
        objects.append(str(obj))
    tmp_library = work / args.output.name
    run([*cxx, '-shared', '-static-libstdc++', '-Wl,--no-undefined',
         '-Wl,-z,max-page-size=16384', '-Wl,-Bsymbolic', '-Wl,--exclude-libs,ALL',
         f'-Wl,-soname,{args.output.name}', *objects, str(archive),
         '-lz', '-lm', '-llog', '-o', str(tmp_library)], work)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(tmp_library, args.output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for option in ('source', 'output', 'cc', 'cxx', 'ar', 'headroom-patch'):
        parser.add_argument('--' + option, type=Path, required=True)
    parser.add_argument('--name', required=True)
    parser.add_argument('--target', required=True)
    options = parser.parse_args()
    # Upstream makefiles concatenate unquoted source names.
    with tempfile.TemporaryDirectory(prefix='gremlinamp-ndk-', dir='/tmp') as directory:
        os.environ['GIT_CEILING_DIRECTORIES'] = directory
        build(options, Path(directory))
