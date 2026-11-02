#!/usr/bin/env bash
set -euo pipefail
repo_root="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve().parent.parent)' "$0")"
serial="${1:-55814895}"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
adb="$sdk_root/platform-tools/adb"
compiler="$sdk_root/ndk/27.2.12479018/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang++"
build="$(python3 - "$repo_root/androidApp/.cxx/Debug" <<'PY'
import pathlib, sys
archives = list(pathlib.Path(sys.argv[1]).glob("*/arm64-v8a/liblearnime-pinyin.a"))
if not archives:
    raise SystemExit("Build :androidApp:externalNativeBuildDebug first")
print(max(archives, key=lambda path: path.stat().st_mtime).parent)
PY
)"
work="$(mktemp -d "${TMPDIR:-/tmp}/learnime-device-check.XXXXXX")"
for target in pinyin-contract pinyin-smoke phrase-audit; do
    "$compiler" -std=c++20 -O2 -UNDEBUG -I "$repo_root/native/include" \
        "$repo_root/scripts/$target.cpp" -L "$build" \
        -llearnime-pinyin -llearnime-libime-pinyin -llearnime-libime-core \
        -llearnime-kenlm -llearnime-fcitx-utils -llearnime-boost-iostreams \
        "$build/zstd/lib/libzstd.a" -ldl -llog -static-libstdc++ -o "$work/$target"
done
device="/data/local/tmp/learnime-libime-check"
"$adb" -s "$serial" shell mkdir -p "$device"
"$adb" -s "$serial" push "$repo_root/input-data/libime" "$device/"
for target in pinyin-contract pinyin-smoke phrase-audit; do
    "$adb" -s "$serial" push "$work/$target" "$device/$target"
    "$adb" -s "$serial" shell chmod 755 "$device/$target"
done
"$adb" -s "$serial" shell "$device/pinyin-contract" "$device/libime/sc.dict" | tee "$work/contract.log"
"$adb" -s "$serial" shell "$device/pinyin-smoke" "$device/libime/sc.dict" \
    "$device/user-$(date +%s).dat" | tee "$work/smoke.log"
"$adb" -s "$serial" shell "$device/phrase-audit" "$device/libime/sc.dict" \
    < "$repo_root/scripts/phrase-audit-input.tsv" > "$work/phrases.tsv"
echo "Device evidence: $work"
