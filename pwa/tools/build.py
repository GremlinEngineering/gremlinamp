#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import zlib
from zipfile import ZIP_DEFLATED, ZipFile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parent
MUSIC = Path(os.environ.get('GREMLINAMP_OBSIDIAN', WORKSPACE / 'GremlinAmp_Obsidian'))
TRACKER = ROOT / 'assets/gremlinamp'
if not TRACKER.is_dir():
    TRACKER = WORKSPACE / 'GremlinAmp_Mac'
ORBITA = ROOT / 'assets/orbita'
if not ORBITA.is_dir():
    ORBITA = WORKSPACE / 'OrbitalStationAndroid/app/src/main/assets'
ORBITA_STYLE = ORBITA / 'orbita.css'
if not ORBITA_STYLE.is_file():
    ORBITA_STYLE = WORKSPACE / 'publisher-integration/gremlin-tg-publisher/games/orbita_assets/orbita.css'


def tracker(output):
    sdk = WORKSPACE / 'gremlin-music/work-040/references/emsdk'
    env = os.environ.copy()
    emxx = env.get('EMXX') or shutil.which('em++')
    if emxx:
        command = [emxx]
    elif (sdk / 'install/emscripten/em++.py').is_file():
        env.setdefault('EM_CONFIG', str(sdk / '.emscripten'))
        command = ['python3', str(sdk / 'install/emscripten/em++.py')]
    else:
        raise SystemExit('Для генераторов нужен Emscripten. Добавьте em++ в PATH или задайте EMXX.')
    native = TRACKER / 'Native'
    exports = ['_malloc', '_free', '_gwt_create', '_gwt_orbit', '_gt_destroy',
               '_gt_render', '_gt_bpm', '_gt_options', '_gt_clock', '_gt_weather', '_gt_street']
    subprocess.run([*command, '-O3', '-std=c++17', '--no-entry', '-I', str(native),
                    str(native / 'TrackerAudio.cpp'), str(ROOT / 'gremlinamp/tracker-bridge.cpp'),
                    '-sMODULARIZE=1', '-sEXPORT_NAME=GremlinTracker', '-sENVIRONMENT=worker',
                    '-sFILESYSTEM=0', '-sEXPORTED_FUNCTIONS=' + json.dumps(exports),
                    '-sEXPORTED_RUNTIME_METHODS=["HEAPU8","HEAPF32"]',
                    '-o', str(output / 'runtime/tracker-core.js')], env=env, check=True)
    shutil.copytree(TRACKER / 'Resources/Trackers', output / 'trackers', dirs_exist_ok=True)


def orbita_icon(target, size):
    # Rasterize the orbit symbol from the Android icon's 48×48 coordinate system.
    def color(px, py):
        x, y = (px + .5) * 48 / size, (py + .5) * 48 / size
        if (x - 33) ** 2 + (y - 16) ** 2 < 9:
            return bytes((255, 208, 138))
        line = abs(16 * x + 34 * y - 1200) / (16**2 + 34**2)**.5
        ring = abs(((x - 24)**2 + (y - 24)**2)**.5 - 13)
        if (7 <= x <= 41 and line < 1.15) or ring < 1.15 or (
            abs(x - 24) < 1.15 and 21 <= y <= 27 or abs(y - 24) < 1.15 and 21 <= x <= 27
        ):
            return bytes((161, 234, 216))
        return bytes((24, 36, 44))

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    pixels = b''.join(b'\0' + b''.join(color(x, y) for x in range(size)) for y in range(size))
    target.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>2I5B', size, size, 8, 2, 0, 0, 0))
                       + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b''))


def build(directory, identifier, title, api, personal_radio_code=None, personal_radio_assets=None):
    output = directory / identifier
    output.mkdir(parents=True, exist_ok=True)
    for name in ('style.css', 'site.js'):
        shutil.copyfile(ROOT / 'shared' / name, output / name)
    shutil.copyfile(ROOT / identifier / 'app.js', output / 'app.js')
    shell = ROOT / ('orbita/index.html' if identifier == 'orbita' else 'shared/index.html')
    (output / 'index.html').write_text(shell.read_text().replace('@TITLE@', title))
    if identifier == 'gremlinamp':
        runtime = MUSIC / 'decoder-runtime'
        files = [p for p in runtime.rglob('*') if p.is_file() and p.relative_to(runtime).parts[0] != 'samples']
        for source in files:
            target = output / 'runtime' / source.relative_to(runtime)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        parts = ["import {DECODERS, decoderFor} from './runtime/decoders.js';",
                 "const decoderAssetsByWorker = {};",
                 "const startDecoder = (view, name) => { const worker = new view.Worker(new URL('./runtime/' + name, import.meta.url)); return {worker, dispose: () => worker.terminate()}; };"]
        for filename in ('strings.js', 'formats.js', 'playback.js'):
            bundled = TRACKER / filename
            source = bundled if bundled.is_file() else MUSIC / 'src' / filename
            parts.append(source.read_text())
        parts.append("export {DecoderPlayback, capsuleRates, routeFile, trackDuration, unpackCapsule};\n"
                     "export {inputName, virtualPath} from './runtime/decoders.js';")
        (output / 'decoder.js').write_text('\n'.join(parts))
        shutil.copyfile(MUSIC / 'THIRD_PARTY_NOTICES.md', output / 'THIRD_PARTY_NOTICES.md')
        for name in ('live.js', 'radio-stations.json', 'arrangement.js'):
            shutil.copyfile(ROOT / identifier / name, output / name)
        sealed = output / 'personal-radio.sealed.json'
        if personal_radio_code is not None:
            assets = personal_radio_assets or WORKSPACE / 'GremlinAmp/app/src/main/assets'
            data = subprocess.check_output(['node', str(ROOT / 'tools/seal-personal-radio.cjs'),
                                            str(personal_radio_code), str(assets)])
            sealed.write_bytes(data)
        else:
            sealed.unlink(missing_ok=True)
        for name in ('tracker-worker.js', 'arrangement-worker.js', 'arrangement-engine.js'):
            shutil.copyfile(ROOT / identifier / name, output / 'runtime' / name)
        tracker(output)
    else:
        plan = (ORBITA / 'map-plan.svg').read_text().replace('<svg ', '<svg xmlns="http://www.w3.org/2000/svg" ', 1)
        (output / 'map-plan.svg').write_text(plan)
        section = (ORBITA / 'network-section.svg').read_text()
        styles = '\n'.join(line for line in ORBITA_STYLE.read_text().splitlines()
                           if line.startswith(('.cutaway-', '.network-cutaway ')))
        (output / 'network-section.svg').write_text(section.replace('>\n', f'>\n<style>{styles}</style>\n', 1))
        shutil.copyfile(ORBITA / 'published-art.json', output / 'published-art.json')
        shutil.copyfile(ORBITA / 'deck-points.json', output / 'deck-points.json')
        for name in ('art-library.js', 'world-screens.js', 'port-screens.js', 'style.css'):
            shutil.copyfile(ROOT / identifier / name, output / name)
    icons = []
    for size in (192, 512):
        name = f'icon-{size}.png'
        if identifier == 'gremlinamp':
            subprocess.run(['sips', '-z', str(size), str(size), str(TRACKER / 'Resources/AppIcon.png'),
                            '--out', str(output / name)], check=True, stdout=subprocess.DEVNULL)
        else:
            orbita_icon(output / name, size)
        icons.append({'src': name, 'type': 'image/png', 'sizes': f'{size}x{size}'})
    theme = '#10191f' if identifier == 'orbita' else '#101718'
    manifest = {'id': './', 'start_url': './', 'scope': './', 'name': title,
                'short_name': 'Орбита' if identifier == 'orbita' else title,
                'lang': 'ru', 'display': 'standalone', 'background_color': theme,
                'theme_color': theme, 'icons': icons}
    (output / 'manifest.webmanifest').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    paths = sorted(p for p in output.rglob('*') if p.is_file() and p.name not in ('sw.js', 'publication.json'))
    template = (ROOT / 'shared/sw.template.js').read_text()
    hashes = hashlib.sha256(template.encode())
    for path in paths:
        hashes.update(path.relative_to(output).as_posix().encode())
        hashes.update(path.read_bytes())
    worker = template.replace('@VERSION@', hashes.hexdigest()[:16])
    worker = worker.replace('@API@', json.dumps(api)).replace('@SHELL@', json.dumps([p.relative_to(output).as_posix() for p in paths]))
    (output / 'sw.js').write_text(worker)
    payload = {
        path.relative_to(output).as_posix(): {'bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
        for path in [*paths, output / 'sw.js']
    }
    (output / 'publication.json').write_text(json.dumps({'schema': 1, 'app': identifier, 'files': payload}, indent=2) + '\n')
    with ZipFile(directory / f'{identifier}-pwa.zip', 'w', ZIP_DEFLATED) as package:
        for path in [*paths, output / 'sw.js', output / 'publication.json']:
            package.write(path, path.relative_to(output))
    print(f'{identifier}: {len(paths)} файлов, {sum(path.stat().st_size for path in paths) / 1048576:.1f} МБ')


if __name__ == '__main__':
    cli = argparse.ArgumentParser()
    cli.add_argument('--out', type=Path, default=ROOT / 'dist')
    cli.add_argument('--app', choices=('gremlinamp', 'orbita'))
    cli.add_argument('--personal-radio-code-file', type=Path)
    cli.add_argument('--personal-radio-assets', type=Path)
    args = cli.parse_args()
    if args.app == 'gremlinamp' or args.app is None and (ROOT / 'gremlinamp').is_dir():
        build(args.out, 'gremlinamp', 'GremlinAmp', '/music/', args.personal_radio_code_file, args.personal_radio_assets)
    if args.app == 'orbita' or args.app is None and (ROOT / 'orbita').is_dir():
        build(args.out, 'orbita', 'Орбитальная станция', '/game-worlds/orbita/')
