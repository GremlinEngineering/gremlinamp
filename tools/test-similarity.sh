#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
JSON_JAR="${GREMLINAMP_JSON_JAR:?Set GREMLINAMP_JSON_JAR}"
mkdir -p build/similarity-tests
java com.sun.tools.javac.Main -cp "$JSON_JAR:$SDK/platforms/android-35/android.jar" -d build/similarity-tests tests/host/android/content/*.java tests/host/android/net/*.java app/src/main/java/ru/oopssre/gremlinamp/{Library,StreamCache,MusicArchive,MusicContract,Gme,NetworkPolicy,PlaybackRules,SimilarityAvailability,SimilarityRules,MusicSimilarity}.java tests/host/ru/oopssre/gremlinamp/ContractFixtures.java tests/similarity/ru/oopssre/gremlinamp/*.java
java -cp "build/similarity-tests:$JSON_JAR:$SDK/platforms/android-35/android.jar" ru.oopssre.gremlinamp.SimilarityChecks "$@"
