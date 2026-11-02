#!/usr/bin/env python3
"""Precompute the fixed projection on the build machine, then bundle the optimized graph."""
import copy
import gc
import hashlib
import pathlib
import sys
import tempfile

import onnx
import onnxruntime as ort

RUNTIME_VERSION = "1.23.2"


def prepare(source: pathlib.Path, destination: pathlib.Path) -> None:
    if ort.__version__ != RUNTIME_VERSION:
        raise ValueError(f"Install onnxruntime=={RUNTIME_VERSION} to match the Android runtime")
    model = onnx.load(source)
    branch = next(node for node in model.graph.node if node.op_type == "If")
    graphs = [attribute.g for attribute in branch.attribute if attribute.type == onnx.AttributeProto.GRAPH]
    if len(graphs) != 2:
        raise ValueError("Expected the M2M100 merged decoder's two cache branches")
    outputs = {"model.shared.weight_transposed_quantized", "model.shared.weight_transposed"}
    lifted = []
    for graph in graphs:
        nodes = [node for node in graph.node if set(node.output) & outputs]
        if [node.op_type for node in nodes] != ["Transpose", "DequantizeLinear"]:
            raise ValueError("Unexpected M2M100 projection graph")
        if not lifted:
            lifted = [copy.deepcopy(node) for node in nodes]
        else:
            for reference, node in zip(lifted, nodes):
                reference.name = node.name
                if reference.SerializeToString() != node.SerializeToString():
                    raise ValueError("Projection operations differ between cache branches")
        for node in nodes:
            graph.node.remove(node)
    # Both operations depend only on initializers shared by the outer graph.
    constants = {tensor.name for tensor in model.graph.initializer}
    for node in lifted:
        if not set(node.input) <= constants:
            raise ValueError("Projection has a nonconstant input")
        constants.update(node.output)
    for node in reversed(lifted):
        model.graph.node.insert(0, node)
    onnx.checker.check_model(model)
    target = destination / "onnx/decoder_model_optimized.onnx"
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="learnime-bundled-decoder-") as temporary:
        temporary = pathlib.Path(temporary)
        shared = temporary / "shared.onnx"
        optimized = temporary / "optimized.onnx"
        onnx.save(model, shared)
        del model
        gc.collect()
        options = ort.SessionOptions()
        options.intra_op_num_threads = 4
        options.inter_op_num_threads = 1
        options.enable_mem_pattern = False
        options.enable_cpu_mem_arena = False
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
        options.add_session_config_entry("session.disable_quant_qdq", "1")
        options.optimized_model_filepath = str(optimized)
        session = ort.InferenceSession(str(shared), options, providers=["CPUExecutionProvider"])
        del session
        gc.collect()
        onnx.checker.check_model(str(optimized))
        frozen = onnx.load(optimized)
        projection = next(
            tensor for tensor in frozen.graph.initializer
            if tensor.name == "model.shared.weight_transposed"
        )
        if projection.data_type != onnx.TensorProto.FLOAT or list(projection.dims) != [1024, 128112]:
            raise ValueError("The fixed projection was not precomputed")
        if any(tensor.external_data for tensor in frozen.graph.initializer):
            raise ValueError("The bundled decoder must contain all weights in one file")
        del frozen, projection
        gc.collect()
        # Copy through a temporary sibling for atomic replacement across filesystems.
        staging = target.with_suffix(".onnx.tmp")
        try:
            with optimized.open("rb") as input_stream, staging.open("wb") as output_stream:
                while block := input_stream.read(1024 * 1024):
                    output_stream.write(block)
            staging.replace(target)
        finally:
            staging.unlink(missing_ok=True)
    # Include tokenization and both graphs so same-size model updates replace tiny extracted files.
    digest = hashlib.sha256()
    digest.update(f"m2m-bundled-v1:ort-{RUNTIME_VERSION}".encode())
    for path in (target, source.with_name("encoder_model_quantized.onnx"),
                 destination / "sentencepiece.bpe.model", destination / "tokenizer-map.bin"):
        digest.update(path.name.encode() + b"\0")
        with path.open("rb") as stream:
            while block := stream.read(1024 * 1024):
                digest.update(block)
    (destination / "model-identity.txt").write_text(f"m2m-bundled-v1:{digest.hexdigest()}\n")
    (destination / "optimized-decoder-runtime.txt").write_text(f"{RUNTIME_VERSION}\n")
    print(f"Prepared bundled optimized M2M100 decoder: {target} ({target.stat().st_size} bytes)")


if __name__ == "__main__":
    prepare(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
