#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <engine-directory-or-zip>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/cpp/engine"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [[ -d "$1" ]]; then
  SRC="$(cd "$1" && pwd)"
else
  unzip -q "$1" -d "$TMP"
  SRC="$TMP"
fi

HEADER="$(find "$SRC" -path '*/include/frostsoulx/immersive_audio_engine.h' -o -name 'immersive_audio_engine.h' | head -1)"
SOURCE="$(find "$SRC" -path '*/src/immersive_audio_engine.cpp' -o -name 'immersive_audio_engine.cpp' | head -1)"
[[ -n "$HEADER" && -n "$SOURCE" ]] || { echo "Bundle must contain the public header and implementation" >&2; exit 1; }

rm -rf "$DEST/include" "$DEST/src"
mkdir -p "$DEST/include/frostsoulx" "$DEST/src"
cp "$HEADER" "$DEST/include/frostsoulx/immersive_audio_engine.h"
cp "$SOURCE" "$DEST/src/immersive_audio_engine.cpp"
sed -i 's#frostsoulx/ImmersiveAudioEngine.h#frostsoulx/immersive_audio_engine.h#' "$DEST/src/immersive_audio_engine.cpp"
echo "Imported engine into $DEST"
