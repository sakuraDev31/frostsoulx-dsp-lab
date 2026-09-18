#!/usr/bin/env bash
# Run on your development machine. Builds the C++ engine ONLY, never Gradle/APK/tests.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 28.2.13676358}"
OUTPUT="${1:-build/frostsoulx-engine.zip}"
SOURCE="${2:-$ROOT/app/src/main/cpp/engine}"
SOURCE="$(cd "$SOURCE" && pwd)"
mkdir -p "$ROOT/build/engine-only"
# Only the build/output paths live here. An optional source root may be your separate
# engine checkout (must contain include/frostsoulx/ and src/). Do not change the C ABI.
for ABI in arm64-v8a x86_64; do
  BUILD="$ROOT/build/engine-only/$ABI"
  cmake -S "$ROOT/app/src/main/cpp/engine" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DFROSTSOULX_SOURCE_DIR="$SOURCE" \
    -DFROSTSOULX_STEAM_SDK="$ROOT/engine-bundle/third_party/steamaudio_sdk"
  cmake --build "$BUILD" --target frostsoulx_engine --parallel "${JOBS:-1}"
done
python3 "$ROOT/tools/engine_bundle.py" export "$OUTPUT" --native-dir "$ROOT/build/engine-only"
echo 'Import the compiled ZIP in Engine > Import ZIP. Trust/activate it; no APK rebuild.'
