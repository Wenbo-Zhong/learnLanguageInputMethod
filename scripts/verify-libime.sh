#!/usr/bin/env bash
set -euo pipefail
repo_root="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve().parent.parent)' "$0")"
build="$repo_root/native/build/libime-host"
work="$(mktemp -d "${TMPDIR:-/tmp}/learnime-check.XXXXXX")"
cmake -S "$repo_root/native" -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build "$build" --target pinyin-smoke pinyin-contract phrase-audit --parallel 8
"$build/pinyin-contract" "$repo_root/input-data/libime/sc.dict"
"$build/pinyin-smoke" "$repo_root/input-data/libime/sc.dict" "$work/user.dat"
"$build/phrase-audit" "$repo_root/input-data/libime/sc.dict" \
    < "$repo_root/scripts/phrase-audit-input.tsv" > "$work/phrases.tsv"
echo "Verification evidence: $work"
