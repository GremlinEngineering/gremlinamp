#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_objects="$PWD/build/objects-$(uname -m)"
xcrun swiftc -module-cache-path build/module-cache -swift-version 5 -O -parse-as-library   -import-objc-header Native/GremlinAudio.h Sources/{Models,DecoderInputs,NativeAudio}.swift tests/DecoderParityTests.swift   -L "$task_objects" -lGremlinAudio -Xlinker -rpath -Xlinker "$task_objects"   -framework AVFoundation -framework CryptoKit -o "$task_objects/decoder-parity"
"$task_objects/decoder-parity" "$@"
