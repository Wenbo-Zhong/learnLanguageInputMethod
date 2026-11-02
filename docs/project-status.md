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

iOS retains its existing Pinyin and OPUS translation implementation. The new
Android settings and extra panels are not yet implemented on iOS.

The project is a working prototype. Validation includes Android builds, Lint,
shared JVM checks and native input-contract checks. Personal device screenshots
and local validation artifacts are intentionally excluded from the repository.
