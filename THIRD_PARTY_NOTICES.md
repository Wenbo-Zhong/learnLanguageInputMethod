# Third-party code, data and models

Original LearnIME application code is licensed under GPL-3.0-or-later.
Bundled upstream code, model weights and dictionaries retain their own licenses.
The top-level license does not replace the licenses listed below.

| Component | License | Source / retained notice |
|---|---|---|
| libime and selected Fcitx5 utilities | LGPL-2.1-or-later | `third_party/LIBIME-SOURCES.md`; licenses in the respective source directories |
| KenLM | LGPL-2.1-or-later with separately licensed utility files | `third_party/libime/src/libime/core/kenlm/LICENSE` |
| Boost 1.88.0 | BSL-1.0 | `third_party/boost/LICENSE_1_0.txt` |
| Zstandard 1.5.7 | BSD-3-Clause / GPL-2.0 alternative | `third_party/zstd/LICENSE`, `COPYING` |
| SentencePiece | Apache-2.0 | `third_party/sentencepiece/LICENSE`; pinned revision in `LEARNIME-REVISION`, local patch in `native/patches/` |
| Zinnia 0.06 | BSD-3-Clause | `third_party/zinnia/COPYING` |
| libime dictionary and language model | LGPL-2.1-or-later | `input-data/libime/LICENSE.txt`, `SHA256SUMS`, `notices/` |
| SymSpellPy English frequency dictionary | MIT | `input-data/extras/SOURCES.json`, `symspellpy-6.10.1-LICENSE.txt` |
| OpenCC Python character and phrase data | Apache-2.0 | `input-data/extras/SOURCES.json`, `opencc-0.1.7-LICENSE.txt` |
| Tegaki Chinese handwriting model | LGPL-2.1 | `input-data/extras/SOURCES.json`, `tegaki-COPYING` |
| Vosk Android / small-cn-0.22 | Apache-2.0 | Maven `com.alphacephei:vosk-android:0.3.75`; `input-data/extras/VOSK-SOURCE.json`, `vosk-Apache-2.0.txt` |
| M2M100 / Xenova ONNX conversion | MIT | `models/m2m100-418m-int8/MODEL-SOURCE.md`, pinned download script and checksums |
| OPUS-MT tiny Chinese-English model | Apache-2.0 | `scripts/download-opus-model.sh`, `models/opus-mt-tiny-zho-eng/SHA256SUMS` |
| ONNX Runtime | MIT | Android Maven `com.microsoft.onnxruntime:onnxruntime-android:1.23.2`; iOS official 1.23.0 archive pinned in `scripts/setup-ios-ort.sh`, `iosApp/Resources/LICENSE-ONNXRuntime.txt` |
| LiteRT / TensorFlow Lite | Apache-2.0 | Maven dependency; `iosApp/Packages/LiteRTSwift/LICENSE`, `iosApp/Resources/LICENSE-LiteRT.txt` |
| AndroidX / Material Components / Kotlin / coroutines | Apache-2.0 | Gradle dependency coordinates in the build scripts |

Modified libime, Fcitx, KenLM and SentencePiece sources are included in this
repository so downstream builds can use the same source. Upstream copyright
headers and license files must be retained when redistributing them.
Nested upstream Git databases are omitted.

Large model and dictionary files are downloaded during build preparation rather
than stored in Git. Their download scripts and recorded checksums are included.
If distributing an APK with LGPL components, provide the corresponding source
and the materials necessary to rebuild/relink the supplied version, and retain
the component notices. This source repository is not a claim that any arbitrary
binary redistribution automatically satisfies all license requirements.
