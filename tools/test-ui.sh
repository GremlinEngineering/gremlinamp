#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/ui-tests
java com.sun.tools.javac.Main -d build/ui-tests app/src/main/java/ru/oopssre/gremlinamp/TransportState.java app/src/main/java/ru/oopssre/gremlinamp/UiRules.java tests/java/ru/oopssre/gremlinamp/UiChecks.java
java -cp build/ui-tests ru.oopssre.gremlinamp.UiChecks
