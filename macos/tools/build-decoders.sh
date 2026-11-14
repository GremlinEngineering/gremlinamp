#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_cpu="${1:?architecture required}"
task_cmake="${GREMLINAMP_CMAKE:-$(command -v cmake || true)}"
if [ -z "$task_cmake" ] || [ ! -x "$task_cmake" ]; then
  echo 'Для нативных декодеров нужен CMake 3.22+. Задайте GREMLINAMP_CMAKE или добавьте cmake в PATH.' >&2
  exit 1
fi
task_ninja="${GREMLINAMP_NINJA:-$(command -v ninja || true)}"
if [ -z "$task_ninja" ] && [ -x "$(dirname "$task_cmake")/ninja" ]; then task_ninja="$(dirname "$task_cmake")/ninja"; fi
task_build="$PWD/build/decoder-$task_cpu"
task_project="$PWD/build/decoder-project"
python3 tools/prepare-decoders.py "$task_project"
task_generator=(-G 'Unix Makefiles')
if [ -n "$task_ninja" ]; then task_generator=(-G Ninja "-DCMAKE_MAKE_PROGRAM=$task_ninja"); fi
"$task_cmake" -S "$task_project" -B "$task_build" "${task_generator[@]}"   -DCMAKE_C_COMPILER="$(xcrun -f clang)" -DCMAKE_CXX_COMPILER="$(xcrun -f clang++)"   -DCMAKE_C_COMPILER_TARGET="$task_cpu-apple-macosx12.0"   -DCMAKE_OSX_ARCHITECTURES="$task_cpu" -DCMAKE_OSX_DEPLOYMENT_TARGET=12.0 -DCMAKE_BUILD_TYPE=Release
"$task_cmake" --build "$task_build" -j "${GREMLINAMP_BUILD_JOBS:-6}"
