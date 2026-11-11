# Embedded image metadata runtime

Natsuko packages ExifTool 13.53 and a minimal Perl 5.40.2 interpreter for
`arm64-v8a` and `x86_64`. Everything required for image metadata reads and writes
is installed with the app. No external app, executable download, codec download,
network service, or device Perl installation is required.

The executable is installed by Android as `libnatsuko_perl.so` in the app's native
library directory, matching the existing FFmpeg executable packaging. Perl core
and its required XS extensions are linked into the PIE executable; its only
shared dependencies are Android's `libc.so`, `libm.so`, and `libdl.so`. All ELF
LOAD segments are aligned to 16 KB. Pure Perl modules are stored in ZIP assets
and extracted to the application's private files directory. The app supplies the
module directory with `-I` and invokes only its bundled `location_bridge.pl`.

The backend edits metadata on a copy of each target. It does not decode or
recompress the image, and never modifies the imported original.

## Rebuilding

Use Android NDK `28.2.13676358`, a C compiler for the build machine, GNU make,
Python 3, and the platform archive/patch tools on macOS or Linux:

```sh
ANDROID_NDK_HOME=/path/to/android-ndk ./scripts/build-metadata-runtime.sh
python3 scripts/verify-metadata.py
```

Builds use the pinned archives in `sources/`; they never download dependencies.
The NDK's API 35 compiler is used because it is the newest API in NDK r28;
Natsuko's application minimum remains API 36. `SHA256SUMS` covers the upstream
archives and all shipped runtime artifacts. `provenance.json` records source
URLs, versions, licensing, and build settings.

`build-config/` contains three small compatibility patches: portable whitespace
expressions for BSD sed, Android fallbacks for unsupported GNU locale queries,
and retaining preprocessor line markers when discovering target errno headers.
The Perl 5.40.0 perl-cross integration patches also apply to Perl 5.40.2.

Perl and ExifTool are distributed under their Artistic License option. The
bundled zlib 1.3.1 source retains its separate zlib license. Complete notices are
in `notices/` and are included in the application license catalog.
