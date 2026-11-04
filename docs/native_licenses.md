# Native dependency licensing and corresponding source

This record covers the native code distributed with Natsuko. The Apache 2.0
license for Natsuko's own code does not replace any third-party license.

## FFmpeg and dav1d

The `arm64-v8a` and `x86_64` executables contain FFmpeg **9.0.1** and dav1d
**1.5.4**, linked statically. They run as separate child processes; Natsuko's
Kotlin application does not link to the FFmpeg libraries. Both retained
configure reports identify **LGPL version 2.1 or later**. GPL, nonfree and
version-3-only components are disabled. Their Android system dependencies are
`libc.so`, `libm.so` and `libz.so`.

The two unmodified source archives, their identities and official download URLs
are recorded in `third_party/ffmpeg/provenance.json`. The existing
`FFmpeg-LGPL-2.1.txt`, `FFmpeg-LICENSE.md` and `dav1d-BSD-2-Clause.txt` notices
match the corresponding archive files byte for byte.

The [FFmpeg license information](https://ffmpeg.org/legal.html) asks distributors
to provide corresponding source, build details and visible attribution. LGPL
2.1 sections 4 and 6 describe distribution of object code and linked executables;
the source-distribution route is used here. See the exact terms in the bundled
`FFmpeg-LGPL-2.1.txt` or the [upstream LGPL 2.1 text](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html).

### Additional notices

The app includes the following files from `third_party/ffmpeg/notices/`:

- `FFmpeg-NOTICE.txt`: dependency identity, source availability and attribution.
- `FFmpeg-LGPL-2.1.txt` and `FFmpeg-LICENSE.md`: upstream FFmpeg license texts.
- `FFmpeg-THIRD-PARTY.txt`: additional permissive notices from the FFmpeg source
  dependencies of both shipped ABIs, including the Independent JPEG Group,
  ISC-licensed x86 assembly helpers, MIT/musl and BSD code. Conditional headers
  can include code not selected by the final build. This inventory does not
  indicate that additional optional encoders were enabled.
- `dav1d-BSD-2-Clause.txt` and `dav1d-THIRD-PARTY.txt`: dav1d's root license plus
  individual runtime source notices, including Two Orioles and other authors.
  Runtime sources for all architectures are covered conservatively.
- `LLVM-NOTICE.txt`: the unmodified notice from the NDK r28c LLVM toolchain.
- `Android-NDK-SYSROOT-NOTICE.txt`: the unmodified NDK r28c sysroot notice,
  retaining notices for startup objects and code originating in system headers.
  The notice is an upstream aggregate and does not claim that every listed
  package is included in these executables. Android's system shared libraries
  themselves are provided by the device.

The LLVM notice includes [Apache 2.0 with LLVM exceptions](https://llvm.org/LICENSE.txt)
and the other notices supplied with that toolchain. Build tools such as Meson,
Ninja, NASM, make and the NDK are build prerequisites, not applications downloaded
or redistributed to users by Natsuko.

The Independent JPEG Group credit is required by the actual compiled
`jfdctfst.c`, `jfdctint_template.c` and `jrevdct.c` files:

> This software is based in part on the work of the Independent JPEG Group.

Natsuko has not changed these upstream files. Their original source comments
retain the descriptions of changes made upstream in FFmpeg.

## Release source package

For every distributed APK or AAB, retain the corresponding release revision and
publish its native source archive alongside the binary at the same download
location. Include at least:

- `third_party/ffmpeg/sources/`: both full source archives, checksums and the
  FFmpeg signature/signing-key material;
- `third_party/ffmpeg/build-config/`, `provenance.json`, `SHA256SUMS`, `README.md`
  and the entire `notices/` directory;
- `scripts/build-ffmpeg.sh`, `scripts/verify-ffmpeg.py` and this document;
- the project license and release attribution notices.

An upstream project URL alone is not a substitute for delivering the exact
corresponding source. Keep the native source archive available with the binary
and include a visible link in the download description. The local packaging
checks cannot establish that a publisher actually uploaded and continues to
host these files.

## Rebuilding and replacing the native executables

1. Extract the source package without changing its directory layout. Install
   Android NDK **r28c (28.2.13676358)** and the build prerequisites listed in
   `third_party/ffmpeg/README.md`.
2. Run `ANDROID_NDK_HOME=/absolute/path/to/android-ndk-r28c ./scripts/build-ffmpeg.sh`
   from the package root. The script validates and extracts the bundled archives.
   No downloading is performed by that script.
3. To make your own changes, edit the extracted sources under
   `build/ffmpeg/src/ffmpeg-9.0.1/` or `build/ffmpeg/src/dav1d-1.5.4/` and rerun the
   build script using the same build directory. Existing extracted sources are
   reused. Preserve your changes and notices with any binaries you redistribute.
4. The rebuilt executables are written to `third_party/ffmpeg/jniLibs/`, and their
   checksums are regenerated. Run `python3 scripts/verify-ffmpeg.py` after both
   ABIs are present. This checks artifact integrity, not equivalence to the
   originally distributed binaries after source changes.
5. To use modified executables in Natsuko, obtain the complete Natsuko source for
   the corresponding release, place the rebuilt `jniLibs/` and checksum/build
   metadata in its `third_party/ffmpeg/` directory, and build an APK using that
   project's Android build instructions. Sign it with a key you control. Android
   requires matching signing certificates for an in-place update; a differently
   signed APK cannot replace the installed release through that update path.

The app imposes no separate license prohibition on modifying or reverse
engineering these LGPL components. The source package contains all FFmpeg and
dav1d sources needed for rebuilding the standalone executables; application
objects are not statically linked into them. The Android toolchain and normal
system libraries remain separately available build/platform prerequisites.

## AndroidX Graphics Path

`androidx.graphics:graphics-path:1.0.1` includes
`libandroidx.graphics.path.so`. The native sources at the revision linked by the
[official 1.0.1 release notes](https://developer.android.com/jetpack/androidx/releases/graphics#1.0.1)
(`8a05a22af450d589ef911d772a001a49dcb05b71`) carry Apache 2.0 notices for the
Android Open Source Project. Their CMake target builds `Conic.cpp`,
`PathIterator.cpp` and `pathway.cpp`, with included math helpers. They query the
system's path implementation; the artifact does not bundle a full Skia engine.

`third_party/licenses/AndroidX-Graphics-Path-NOTICE.txt` retains the original
native source notices, including the math helpers. The published AAR checked
for this inventory has SHA256
`8ca4032b6d79b351f0b59ad4b580eddbb9423e1652f7c958830687f1eee2ec03`.
Both Natsuko ABIs refer to Android's `libc.so`, `libm.so` and `libdl.so`.

This inventory addresses software copyright notices and source redistribution.
It does not establish rights to unrelated artwork or codec patents. Any such
rights must be assessed separately for the intended distribution.
