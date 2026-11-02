# Chinese input data

The production Android and iOS keyboards load `libime/sc.dict`, `sc.lm` and
`sc.lm.predict`. See `libime/SHA256SUMS` and `third_party/LIBIME-SOURCES.md`
for exact source releases, hashes and licenses.

Run `TMPDIR=/tmp bash scripts/prepare-libime-data.sh` to reproduce the data
from the pinned upstream dictionary and ARPA model. The portable host tools
generate the same little-endian data consumed on arm64 Android and iOS.

Nine-key ambiguity is represented inside the libime syllable/word graph.
QWERTY uses libime's spelling parser and correction profile. The adapters retain
original key ranges, full and initial locks, candidate generations, explicit
selection and an editor-controlled learning flag.

Android 0.6.0 also loads `extras/english-frequency.txt` (SymSpellPy),
`STCharacters.txt` and `STPhrases.txt` (OpenCC), `handwriting-zh_CN.model`
(Tegaki/Zinnia), and `vosk-cn` (Vosk small Chinese model).
Run `python3 scripts/prepare-ime-extras.py` and
`python3 scripts/prepare-vosk-model.py` to reproduce them.
Pinned URLs and SHA-256 values are in `extras/SOURCES.json` and
`extras/VOSK-SOURCE.json`; full licenses and `NOTICE.txt` are copied into
the Android APK. These extras are currently Android-only.
