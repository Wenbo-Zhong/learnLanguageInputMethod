#!/usr/bin/env python3
"""Build the official input ID mapping and exact UTF-8 output vocabulary."""
import json
import struct
import sys
from pathlib import Path

import sentencepiece

root = Path(sys.argv[1])
vocab = json.loads((root / "vocab.json").read_text())
config = json.loads((root / "config.json").read_text())
languages = json.loads((root / "tokenizer_config.json").read_text())["additional_special_tokens"]
sp = sentencepiece.SentencePieceProcessor(model_file=str(root / "sentencepiece.bpe.model"))
forward = [vocab.get(sp.id_to_piece(i), vocab["<unk>"]) for i in range(sp.get_piece_size())]
pieces = [b""] * len(vocab)
for piece, model_id in vocab.items():
    pieces[model_id] = piece.encode("utf-8")
zh = len(vocab) + languages.index("__zh__")
en = len(vocab) + languages.index("__en__")
assert (zh, en) == (128102, 128022)
assert vocab["</s>"] == 2 and vocab["<unk>"] == 3
values = [0x4D324D32, len(forward), config["vocab_size"], len(vocab), zh, en] + forward
target = root / "tokenizer-map.bin"
temporary = target.with_suffix(".tmp")
with temporary.open("wb") as output:
    output.write(struct.pack(f">{len(values)}i", *values))
    for piece in pieces:
        output.write(struct.pack(">i", len(piece)))
        output.write(piece)
temporary.replace(target)
print(f"Tokenizer: {len(forward)} SentencePiece IDs, {len(pieces)} exact output pieces, zh={zh}, en={en}")
