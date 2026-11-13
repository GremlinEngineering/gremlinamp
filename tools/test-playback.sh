#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
JSON_JAR="${GREMLINAMP_JSON_JAR:?Set GREMLINAMP_JSON_JAR to an org.json JVM jar}"
mkdir -p build/playback-tests
mapfile -t runtime < <(find tests/playback-runtime -name '*.java' -print)
java com.sun.tools.javac.Main -cp "$JSON_JAR" -d build/playback-tests \
 tests/host/android/content/*.java tests/host/android/net/*.java "${runtime[@]}" \
 app/src/main/java/ru/oopssre/gremlinamp/{Library,StreamCache,MusicArchive,MusicContract,ChipDecoder,PcmSource,SpaceTracker,OrbitSnapshot,OrbitData,SpaceMotion,SpaceSensors,WavPcm,Gme,GmePlus,NativeVgm,NativeGsf,NativeBoard,NativeModule,NativeMachine,NativePsf,NativeStream,BoardCapsule,AudioInput,NetworkPolicy,Continuation,ContinuationRules,PlaybackRules,TransportState,PlaybackIdentity,PlaybackHistory,PlaybackService,AudioEnergy,RadioStations,MusicSimilarity,SimilarityRules}.java \
 tests/similarity/ru/oopssre/gremlinamp/SimilarityStore.java tests/host/ru/oopssre/gremlinamp/ContractFixtures.java tests/host/ru/oopssre/gremlinamp/OfflinePlaybackChecks.java
java -cp "build/playback-tests:$JSON_JAR" ru.oopssre.gremlinamp.OfflinePlaybackChecks
