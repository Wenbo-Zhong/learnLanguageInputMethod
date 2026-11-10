#!/usr/bin/env bash
set -euo pipefail
repo_root="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve().parent.parent)' "$0")"
target="$repo_root/build/ios-assets/extras"
mkdir -p "$target"
for name in english-frequency.txt STCharacters.txt STPhrases.txt handwriting-zh_CN.model NOTICE.txt \
  symspellpy-6.10.1-LICENSE.txt opencc-0.1.7-LICENSE.txt tegaki-COPYING SOURCES.json; do
  test -f "$repo_root/input-data/extras/$name" || {
    echo "Run python3 scripts/prepare-ime-extras.py before building iOS" >&2; exit 1;
  }
  cp "$repo_root/input-data/extras/$name" "$target/$name"
done
cp "$repo_root/third_party/zinnia/COPYING" "$target/zinnia-LICENSE.txt"
