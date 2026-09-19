#!/usr/bin/env bash
# Builds the reference engine + bridge core for the host and runs test_engine.py.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/../../build-host}"
mkdir -p "$out"
g++ -std=c++17 -O2 -shared -fPIC -fvisibility=hidden -I"$here/../../engine-sdk" \
    "$here/../src/reference_engine.cpp" -o "$out/libref_engine.so"
g++ -std=c++17 -O2 -shared -fPIC \
    "$here/../../app/src/main/cpp/bridge_core.cpp" "$here/bridge_test_shim.cpp" \
    -ldl -o "$out/libbridge_shim.so"
python3 "$here/test_engine.py" "$out"
