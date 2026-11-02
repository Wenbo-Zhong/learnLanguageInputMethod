# Build from source

The modified native dependencies are included as source snapshots under
`third_party/`. Model and dictionary weights are excluded from Git.

## Android

Requirements: JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22+,
Ninja, Python 3, curl, and tar with Zstandard support. Android currently builds
arm64-v8a only.

Configure the Android SDK through Android Studio or an untracked
`local.properties` file. Prepare input resources:

```sh
python3 scripts/prepare-ime-extras.py
python3 scripts/prepare-vosk-model.py
bash scripts/prepare-libime-data.sh
```

Prepare the translation models:

```sh
python3 -m venv /tmp/learnime-model-tools
/tmp/learnime-model-tools/bin/python -m pip install sentencepiece onnx onnxruntime==1.23.2
PYTHON=/tmp/learnime-model-tools/bin/python bash scripts/download-m2m-model.sh
bash scripts/download-opus-model.sh
```

These downloads are large. The optimized M2M100 graph is generated on the
computer, then bundled with the OPUS model and input resources:

```sh
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:lintDebug :shared:jvmTest
bash scripts/verify-libime.sh
```

Output: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
The current debug APK is approximately 1.25 GiB. APKs are not tracked in Git.
Release signing requires your own signing configuration; no keys are supplied.

## iOS

Requirements: macOS, Xcode, XcodeGen, JDK 17 and the prepared OPUS/libime data.
Use `iosApp/project.yml` to generate the Xcode project and
`scripts/build-ios-native.sh` to build the native libraries.
Configure your own development team in Xcode. Android extras such as the new
handwriting and speech panels have not been ported to iOS.

## Source changes and licenses

See `THIRD_PARTY_NOTICES.md` and `third_party/LIBIME-SOURCES.md` for upstream
revisions, local modifications and component licenses. SentencePiece is included
at its pinned revision with the Android patch already applied; the setup script
accepts that snapshot without downloading another Git checkout.
