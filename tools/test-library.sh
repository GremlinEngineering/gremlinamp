#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
JSON_JAR="${GREMLINAMP_JSON_JAR:?Set GREMLINAMP_JSON_JAR to an org.json JVM jar}"
mkdir -p build/library-tests
java com.sun.tools.javac.Main -cp "$JSON_JAR:$SDK/platforms/android-35/android.jar" -d build/library-tests tests/host/android/content/*.java tests/host/android/net/*.java app/src/main/java/ru/oopssre/gremlinamp/{Library,Gme,NetworkPolicy,Continuation,ContinuationRules,PlaybackRules,SyncRules}.java tests/host/ru/oopssre/gremlinamp/FeatureChecks.java
java -cp "build/library-tests:$JSON_JAR:$SDK/platforms/android-35/android.jar" ru.oopssre.gremlinamp.FeatureChecks
