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

Requirements: macOS, Xcode, XcodeGen, JDK 17 and prepared M2M/OPUS/libime/extras data.
Use `iosApp/project.yml` to generate the Xcode project and
`scripts/build-ios-native.sh` to build the native libraries.
Configure your own development team in Xcode for physical devices.
The iOS build copies English/OpenCC data and the Zinnia handwriting model;
the Android Vosk microphone pipeline is not available to iOS keyboard extensions.

For simulator testing, select your full Xcode developer directory and JDK first:

```sh
export DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer
export JAVA_HOME=/path/to/jdk
xcodebuild -downloadPlatform iOS -architectureVariant arm64
```

Generate and build the app and UI tests:

```sh
# First prepare Android M2M resources using the commands above, then serialize
# the same graphs for the exact iOS runtime in a separate Python environment.
python3 -m venv /tmp/learnime-ios-model-tools
/tmp/learnime-ios-model-tools/bin/pip install onnxruntime==1.23.0
/tmp/learnime-ios-model-tools/bin/python scripts/prepare-ios-m2m.py
bash scripts/prepare-ios-extras.sh
bash scripts/setup-ios-ort.sh
xcodegen generate --spec iosApp/project.yml
xcodebuild -project iosApp/LearnIME.xcodeproj -scheme LearnIME \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/learnime-ios-simulator-build \
  CODE_SIGNING_ALLOWED=NO build-for-testing
python3 scripts/prepare-ios-simulator.py --test
```

The script creates/reuses `LearnIME Test iPhone`, boots it, installs the app and
runs real keyboard UI tests. Simulator settings use English system labels;
the test enables 对照英语 in Settings and switches to it in the practice editor.
No physical iPhone or development signing is required. `--wait` waits for an
already running runtime download; it does not start another download.

M2M100 is bundled once in the containing app and is the default backend.
`LearnIMEM2M` wraps the pinned ORT static library in a dynamic framework, exposing
only the C adapter symbols so ORT and LiteRT do not collide on XNNPACK symbols.
The generated model manifest records source and ORT SHA256 hashes.
If the CocoaPods archive endpoint is unavailable, `setup-ios-ort.sh` uses the
Apple XCFramework from Microsoft's pinned NuGet 1.23.0 package and verifies its
SHA256 checksum.

To compile an unsigned physical-device app without provisioning profiles:

```sh
xcodebuild -project iosApp/LearnIME.xcodeproj -scheme LearnIME \
  -configuration Debug -destination 'generic/platform=iOS' \
  -derivedDataPath /tmp/learnime-ios-device-build \
  CODE_SIGNING_ALLOWED=NO build
```

Output: `/tmp/learnime-ios-device-build/Build/Products/Debug-iphoneos/LearnIME.app`.
Physical-device installation requires development signing and a valid
provisioning profile for the app and keyboard extension.

The translation integration probe uses the production Swift engine, shared beam
search and native tokenizer without XCTest. It checks both directions, in-flight
cancellation, newest-request completion and unloading/reloading. After building,
substitute the simulator UDID:

```sh
bash scripts/verify-ios-translation.sh \
  /tmp/learnime-ios-simulator-build/Build/Products/Debug-iphonesimulator \
  <simulator-udid> default
```

Use `cpu` to verify the retained OPUS backend, or `gpu` to reproduce its Metal
experiment. The keyboard's
“Metal GPU 翻译（实验）” setting is off by default. LiteRT and its accelerator
are pinned to 2.3.0 with checksums and embedded in the containing iOS app.
GPU initialization/execution failures do not produce a success label.
The current simulator model fails Metal graph compilation; CPU translation
remains available. Simulator timing and footprint do not establish physical
iPhone performance or compliance with the keyboard extension's memory limit.

Original app artwork can be regenerated with Pillow:

```sh
python3 scripts/generate-app-icons.py
```

## Source changes and licenses

See `THIRD_PARTY_NOTICES.md` and `third_party/LIBIME-SOURCES.md` for upstream
revisions, local modifications and component licenses. SentencePiece is included
at its pinned revision with the Android patch already applied; the setup script
accepts that snapshot without downloading another Git checkout.
