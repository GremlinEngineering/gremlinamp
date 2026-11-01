#!/usr/bin/env bash
# Reproducible dependency-free Android build. Requires SDK 35, NDK 27, CMake 3.22.1 and JDK 17.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
NDK="$SDK/ndk/27.0.12077973"
BT="$SDK/build-tools/35.0.0"
CMAKE="$SDK/cmake/3.22.1/bin/cmake"
mkdir -p build/classes build/dex build/apk/lib
java com.sun.tools.javac.Main -source 17 -target 17 -classpath "$SDK/platforms/android-35/android.jar" -d build/classes app/src/main/java/ru/oopssre/gremlinamp/*.java
"$BT/d8" --min-api 26 --lib "$SDK/platforms/android-35/android.jar" --output build/dex build/classes/ru/oopssre/gremlinamp/*.class
for ABI in arm64-v8a armeabi-v7a x86_64; do
 "$CMAKE" -S app/src/main/cpp -B "build/native-$ABI" -G Ninja -DCMAKE_MAKE_PROGRAM="$SDK/cmake/3.22.1/bin/ninja" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 -DGME_BUILD_EXAMPLES=OFF -DGME_BUILD_TESTING=OFF -DGME_BUILD_STATIC=ON -DGME_BUILD_SHARED=OFF -DGME_ZLIB=ON -DCMAKE_BUILD_TYPE=Release
 "$CMAKE" --build "build/native-$ABI" -j4
 mkdir -p "build/apk/lib/$ABI"
 cp "build/native-$ABI/libgremlinamp.so" "build/apk/lib/$ABI/"
done
"$BT/aapt2" compile --dir app/src/main/res -o build/resources.zip
"$BT/aapt2" link build/resources.zip -A app/src/main/assets -o build/unsigned.apk --manifest app/src/main/AndroidManifest.xml -I "$SDK/platforms/android-35/android.jar" --min-sdk-version 26 --target-sdk-version 35 --version-code 10 --version-name 0.2.0
cp build/dex/classes.dex build/apk/
(cd build/apk && zip -q -r ../unsigned.apk .)
"$BT/zipalign" -f -P 16 4 build/unsigned.apk build/aligned.apk
KEYSTORE="${GREMLINAMP_KEYSTORE:-build/development.keystore}"
if [ ! -f "$KEYSTORE" ]; then
 keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias gremlinamp -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=GremlinAmp Development'
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass env:GREMLINAMP_STOREPASS --out build/GremlinAmp-0.2.0.apk build/aligned.apk
"$BT/apksigner" verify --verbose build/GremlinAmp-0.2.0.apk
