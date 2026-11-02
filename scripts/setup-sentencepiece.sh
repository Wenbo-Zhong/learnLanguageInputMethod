#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source_dir=third_party/sentencepiece
revision=17d7580d6407802f85855d2cc9190634e2c95624
patch_file=native/patches/sentencepiece-android.patch
if [[ -f "$source_dir/LEARNIME-REVISION" && ! -d "$source_dir/.git" ]]; then
  [[ "$(cat "$source_dir/LEARNIME-REVISION")" == "$revision" ]] || {
    echo "Unexpected bundled SentencePiece revision" >&2; exit 1;
  }
  git -C "$source_dir" apply --reverse --check "../../$patch_file"
  exit 0
fi
if [[ ! -d "$source_dir/.git" ]]; then
  mkdir -p "$source_dir"
  git -C "$source_dir" init -q
  git -C "$source_dir" remote add origin https://github.com/google/sentencepiece.git
  git -C "$source_dir" fetch --depth 1 origin "$revision"
  git -C "$source_dir" checkout --detach FETCH_HEAD
fi
actual="$(git -C "$source_dir" rev-parse HEAD)"
[[ "$actual" == "$revision" ]] || { echo "Unexpected SentencePiece revision: $actual" >&2; exit 1; }
if git -C "$source_dir" apply --reverse --check "../../$patch_file" 2>/dev/null; then
  : # Already patched.
else
  git -C "$source_dir" apply --check "../../$patch_file"
  git -C "$source_dir" apply "../../$patch_file"
fi
