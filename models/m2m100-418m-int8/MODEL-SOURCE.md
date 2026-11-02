# M2M100 418M · Android trial

Base model: https://huggingface.co/facebook/m2m100_418M
Base repository revision: `55c2e61bbf05dfb8d7abccdc3fae6fc8512fd636`
License: MIT.

ONNX INT8 conversion: https://huggingface.co/Xenova/m2m100_418M
Pinned conversion revision: `9c374f0b7aca709787cea97b047bfbbd1559d177`

This is the M2M100 418M architecture and weights converted to ONNX with INT8
quantization, not the original FP32 PyTorch checkpoint. The conversion can change
numerical results. Encoder and merged cached decoder total 631,984,548 bytes.
These source graphs are used on the build machine only. Android 0.3.2 bundles
`encoder_model_quantized.onnx` and `decoder_model_optimized.onnx`: the fixed
vocabulary projection is precomputed as FP32 using ONNX Runtime 1.23.2 BASIC
optimization, while other decoder matrix weights remain INT8. The optimized
decoder replaces the raw/hoisted decoder in the APK. Both graph assets are stored
uncompressed and loaded by memory mapping; no first-run graph optimization or
standalone decoder cache is needed on the phone. The original OPUS-MT tiny files
and LiteRT implementation are retained separately.

Generation: Chinese source language (`128102`), forced English first decoder token
(`128022`), EOS/start token (`2`), greedy decoding, cached attention. The original
Hugging Face configuration defaults to 5 beams; this mobile trial uses one beam.

`tokenizer-map.bin` is generated deterministically from the pinned SentencePiece
model and `vocab.json`, using `scripts/prepare-m2m-tokenizer.py`. Its big-endian
header contains magic `M2M2`, SentencePiece size, model vocabulary size, base
vocabulary size, source language ID and target language ID, followed by the
forward Int32 ID array and length-prefixed UTF-8 output vocabulary pieces.
SentencePiece IDs are not M2M100 model IDs. Output pieces are decoded directly:
394 HF vocabulary entries (including PAD) are absent from the SPM vocabulary,
so an inverse ID mapping would lose information.
