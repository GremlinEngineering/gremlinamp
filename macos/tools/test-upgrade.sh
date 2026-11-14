#!/bin/bash
# Exercise the Finder-style replacement at an isolated installation path.
set -euo pipefail
cd "$(dirname "$0")/.."
task_old="$PWD/${1:-build/GremlinAmp-0.2.1-arm64.dmg}"
task_new="$PWD/${2:-build/GremlinAmp-0.2.2-arm64.dmg}"
task_release="$PWD/build/macos-release.json"
task_work="$(mktemp -d /tmp/gremlinamp-upgrade.XXXXXX)"
task_old_mount="$task_work/old"
task_new_mount="$task_work/new"
task_home="$task_work/profile"
task_support="$task_home/Library/Application Support/GremlinAmp"
task_install="$task_work/Applications/GremlinAmp.app"
task_pid=
cleanup() {
  if [ -n "$task_pid" ]; then kill -TERM "$task_pid" 2>/dev/null || true; wait "$task_pid" 2>/dev/null || true; fi
  hdiutil detach "$task_old_mount" -quiet 2>/dev/null || true
  hdiutil detach "$task_new_mount" -quiet 2>/dev/null || true
}
trap cleanup EXIT
mkdir -p "$task_old_mount" "$task_new_mount" "$task_home/Library/Preferences" "$task_work/Applications" "$task_support/updates" "$task_support/files"
hdiutil attach "$task_old" -readonly -nobrowse -mountpoint "$task_old_mount" -quiet
hdiutil attach "$task_new" -readonly -nobrowse -mountpoint "$task_new_mount" -quiet
task_hash="$(shasum -a 256 "$task_new" | cut -d ' ' -f 1)"
task_dmg="$(basename "$task_new")"
cp "$task_new" "$task_support/updates/$task_dmg"
python3 - "$task_release" "$task_support/updates/ready.json" "$task_dmg" "$task_hash" <<'PY'
import json
from pathlib import Path
import sys
release=json.loads(Path(sys.argv[1]).read_text())
image=release['dmg']['arm64']
assert release['version']=='0.2.2' and release['build']==6 and image['sha256']==sys.argv[4]
assert image['bytes']==Path(sys.argv[2]).with_name(sys.argv[3]).stat().st_size
Path(sys.argv[2]).write_text(json.dumps(dict(release=release,image=image,filename=sys.argv[3])))
PY
cp tests/tone.nsf "$task_support/files/offline-fixture.nsf"
task_saved="$(shasum -a 256 "$task_support/files/offline-fixture.nsf")"
launch() {
  local expected="$1" build="$2"
  test "$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "$task_install/Contents/Info.plist")" = "$expected"
  test "$(/usr/libexec/PlistBuddy -c 'Print CFBundleVersion' "$task_install/Contents/Info.plist")" = "$build"
  test "$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$task_install/Contents/Info.plist")" = 'ru.oopssre.gremlinamp.mac'
  codesign --verify --deep --strict "$task_install"
  CFFIXED_USER_HOME="$task_home" "$task_install/Contents/MacOS/GremlinAmp" -autoClientUpdates NO -volume 0.43 > "$task_work/launch-$expected.log" 2>&1 &
  task_pid=$!
  for ((i=0;i<5;i++)); do sleep 1; kill -0 "$task_pid"; done
  kill -TERM "$task_pid"
  wait "$task_pid" 2>/dev/null || true
  task_pid=
}
ditto "$task_old_mount/GremlinAmp.app" "$task_install"
launch 0.2.1 5
mv "$task_install" "$task_work/GremlinAmp-previous.app"
ditto "$task_new_mount/GremlinAmp.app" "$task_install"
launch 0.2.2 6
test "$task_saved" = "$(shasum -a 256 "$task_support/files/offline-fixture.nsf")"
printf '%s\n' "Upgrade: launched 0.2.1 (5), replaced at the same path, launched signed 0.2.2 (6); isolated offline data preserved and DMG SHA-256 verified."
