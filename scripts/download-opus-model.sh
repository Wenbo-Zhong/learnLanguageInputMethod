#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=4ee3a9342aae4ca6fbae82c930b5dccff5dd68dd
out="models/opus-mt-tiny-zho-eng"
mkdir -p "$out"
base="https://huggingface.co/Helsinki-NLP/opus-mt_tiny_zho-eng/resolve/$revision"
curl -fL --retry 3 "$base/model.tflite" -o "$out/model.tflite"
curl -fL --retry 3 "$base/source.spm" -o "$out/source.spm"
curl -fL --retry 3 "$base/target.spm" -o "$out/target.spm"
(cd "$out" && shasum -a 256 -c SHA256SUMS)
