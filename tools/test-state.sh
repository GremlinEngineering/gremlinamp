#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
JSON_JAR="${GREMLINAMP_JSON_JAR:?Set GREMLINAMP_JSON_JAR to an org.json JVM jar}"
mkdir -p build/state-tests
java com.sun.tools.javac.Main -cp "$JSON_JAR" -d build/state-tests app/src/main/java/ru/oopssre/gremlinamp/TransportState.java app/src/main/java/ru/oopssre/gremlinamp/PlaybackIdentity.java app/src/main/java/ru/oopssre/gremlinamp/PlaybackRules.java app/src/main/java/ru/oopssre/gremlinamp/ContinuationRules.java app/src/main/java/ru/oopssre/gremlinamp/SyncRules.java tests/java/ru/oopssre/gremlinamp/Regression.java
java -cp "build/state-tests:$JSON_JAR" ru.oopssre.gremlinamp.Regression
