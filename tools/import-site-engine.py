#!/usr/bin/env python3
"""Install the browser decoder runtime from the project's music_player.zip."""
import argparse
import hashlib
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "music_player/"
EXPECTED_SHA256 = "0074e542d389410e74db9320b23fdca33f43e16e3948677d1e942f1ca26dae8a"
FORMATS = ROOT / "app/src/main/java/ru/oopssre/gremlinamp/MusicContract.java"

def install(source, target):
    with source.open("rb") as package:
        digest = hashlib.file_digest(package, "sha256").hexdigest()
    if digest != EXPECTED_SHA256:
        raise ValueError("music_player.zip differs from the checked site bundle")
    target.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(source) as archive, tempfile.TemporaryDirectory(dir=target.parent) as temp:
        stage = Path(temp) / "site-engine"
        stage.mkdir()
        names = set(archive.namelist())
        text = archive.read(PREFIX + "decoders.js").decode("utf-8")
        decoder_blocks = re.findall(r"Object\.freeze\(\[([^\]]*)\]\)", text)
        formats = sorted(set(re.findall(r"'([a-z0-9-]+)'", "".join(decoder_blocks))) - {"snes-registers"})
        expected = re.search(r'SITE_FORMATS = "([^"]+)"', FORMATS.read_text())
        if not expected or formats != sorted(expected.group(1).split()):
            raise ValueError("Decoder format table changed. Update MusicContract before building.")
        for item in archive.infolist():
            if not item.filename.startswith(PREFIX) or item.is_dir():
                continue
            name = item.filename[len(PREFIX):]
            path = PurePosixPath(name)
            if (path.is_absolute() or ".." in path.parts or "\\" in name
                    or name != path.as_posix() or any(part.startswith(".") for part in path.parts)):
                continue
            vendor = name.startswith("vendor/")
            if not (name == "decoders.js" or len(path.parts) == 1 and name.endswith("-worker.js")
                    or vendor and (path.suffix in (".js", ".wasm", ".data", ".txt", ".json", ".md")
                                   or path.name in ("LICENSE", "COPYING"))):
                continue
            output = stage / path
            output.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(item) as data, output.open("xb") as file:
                shutil.copyfileobj(data, file)
        if PREFIX + "vendor/LIBVGM_0_9.json" not in names or not (stage / "vendor/libvgm/gremlin-libvgm.wasm").is_file():
            raise ValueError("Expected the site's libvgm 0.9 runtime")
        (stage / "archive-sha256.txt").write_text(digest + "\n")
        if target.exists():
            shutil.rmtree(target)
        stage.rename(target)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    install(args.archive, ROOT / "app/src/main/assets/site-engine")
