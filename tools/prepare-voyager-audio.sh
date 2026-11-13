#!/usr/bin/env bash
set -euo pipefail
output=${1:?Expected output PCM path}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl --fail --location --retry 2 --max-time 120 \
  'https://space.physics.uiowa.edu/plasma-wave/voyager/v1pws_interstellar_epo.mp4' \
  --output "$tmp/voyager.mp4"
python3 - "$tmp/voyager.mp4" <<'PY'
import hashlib
from pathlib import Path
import sys

expected = "7d62c16b9ea30aab763b98bb6a4441018b8dd04b315c97795b63290abbbd383d"
if hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() != expected:
    sys.exit("Unexpected SHA-256 for the Voyager 1 PWS source video")
PY
ffmpeg -hide_banner -loglevel error -i "$tmp/voyager.mp4" \
  -ss 0.5 -t 11.5 -vn -ac 1 -ar 16000 -f s16le "$tmp/voyager-plasma.pcm"
mkdir -p "$(dirname "$output")"
cp "$tmp/voyager-plasma.pcm" "$output"
