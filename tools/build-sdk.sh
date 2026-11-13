#!/usr/bin/env bash
# Android release build. Requires Gradle, SDK 35, NDK 27, CMake 3.22.1 and JDK 17.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
BT="$SDK/build-tools/35.0.0"
VERSION_NAME="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)"
: "${VERSION_NAME:?Missing versionName}"
mkdir -p build
"${GRADLE:-gradle}" :app:assembleRelease
"$BT/zipalign" -f -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk build/aligned.apk
KEYSTORE="${GREMLINAMP_KEYSTORE:-build/development.keystore}"
if [ ! -f "$KEYSTORE" ]; then
 keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias gremlinamp -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=GremlinAmp Development'
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass env:GREMLINAMP_STOREPASS --out build/GremlinAmp-$VERSION_NAME.apk build/aligned.apk
"$BT/apksigner" verify --verbose build/GremlinAmp-$VERSION_NAME.apk
