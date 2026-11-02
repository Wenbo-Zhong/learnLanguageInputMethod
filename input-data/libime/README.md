# libime input resources

The three data files total approximately 40 MiB. They are loaded from the same
directory; libime discovers the prediction trie alongside `sc.lm`.

`SHA256SUMS` contains hashes of the generated files. Dictionary and language-model
source archive hashes, versions, licenses and the portable build patches are
listed in `notices/SOURCES.md` and `third_party/LIBIME-SOURCES.md`.

Android copies these files to `noBackupFilesDir/libime-20260907`, then opens the
decoder with a separate `libime-user-v1.dat`. Legacy AOSP learning files remain
untouched and are not interpreted as libime data. iOS loads the bundled data
folder and stores its user model in the extension's Application Support directory.

The user file wraps the upstream compressed history and dictionary in separate
length-delimited sections, so their buffered readers cannot consume each other's
bytes. Only an explicit selection with learning enabled writes this file, using
a temporary file and atomic rename.

The LGPL source and local modifications are included in the workspace.
The portable CMake build can rebuild each static archive independently; retain
the corresponding application sources/objects and build scripts when distributing
a binary so recipients can rebuild or relink the modified library.
