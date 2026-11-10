# Project status

LearnIME is a Kotlin Multiplatform input method for practicing languages while
typing. Committed text goes to the host app; translations stay in the keyboard.

Android provides Chinese Pinyin QWERTY and nine-key input, English suggestions,
mixed text entry, user dictionaries, configurable correction and fuzzy sounds,
clipboard text history, editing tools, symbols, basic emoji, traditional Chinese
conversion, layout settings, single-character handwriting and offline Chinese
speech recognition.

Examples: `nihao` → `你好`, `woxianghuijia` → `我想回家`,
English `I want to go home.` → Chinese `我想回家。`.

Input and inference run locally. Password and no-personalized-learning fields
disable translation, learning and applicable input tools.

Handwriting uses Zinnia/Tegaki. It supports writing characters one after another,
not automatic segmentation of several characters written side by side. Candidate
accuracy is limited by the model. Speech uses the small Vosk Chinese model;
microphone accuracy across accents and noisy environments still needs testing.
English spelling correction uses frequency data rather than a contextual grammar
model. GIFs, stickers, swipe decoding and image clipboard history are not provided.

iOS provides the same Pinyin, dictionary, English correction, clipboard text,
symbols and continuous single-character handwriting tools. It uses UIKit,
semantic system colors and SF Symbols; Android uses Material components.
iOS keyboard extensions cannot record microphone audio. Both platforms bundle
M2M100 and enable it by default for Chinese/English translation, with OPUS
Chinese-to-English selectable in settings. iOS uses the official ORT 1.23.0
runtime and serialized graphs with file-backed initializer mappings.

The Kotlin shared layer owns Pinyin sessions, English spelling/ranking,
traditional conversion, candidate validation, clipboard retention, symbol
catalogs, logical keymaps, M2M100 beam search and input policies. Native adapters own resource IO,
storage, editor APIs, permissions and scheduling. `KeyboardLayout` modules own
platform geometry; `KeyboardSkin.swift` and `MaterialUi.kt` own platform styling.
Panel construction and some input orchestration still live in the platform
controllers; this separation is incremental.

iOS “中/EN” switches directly between English and the last Chinese layout.
The toolbar switches nine-key/QWERTY directly; handwriting has its own entry.
Nine-key letter groups use larger native-style keycaps with quieter punctuation
and utility keys. All iOS layouts retain UIKit's keyboard backdrop so their
background follows the system switch/dictation bar in light and dark appearance.
The handwriting surface is transparent; key and text colors adapt dynamically.
Composition and syllable rows appear only when needed, and clipboard access
lives in the clipboard panel. A globe is supplied by the extension
only when `needsInputModeSwitchKey` requires it; otherwise iOS provides the
system switch. Both apps have launcher artwork, with Android adaptive resources.

iOS OPUS has an opt-in Metal translation experiment using LiteRT 2.3.0
CompiledModel and its matching Metal accelerator. CPU remains the default.
Simulator probes produced valid CPU translations for three complete sentences.
Full-model Metal compilation failed on encoder tensor layouts; an isolated
decoder experiment ran GPU operators but produced NaN logits, including with
FP32 configured. That unsuccessful model rewrite was removed. GPU translation
is not validated for use; physical-device performance has not been measured.
Empty, non-finite and unfinished decoding results are reported as failures.

M2M100 simulator probes verified both directions, cancellation of an active
request and session unload/reload. Three short warm translations took around
0.9–1.0 seconds; cold initialization plus decoding took around 2.5 seconds.
The standalone probe peaked near 50 MiB physical footprint. The full Debug
keyboard reported approximately 148–150 MiB including its input tools.
The real simulator keyboard displayed the default M2M100 selection and a valid
translation. Unsigned iPhone targets compile; physical-device translation,
timing and extension memory limits still require on-device validation.

The project is a working prototype. Validation includes Android builds, Lint,
shared JVM checks, native input-contract checks and iOS keyboard UI tests.
See `building.md` for the dedicated simulator workflow. Personal device screenshots
and local validation artifacts are intentionally excluded from the repository.
