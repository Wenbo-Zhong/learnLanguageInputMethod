#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="models/m2m100-418m-int8"
base="https://huggingface.co/Xenova/m2m100_418M/resolve/9c374f0b7aca709787cea97b047bfbbd1559d177"
mkdir -p "$out/onnx"
for name in config.json vocab.json tokenizer_config.json sentencepiece.bpe.model onnx/encoder_model_quantized.onnx onnx/decoder_model_merged_quantized.onnx; do
    expected=$(awk -v name="$name" '$2 == name { print $1 }' "$out/SHA256SUMS")
    if [[ -f "$out/$name" && $(shasum -a 256 "$out/$name" | awk '{print $1}') == "$expected" ]]; then
        continue
    fi
    curl -fL --retry 3 --connect-timeout 30 "$base/$name" -o "$out/$name.tmp"
    actual=$(shasum -a 256 "$out/$name.tmp" | awk '{print $1}')
    [[ "$actual" == "$expected" ]] || { echo "SHA256 mismatch: $name" >&2; exit 1; }
    mv "$out/$name.tmp" "$out/$name"
done
# Use sentencepiece, onnx and onnxruntime==1.23.2 in the Python environment.
"${PYTHON:-python3}" scripts/prepare-m2m-tokenizer.py "$out"
"${PYTHON:-python3}" scripts/prepare-m2m-decoder.py "$out/onnx/decoder_model_merged_quantized.onnx" "$out"
(cd "$out" && shasum -a 256 -c SHA256SUMS)
