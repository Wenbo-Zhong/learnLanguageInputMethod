#!/usr/bin/env bash
set -euo pipefail
repo_root="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve().parent.parent)' "$0")"
data_cache="${TMPDIR:-/tmp}/learnime-libime-data"
mkdir -p "$data_cache" "$repo_root/input-data/libime"
fetch() {
    local filename="$1" expected="$2"
    if [[ ! -f "$data_cache/$filename" ]] ||
       [[ "$(shasum -a 256 "$data_cache/$filename" | awk '{print $1}')" != "$expected" ]]; then
        curl -fL --connect-timeout 30 --max-time 1800 --retry 2 \
            "https://download.fcitx-im.org/data/$filename" -o "$data_cache/$filename.tmp"
        actual="$(shasum -a 256 "$data_cache/$filename.tmp" | awk '{print $1}')"
        [[ "$actual" == "$expected" ]] || { echo "Checksum mismatch: $filename" >&2; exit 1; }
        mv "$data_cache/$filename.tmp" "$data_cache/$filename"
    fi
    tar -xf "$data_cache/$filename" -C "$data_cache"
}
fetch lm_sc.arpa-20260629.tar.zst 06808333b9173e5374cf2cb5afc12d08f5625bf9abb536489cac376fc05f2e7f
fetch dict-20260907.tar.zst fb75a179065e690dfc4559ce1807cbaf4fbe4f0111a5005615be9f435e6b9d76
cmake -S "$repo_root/native" -B "$repo_root/native/build/libime-host" -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build "$repo_root/native/build/libime-host" \
    --target learnime-pinyindict learnime-lm-build learnime-lm-predict --parallel 8
"$repo_root/native/build/libime-host/learnime-pinyindict" \
    "$data_cache/dict_sc.txt" "$repo_root/input-data/libime/sc.dict"
"$repo_root/native/build/libime-host/learnime-lm-build" -s -a 22 -q 4 trie \
    "$data_cache/lm_sc.arpa" "$repo_root/input-data/libime/sc.lm"
"$repo_root/native/build/libime-host/learnime-lm-predict" \
    "$repo_root/input-data/libime/sc.lm" "$data_cache/lm_sc.arpa" \
    "$repo_root/input-data/libime/sc.lm.predict"
cp "$repo_root/third_party/libime/LICENSES/LGPL-2.1-or-later.txt" "$repo_root/input-data/libime/LICENSE.txt"
shasum -a 256 "$repo_root/input-data/libime/sc.dict" "$repo_root/input-data/libime/sc.lm" \
    "$repo_root/input-data/libime/sc.lm.predict"
python3 - "$repo_root/input-data/libime" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
root.joinpath("SHA256SUMS").write_text("".join(
    hashlib.sha256(root.joinpath(name).read_bytes()).hexdigest() + "  " + name + "\n"
    for name in ("sc.dict", "sc.lm", "sc.lm.predict")
))
PY
