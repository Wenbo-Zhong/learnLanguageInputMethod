#!/usr/bin/env bash
set -euo pipefail
root="$(dirname "$(dirname "$(realpath "$0")")")"
products="${1:?Pass the Debug-iphonesimulator build products directory}"
simulator="${2:?Pass the simulator UDID}"
backend="${3:-default}"
sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
probe="$(mktemp -d /tmp/learnime-translation.XXXXXX)/translation-smoke"
xcrun --sdk iphonesimulator swiftc -parse-as-library -D DEBUG -target arm64-apple-ios16.0-simulator -sdk "$sdk" \
  -I "$products" -I "$root/native/include" -F "$products" \
  -import-objc-header "$root/iosApp/Keyboard/Bridge.h" \
  "$root/iosApp/Keyboard/LocalEngine.swift" \
  "$root/iosApp/Keyboard/M2mEngine.swift" \
  "$root/iosApp/Keyboard/TranslationRuntime.swift" \
  "$root/scripts/ios-translation-smoke.swift" \
  "$products/TensorFlowLite.o" "$products/LiteRT.o" \
  -L "$root/native/build/iphonesimulator-ninja/lib" \
  -llearnime-sentencepiece -lsentencepiece -lc++ \
  -framework LearnIMEM2M -framework Shared \
  -framework TensorFlowLiteC -framework CLiteRT -framework Metal \
  -Xlinker -rpath -Xlinker "$products" -o "$probe"
xcrun simctl spawn "$simulator" "$probe" \
  "$products/LearnIME.app/PlugIns/LearningKeyboard.appex" "$backend"
