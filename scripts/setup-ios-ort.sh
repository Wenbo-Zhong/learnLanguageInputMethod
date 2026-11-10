#!/usr/bin/env bash
set -euo pipefail
root="$(dirname "$(dirname "$(realpath "$0")")")"
target="$root/build/ios-ort"
checksum="756a78e0168f29840bc614b43aeb03e63673f44022e0221d21698a2c8ed747ef"
if [[ ! -f "$target/onnxruntime.xcframework/Info.plist" ]]; then
  mkdir -p "$root/build"
  archive="$root/build/onnxruntime-ios-1.23.0.zip"
  curl -fL --retry 3 https://download.onnxruntime.ai/pod-archive-onnxruntime-c-1.23.0.zip -o "$archive"
  echo "$checksum  $archive" | shasum -a 256 -c -
  mkdir -p "$target"
  unzip -q "$archive" -d "$target"
fi
