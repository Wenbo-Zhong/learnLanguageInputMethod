#!/usr/bin/env python3
"""Serialize the locked Android graphs with the exact iOS ORT version."""
import gc
import hashlib
import json
from pathlib import Path
import shutil
import onnxruntime as ort

root = Path(__file__).resolve().parents[1]
source = root / "models/m2m100-418m-int8"
target = root / "build/ios-assets/m2m100"
target.mkdir(parents=True, exist_ok=True)
if ort.__version__ != "1.23.0":
    raise RuntimeError("iOS conversion requires onnxruntime==1.23.0")
def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

for line in (source / "SHA256SUMS").read_text().splitlines():
    expected, name = line.split()
    if sha256(source / name) != expected:
        raise RuntimeError(f"Pinned M2M asset checksum mismatch: {name}")
if (source / "optimized-decoder-runtime.txt").read_text().strip() != "1.23.2":
    raise RuntimeError("Prepare the Android optimized decoder before iOS conversion")
identity = hashlib.sha256(b"m2m-bundled-v1:ort-1.23.2")
for path in [source / "onnx/decoder_model_optimized.onnx", source / "onnx/encoder_model_quantized.onnx",
             source / "sentencepiece.bpe.model", source / "tokenizer-map.bin"]:
    identity.update(path.name.encode() + b"\0")
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            identity.update(block)
if (source / "model-identity.txt").read_text().strip() != f"m2m-bundled-v1:{identity.hexdigest()}":
    raise RuntimeError("Optimized M2M decoder identity mismatch")
manifest = {"runtime": ort.__version__, "models": {}}
for name, original in [("encoder", "encoder_model_quantized.onnx"), ("decoder", "decoder_model_optimized.onnx")]:
    path = source / "onnx" / original
    digest = sha256(path)
    destination = target / f"{name}.ort"
    stamp = target / f"{name}.sha256"
    signature = {"source": digest, "converter": sha256(Path(__file__)), "runtime": ort.__version__}
    try:
        previous = json.loads(stamp.read_text())
    except (FileNotFoundError, ValueError):
        previous = {}
    valid = destination.exists() and previous.get("signature") == signature
    if valid:
        valid = previous.get("output") == sha256(destination)
    if not valid:
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.enable_cpu_mem_arena = False
        options.enable_mem_pattern = False
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
        options.add_session_config_entry("session.save_model_format", "ORT")
        options.add_session_config_entry("session.disable_prepacking", "1")
        options.optimized_model_filepath = str(destination.with_suffix(".tmp"))
        session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
        del session
        gc.collect()
        destination.with_suffix(".tmp").replace(destination)
        stamp.write_text(json.dumps({"signature": signature, "output": sha256(destination)}))
    manifest["models"][name] = {"onnx_sha256": digest, "ort_sha256": sha256(destination)}
for name in ["sentencepiece.bpe.model", "tokenizer-map.bin", "MODEL-SOURCE.md"]:
    shutil.copy2(source / name, target / name)
(target / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(f"iOS M2M100 assets: {target}")
