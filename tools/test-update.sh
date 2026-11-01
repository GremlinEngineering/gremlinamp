#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
java com.sun.tools.javac.Main -d "$tmpdir" app/src/main/java/ru/oopssre/gremlinamp/UpdateRules.java tests/UpdateRegression.java
java -cp "$tmpdir" ru.oopssre.gremlinamp.UpdateRegression
