#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/prepare-ios-extras.sh
platform="${1:-iphoneos}"
case "$platform" in iphoneos|iphonesimulator) ;; *) echo "Unsupported platform: $platform" >&2; exit 1;; esac
build="native/build/$platform-ninja"
sdk="$(xcrun --sdk "$platform" --show-sdk-path)"
cmake -S native -B "$build" -G Ninja \
  -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$sdk" -DCMAKE_BUILD_TYPE="${CONFIGURATION:-Debug}" \
  -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 \
  -DCMAKE_ARCHIVE_OUTPUT_DIRECTORY="$PWD/$build/lib" \
  -DCMAKE_ARCHIVE_OUTPUT_DIRECTORY_DEBUG="$PWD/$build/lib" \
  -DCMAKE_ARCHIVE_OUTPUT_DIRECTORY_RELEASE="$PWD/$build/lib"
cmake --build "$build" --config "${CONFIGURATION:-Debug}" --target learnime-sentencepiece learnime-pinyin learnime-handwriting --parallel 4
