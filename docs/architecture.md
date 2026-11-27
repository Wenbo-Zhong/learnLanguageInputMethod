# Source architecture

The shared module owns deterministic input rules. Android and iOS own editor
access, native scheduling, asset IO and platform presentation. Keep business
state, layout and skin separate when adding a feature.

| Concern | Shared source directory | Android adapter directory | iOS adapter directory |
| --- | --- | --- | --- |
| Composition and candidate identity | `shared/.../input/` | `android/.../keyboard/` | `Keyboard/Input/` |
| Dictionary and text conversion | `shared/.../dictionary/` | `android/.../storage/` | `Keyboard/Storage/` |
| Handwriting state | `shared/.../handwriting/` | `android/.../handwriting/` | `Keyboard/Handwriting/` |
| Translation and model contracts | `shared/.../translation/` | `android/.../translation/` | `Keyboard/Translation/` |
| Settings definitions | `shared/.../settings/` | `KeyboardSettingsActivity.kt` | Native settings panels |
| Native key layout and skin | — | `android/.../keyboard/` | `Keyboard/Presentation/` |
| Pinyin native bridge | — | `android/.../pinyin/` | `Keyboard/Pinyin/` |
| Voice input | — | `android/.../voice/` | — |

Here `shared/.../` means
`shared/src/commonMain/kotlin/dev/learnime/shared/`, and `android/.../` means
`androidApp/src/main/java/dev/learnime/android/`. Swift paths are relative to
`iosApp/`.

Kotlin packages remain `dev.learnime.shared` and `dev.learnime.android`.
Directory grouping does not change the JNI names, Android manifest entry
classes or the exported Shared framework API. Swift files remain in the
keyboard extension module. Gradle and XcodeGen discover the nested source
directories recursively.

## State and asynchronous work

`KeyboardInputFlow` is the shared input algorithm used by both controllers.
It handles typing, backspace, one-shot Shift and caps lock, capitalization,
SPACE/ENTER/LITERAL boundaries, English finishing and correction application,
double-space punctuation, candidate selection, raw commits, mode changes and
tool-opening boundaries. Controllers pass host context, monotonic time and
`InputOptions`; the flow returns `InputTransition`.

`InputTransition.effects` describes native work: insert or convert-and-insert
text, delete a character, perform the host return action, synchronize marked
text, schedule a lookup, render candidates or keys, or open a native panel.
`InputTransition.selection` describes a native candidate acknowledgement.
Call `canSelect()` before native selection and `completeSelection()` afterward.
The shared flow commits and consumes the candidate only if its original
request remains current. A mode change or panel opening attached to a selection
occurs only when that selection exhausts the composition.

Ordinary typing updates the candidate presentation without rebuilding the
native key layout. The flow requests key rendering for mode and Shift changes;
`capitalize()` reports whether uppercase actually changed.

`beginCandidates()` chooses pinyin lookup, prediction or no lookup, and applies
the same mixed/shortcut conditions on both platforms. `publishCandidates()`
checks lookup identity before applying native results. `presentation()` supplies
candidate lists, preedit content and a snapshot for rendered buttons; native
font, underline, scrolling, geometry and skin remain platform-specific.

`CandidatePipeline` executes that lookup plan through `CandidateBackend`.
It owns loading/error state, merges shortcuts and mixed suggestions, rejects
late callbacks, and releases pending input boundaries. `whenReady()` waits for
the native pinyin result; supplemental dictionary work can complete separately.
Failure releases the boundary while preserving the composition, and invalidation
releases it with `false`. Every waiter is checked again before delivery so a
reentrant action cannot approve later waiters from an old revision. A new lookup
clears previous native candidate objects before exposing a new request.

`AndroidCandidateBackend` supplies cancellable coroutine jobs, pinyin calls and
resource preparation. `IOSCandidateBackend` supplies native engine/Dispatch
calls and guards its own cancellation epoch. All completions arrive on the
editor thread. The controllers no longer publish lookup results themselves:
Android adapts readiness to a suspended coroutine, and iOS pauses its edit queue
until the shared callback. Host changes cancel the native edit queue before
resetting input and invalidating the candidate pipeline.

`KeyboardInputState` owns the existing `KeyboardSession` and
`CandidateCoordinator`, English preedit and supplemental candidate lists.
Use `snapshot()` before native lookup and `publishChoices()` or
`publishPredictions()` when it completes. Capture the snapshot when rendering
a supplemental candidate button and call `canChoose()` when its queued action
executes. A request includes composition revision, mode, candidate generation
and English edit revision. Editing a word and then returning to the same text
does not make an old request valid again.

`CandidateCoordinator` continues to own pinyin candidate identity and
SPACE/ENTER/LITERAL boundary rules. Native selection is checked against the
request before and after its asynchronous completion. Only confirmed text is
committed to the host editor.

`AndroidEditQueue` serializes suspended edits and cancels the whole editor job
on a field change. `IOSEditQueue` drains native callbacks in order and pauses
while selection is pending. Its owner resets the queue and invalidates
candidate requests at host-context and lifecycle boundaries. Scheduling remains
native; coroutine jobs and UIKit closures are not shared business state.
`IOSEditQueue.epoch` also identifies the editor lifetime for suspended English
correction and selection callbacks. Old callbacks cannot release a newer
editor's queue.

`InputPreeditBuilder` maps the candidate's syllable spelling to raw encoding
offsets and supplies locked-prefix length and next-syllable text slices.
Android uses the cursor/underline metadata; iOS retains its plain nine-key
preedit style. Rendered lock, unlock and preedit-tap actions carry the original
`SupplementalRequest` and go through the shared flow. A later edit or lookup
invalidates those actions.

The shared flow also chooses composition-cursor movement versus host movement.
Native hosts report successful insertions with `recordCommit()` and an
`EditorSnapshot`; `KeyboardEditorState` owns the undo anchor and deletion
decisions. Android supplies its selection position; iOS supplies its visible
host context. Both supply whether a selection is collapsed. `DELETE_TEXT`
contains the exact suffix to remove: Android counts Unicode code points,
while iOS invokes `deleteBackward()` for the suffix's native characters.

`WordBoundary` scans text without Unicode-property regexes, which are not
compatible across JVM and Kotlin/Native. It keeps combining marks, common
emoji sequences and regional-indicator pairs attached to their base, and
classifies supplementary letters/numbers with JDK or Foundation Unicode
tables. These small adapters live in `androidMain`, `jvmMain` and `appleMain`;
segmentation and edit decisions remain common Kotlin. The shared Gradle
module explicitly applies the default hierarchy so Apple targets consume
`appleMain`.

## Presentation and translation

`AndroidKeyFactory` and `IOSKeyFactory` own native styling, feedback and held-key
gestures. They receive editing callbacks. `AndroidKeyboardLayout`,
`IOSKeyboardLayout`, `MaterialUi` and `IOSKeyboardSkin` retain platform
geometry and appearance. The keyboard entry classes still compose layouts,
route editor events and coordinate tools.
They execute shared transitions rather than implementing separate keyboard
algorithms. Android maps `HOST_ENTER` to `EditorInfo` actions or newline; iOS
maps it to insertion through `textDocumentProxy`. Both retain their native
handwriting recognizer and form/editor adapters.

`IOSTranslationPresenter` owns the translation card and its debounce lifecycle.
It uses the shared `TranslationPolicy`; Android continues to use
`TranslationController`. `OpusModelSpec` and `M2mModelSpec` are the source of
model token limits, vocabulary identities and decoder constants used by both
adapters. Native tensor allocation, attention caches and runtime calls remain
platform code.

The OPUS encoder reserves one token for EOS and rejects more than 255 source
tokens before inference. M2M100 reserves language and EOS tokens. Preserve the
full SentencePiece token count when validating a bounded native output buffer.

`native/libime_pinyin.cpp` is the configured pinyin implementation on both
platforms. The unused older `native/pinyin.cpp` implementation was removed.
Native public C headers and JNI symbol names remain stable.

## Adding and validating changes

Place shared input rules in the relevant feature directory and exercise them
in `shared/src/commonTest/kotlin/dev/learnime/shared/`. Add platform code when
it touches an editor, native memory, OS callbacks or appearance. Avoid new
platform copies of rules already implemented in Shared.

Build instructions remain in [building.md](building.md). For an input-state
change, run shared tests and compile both apps. Exercise delayed candidate
selection followed by Enter, host-field switches during suspended edits, old
candidate buttons after edits, and token overflow before inference. Final UI
geometry and keyboard-extension memory usage require device validation.
