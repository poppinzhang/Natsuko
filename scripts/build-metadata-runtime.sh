#!/usr/bin/env bash
# Rebuild the offline metadata interpreter from the vendored source archives.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
vendor="$root/third_party/metadata"
ndk="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/ndk/28.2.13676358}"
jobs="${NATSUKO_BUILD_JOBS:-8}"
work=$(mktemp -d "${TMPDIR:-/tmp}/natsuko-metadata.XXXXXX")
trap 'rm -rf "$work"' EXIT

case "$(uname -s)" in
  Darwin) host_tag=darwin-x86_64 ;;
  Linux) host_tag=linux-x86_64 ;;
  *) echo 'Build on macOS or Linux with Android NDK 28.2.13676358.' >&2; exit 1 ;;
esac
toolchain="$ndk/toolchains/llvm/prebuilt/$host_tag/bin"
test -x "$toolchain/clang"
python3 - "$vendor" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
expected = dict(line.split(None, 1)[::-1] for line in (root / 'SHA256SUMS').read_text().splitlines() if line.strip())
for name in ('sources/perl-5.40.2.tar.xz', 'sources/perl-cross-1.6.tar.gz'):
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == expected[name], name
PY

modules=cpan/Encode,cpan/Compress-Raw-Zlib,cpan/IO-Compress,cpan/Time-Local,ext/Fcntl,cpan/Scalar-List-Utils,ext/File-Glob,dist/IO,dist/PathTools,dist/Storable,dist/Carp,dist/Exporter,dist/constant,dist/XSLoader,cpan/AutoLoader,cpan/parent,ext/Errno

for abi in arm64-v8a x86_64; do
  case "$abi" in
    arm64-v8a) target=aarch64-linux-android; perl_arch=aarch64-android ;;
    x86_64) target=x86_64-linux-android; perl_arch=x86_64-android ;;
  esac
  build="$work/$abi"
  mkdir -p "$build"
  tar -xf "$vendor/sources/perl-5.40.2.tar.xz" -C "$build" --strip-components=1
  chmod -R u+w "$build"
  tar -xf "$vendor/sources/perl-cross-1.6.tar.gz" -C "$build" --strip-components=1
  (
    cd "$build"
    patch -p1 < "$vendor/build-config/portable-sed.patch"
    # 5.40.2 retains the same build integration points as the 5.40.0 patchset.
    cp -R cnf/diffs/perl5-5.40.0 cnf/diffs/perl5-5.40.2
    host_args=()
    if [[ "$host_tag" == darwin-x86_64 ]]; then
      long_double=8
      [[ "$(uname -m)" != x86_64 ]] || long_double=16
      host_args=(--host-set-readelf=/usr/bin/true --host-objdump=/usr/bin/objdump
        --host-set-charsize=1 --host-set-shortsize=2 --host-set-intsize=4
        --host-set-longsize=8 --host-set-doublesize=8 --host-set-ptrsize=8
        --host-set-longdblsize="$long_double" --host-set-longlongsize=8
        --host-set-sizesize=8 --host-set-fpossize=8 --host-set-lseeksize=8
        --host-set-uidsize=4 --host-set-gidsize=4 --host-set-timesize=8
        --host-set-byteorder=12345678 --host-set-d-nanosleep=define)
    fi
    ./configure --target="$target" "${host_args[@]}" \
      --with-cc="$toolchain/${target}35-clang" \
      --with-ranlib="$toolchain/llvm-ranlib" \
      --with-objdump="$toolchain/llvm-objdump" \
      -Dreadelf="$toolchain/llvm-readelf" -Dar="$toolchain/llvm-ar" \
      -Dosname=android -Dsh=/system/bin/sh --prefix=/natsuko/perl \
      -Dccflags='-D_GNU_SOURCE -D_BSD_SOURCE=1 -fwrapv -fno-strict-aliasing -fPIE' \
      -Dldflags='-pie -Wl,-z,max-page-size=16384' -Doptimize=-O2 \
      --with-libs='-lm -ldl' --all-static --only-mod="$modules" \
      -Uuseshrplib -Uuseithreads
    make crosspatch SHELL=/bin/sh
    patch -p1 < "$vendor/build-config/android-langinfo.patch"
    patch -p1 < "$vendor/build-config/cross-errno.patch"
    make -j"$jobs" SHELL=/bin/sh miniperl
    make SHELL=/bin/sh dynaloader
    make -j"$jobs" SHELL=/bin/sh perl nonxs_ext
    for module in ext/Fcntl cpan/Compress-Raw-Zlib cpan/Encode cpan/Scalar-List-Utils ext/File-Glob dist/IO dist/PathTools dist/Storable; do
      rm -f "$module/pm_to_blib"
      make -C "$module" SHELL=/bin/sh LINKTYPE=static pm_to_blib
    done
    make SHELL=/bin/sh lib/unicore/CombiningClass.pl
    make install.perl SHELL=/bin/sh DESTDIR="$build/stage"
  )
  mkdir -p "$vendor/jniLibs/$abi" "$vendor/assets/metadata"
  "$toolchain/llvm-strip" --strip-unneeded "$build/perl" \
    -o "$vendor/jniLibs/$abi/libnatsuko_perl.so"
  python3 "$vendor/build-config/package-perl.py" \
    "$build/stage/natsuko/perl/lib/perl5/5.40.2" "$perl_arch" \
    "$vendor/assets/metadata/perl-5.40.2-$abi.zip"
done

python3 "$root/scripts/prepare-exiftool.py"
python3 - "$vendor" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
paths = sorted(p for folder in ('sources', 'jniLibs', 'assets') for p in (root / folder).rglob('*') if p.is_file())
(root / 'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(root).as_posix()}\n' for p in paths))
PY
echo 'Rebuilt the embedded metadata runtime for arm64-v8a and x86_64.'
