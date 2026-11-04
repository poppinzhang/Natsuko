# Embedded FFmpeg

Natsuko ships its own FFmpeg 9.0.1 and FFprobe executables for `arm64-v8a` and
`x86_64`. They are built from the unmodified official source archives in `sources/`.
The application executes the binaries installed by Android in `nativeLibraryDir`.
Their `.so` names allow Android to package and extract them; they are PIE
executables, not JNI libraries. No runtime download, system FFmpeg installation,
or Android NDK is required on the user's device. FFmpeg network support is disabled.

## Included capabilities

- MP4/MOV, Matroska/MKV, AVI, IVF and image input.
- Software H.264, H.265/HEVC, AV1 (dav1d), VP8, VP9, MPEG-4 and MJPEG decoding.
- GIF and PNG output; GIF palette generation, dithering and palette application.
- Frame sampling, scaling, timestamp control and display rotation.
- FFprobe metadata, JSON output and progress reporting from the FFmpeg CLI.

FFmpeg and dav1d are linked statically into each executable. The only shared
dependencies are Android's `libc.so`, `libm.so` and `libz.so`. The binaries have
16 KB ELF load-segment alignment. Native code targets API 35; the Android app
requires API 36 (Android 16), so it runs with that system API available.

## Rebuild

Install Android NDK **r28c (28.2.13676358)** and Python 3, Meson, Ninja, NASM,
pkg-config and make on macOS or Linux. The original build-tool versions are in
`provenance.json`. From the repository root:

```sh
ANDROID_NDK_HOME=/absolute/path/to/android-ndk-r28c ./scripts/build-ffmpeg.sh
python3 scripts/verify-ffmpeg.py
```

An optional ABI argument rebuilds one target (`arm64-v8a` or `x86_64`). Source
archives are checked against pinned SHA256 values before building. The build
script performs no downloads. It writes generated build files into ignored
`build/ffmpeg/`, installs finished executables into `jniLibs/`, and refreshes
`SHA256SUMS` and `build-config/`. A build directory may be reused for incremental
builds; use a new `NATSUKO_FFMPEG_BUILD_DIR` for a clean rebuild. The checked-in
checksums identify the shipped binaries; byte-for-byte identity across different
host toolchains or build paths is not promised.

`scripts/verify-ffmpeg.py` needs only Python. It validates all four binary
checksums, ELF architectures, PIE entry points, Android linker paths, nonexecutable
stacks, 16 KB load alignment and shared-library dependencies. The detailed compiler
feature reports and ELF inspection outputs are retained in `build-config/`.

Pass one or more APK/AAB paths to also audit the finished application artifacts:

```sh
python3 scripts/verify-ffmpeg.py app/build/outputs/apk/debug/app-debug.apk
python3 scripts/verify-ffmpeg.py /absolute/path/to/app.apk /absolute/path/to/app.aab
```

The artifact audit requires all four executables in `lib/` (APK) or `base/lib/`
(AAB), checks their bytes against `SHA256SUMS`, and checks the bundled license
notices against `notices/`. Missing, duplicated or modified required entries fail
the audit. Artifacts are read directly as ZIP archives without extraction or
modification. This audits universal APKs and the full AAB; an individual ABI split
APK intentionally lacks two of the four binaries and will fail this full-payload
check.

Run the artifact audit's integrity regression tests with
`python3 scripts/test_verify_ffmpeg.py`.

## Source verification and licenses

`provenance.json` records official source URLs, archive checksums, toolchain
versions and the build configuration. The FFmpeg release's detached signature was
verified against the upstream release signing key fingerprint
`FCF986EA15E6E293A5644F10B4322F04D67658D8`; its signature and public key are included
in `sources/`. The dav1d archive matches the upstream SHA256 file, also included.

FFmpeg is configured as **LGPL 2.1 or later**, with GPL and nonfree components
disabled. dav1d uses the BSD 2-Clause license. Complete upstream sources and the
build script are included so the executables can be rebuilt and replaced. Include
this source directory and the build script when distributing corresponding
source with releases. Publish the corresponding source archive alongside each
binary release and link it from the download description. Files in `notices/`
are packaged as application assets and available from the About screen.

The additional notices retain the Independent JPEG Group acknowledgment and
the original MIT, ISC, BSD and other permissive notices present in the shipped
sources. LLVM and Android NDK sysroot notices cover compiler runtime, startup
and header code; the sysroot file preserves the full upstream notice aggregate.
No third-party FFmpegKit binary or wrapper is used. Natsuko's own Apache 2.0
license does not replace these third-party terms.

See [native dependency licensing and source delivery](../../docs/native_licenses.md)
for notice provenance, release contents and instructions for modifying,
rebuilding and replacing the standalone executables.
