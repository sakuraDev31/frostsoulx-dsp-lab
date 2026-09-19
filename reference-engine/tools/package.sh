#!/usr/bin/env bash
# Packs already-built engine libraries into an importable zip.
# Usage: package.sh <out.zip> <manifest.json> <abi>=<path/to/lib.so> [<abi>=<path> ...]
set -euo pipefail
out="$1"; manifest="$2"; shift 2
work="$(mktemp -d)"
cp "$manifest" "$work/manifest.json"
for spec in "$@"; do
  abi="${spec%%=*}"; lib="${spec#*=}"
  mkdir -p "$work/lib/$abi"
  cp "$lib" "$work/lib/$abi/"
done
rm -f "$out"
(cd "$work" && zip -qr "$OLDPWD/$out" .)
rm -rf "$work"
unzip -l "$out"
