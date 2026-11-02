# Pinned native input engine sources

The application builds the original libime decoder and its dependencies statically
for Android and iOS. The source archives were obtained from the upstream projects.

| Component | Version / commit | Archive SHA-256 |
|---|---|---|
| libime | `6fec3054d8a65fc8a6942a467a42f2c0dac20eb8` | `e46dbedf981aebcb4166a3e69ff63899dc0166589eecf5b22e02283bb8897860` |
| KenLM | `4cb443e60b7bf2c0ddf3c745378f76cb59e254e5` | `e9980dfd7ff1f9c15adb881aade114bad4297afa67680dcf6e74c612e176e1a2` |
| Fcitx5 utilities | 5.1.20, tag object `4f496a2f0c28c7c2cb03e4cb7967241426b8d071` | `bbbe0339b4fa2658ac801b72f3904d1cd6b8db52f337fbb3fcad5aa031cd89d6` |
| Boost | 1.88.0 | `46d9d2c06637b219270877c9e16155cbd015b6dc84349af064c088e9b5b12f7b` |
| Zstandard | 1.5.7 | `9b70bbc1f5750dd895765e376e78811f3c47065cc1c782c09f4bcea5d137c7d0` |

Upstream licenses are retained in each source directory. libime and the selected
Fcitx utilities use LGPL-2.1-or-later; Boost uses the Boost Software License; KenLM
and Zstandard retain their own license files.

Local changes:

- `native/libime.cmake`: build only portable input-engine dependencies, without
  desktop frontends, D-Bus or event-loop services.
- `fcitx5/src/lib/fcitx-utils/log.cpp`: buffered, mutex-protected log emission
  because Apple libc++ does not provide `std::osyncstream`; portable timestamps.
- `fcitx5/src/lib/fcitx-utils/{misc,semver}.cpp`: replace `std::format` to
  support iOS 16.0.
- `libime/src/libime/core/kenlm/util/usage.cc`: use libproc only on macOS,
  keep Mach task accounting available on iOS.
- `libime/src/libime/core/kenlm/util/bit_packing.hh`: include arm64 in
  the upstream memcpy path for packed, unaligned language-model fields.
- `libime/src/libime/core/kenlm/lm/model.cc`: iterate backoff indexes
  without forming an out-of-bounds intermediate pointer.
- `libime/src/libime/pinyin/ninekeyencoder.{h,cpp}` and one dictionary dispatch:
  allow numeric syllables and locked literal prefixes to share the original
  libime word graph and language-model decoder; queue each DAG node once.

The matching data release is pinned by libime's `data/CMakeLists.txt`:

- `lm_sc.arpa-20260629.tar.zst`, SHA-256
  `06808333b9173e5374cf2cb5afc12d08f5625bf9abb536489cac376fc05f2e7f`.
- `dict-20260907.tar.zst`, SHA-256
  `fb75a179065e690dfc4559ce1807cbaf4fbe4f0111a5005615be9f435e6b9d76`.

These data packages are declared LGPL-2.1-or-later in upstream `REUSE.toml`.
