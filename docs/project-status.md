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
traditional conversion, clipboard retention, symbol catalogs, logical keymaps,
M2M100 beam search and input policies. `CandidateCoordinator` captures candidate
request identity and composition boundary rules. `HandwritingSession` separates
recognized candidates from text marked in the editor. `TranslationPolicy`
handles sentence selection, model capabilities, direction, debounce and request
identity. `KeyboardSettings` defines persistent setting keys, defaults, choices
and ranges, including platform differences.

`LearningKeyboardService.kt` and `KeyboardViewController.swift` own editor writes
and serialize user actions across asynchronous selection or recognition.
`AndroidHandwritingInput` and `IOSHandwritingInput` own stroke views and native
recognition scheduling; a delimiter waits for recognition independently of the
automatic marking delay. `AndroidVoiceInput` owns recording preparation and
Vosk resources. `AndroidKeyboardPanels` and `IOSKeyboardPanels` render clipboard,
symbols and editing tools; editor commands return to the controllers.
`AndroidCandidatePanel` and `IOSCandidatePanel` render the candidate strip and
expanded grid. Dictionary forms retain platform-specific editing behavior.
`KeyboardLayout` modules own platform geometry; `KeyboardSkin.swift` and
`MaterialUi.kt` own platform styling.

The 2026-11-26 maintainability pass reduced
`androidApp/src/main/java/dev/learnime/android/LearningKeyboardService.kt` from
1,530 to 1,254 physical lines and
`iosApp/Keyboard/KeyboardViewController.swift` from 1,062 to 898. Counts include
comments and blank lines. Across the same source snapshot, the extracted
components, shared policies and tests added a net 565 lines. The objective was
clearer ownership and shared behavior rather than a required reduction ratio.

Validation for that pass: 27 shared JVM tests passed; Android Kotlin and the
iOS simulator shared framework compiled; UIKit controllers and panels passed
Swift typechecking against the generated shared framework with a translation
engine stub. Extracted-method probes verified queued candidate selection,
recognition boundaries, no-result stroke retention, request invalidation and
recording cancellation/resource release. The local generated Xcode project
includes the new Swift files; `project.yml` includes the entire `Keyboard`
directory when regenerating. Full iOS packaging and device UI validation were
not repeated because the local iOS runtime/model asset directories are absent.

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
