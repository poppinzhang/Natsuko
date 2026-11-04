#!/usr/bin/env bash
# Rebuild the FFmpeg executables shipped inside the APK. Never called by the app.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR_DIR="$PROJECT_ROOT/third_party/ffmpeg"
BUILD_DIR="${NATSUKO_FFMPEG_BUILD_DIR:-$PROJECT_ROOT/build/ffmpeg}"
NDK_DIR="${ANDROID_NDK_HOME:-$BUILD_DIR/toolchains/android-ndk-r28c}"
JOBS="${NATSUKO_FFMPEG_JOBS:-8}"
export SOURCE_DATE_EPOCH=1786492800

case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64 ;;
    Linux) HOST_TAG=linux-x86_64 ;;
    *) echo "Build on macOS or Linux with Android NDK r28c." >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
if ! test -f "$NDK_DIR/source.properties" ||
    ! grep -q 'Pkg.Revision = 28.2.13676358' "$NDK_DIR/source.properties"; then
    echo "Set ANDROID_NDK_HOME to Android NDK r28c (28.2.13676358)." >&2
    exit 1
fi
export PATH="$BUILD_DIR/host/bin:$TOOLCHAIN/bin:$PATH"
for tool in python3 meson ninja nasm pkg-config make; do
    command -v "$tool" >/dev/null || { echo "Required build tool missing: $tool" >&2; exit 1; }
done

# Sources are part of the repository. Rebuilds have no network or package downloads.
python3 - "$VENDOR_DIR" <<'PY'
import hashlib
import pathlib
import sys
root = pathlib.Path(sys.argv[1]) / 'sources'
expected = {
    'ffmpeg-9.0.1.tar.xz': 'cf38e0e28c7e5605942c4a77755349b0145804a397af37eb1fb4c77cb237f635',
    'dav1d-1.5.4.tar.xz': '686616b7c69eb88d44459391ab25cac13b6647a3b288835c5784e71c1514a5c5',
}
for name, checksum in expected.items():
    actual = hashlib.sha256((root / name).read_bytes()).hexdigest()
    if actual != checksum:
        raise SystemExit(f'Source checksum mismatch: {name}')
PY

mkdir -p "$BUILD_DIR/src" "$VENDOR_DIR/build-config"
if ! test -d "$BUILD_DIR/src/ffmpeg-9.0.1"; then
    tar -xf "$VENDOR_DIR/sources/ffmpeg-9.0.1.tar.xz" -C "$BUILD_DIR/src"
fi
if ! test -d "$BUILD_DIR/src/dav1d-1.5.4"; then
    tar -xf "$VENDOR_DIR/sources/dav1d-1.5.4.tar.xz" -C "$BUILD_DIR/src"
fi

build_abi() {
    local abi="$1" arch triple cpu_family
    case "$abi" in
        arm64-v8a) arch=aarch64; triple=aarch64-linux-android; cpu_family=aarch64 ;;
        x86_64) arch=x86_64; triple=x86_64-linux-android; cpu_family=x86_64 ;;
        *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
    esac
    local target="$BUILD_DIR/$abi"
    local prefix="$target/prefix"
    local cc="$TOOLCHAIN/bin/${triple}35-clang"
    local cxx="$TOOLCHAIN/bin/${triple}35-clang++"
    mkdir -p "$target" "$prefix"
    cat > "$target/android.cross" <<EOF
[binaries]
c = '$cc'
cpp = '$cxx'
ar = '$TOOLCHAIN/bin/llvm-ar'
strip = '$TOOLCHAIN/bin/llvm-strip'
pkg-config = 'pkg-config'
[host_machine]
system = 'android'
cpu_family = '$cpu_family'
cpu = '$arch'
endian = 'little'
[properties]
needs_exe_wrapper = true
[built-in options]
c_args = ['-ffile-prefix-map=$BUILD_DIR=/natsuko-ffmpeg-build']
c_link_args = ['-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384']
EOF
    if ! test -f "$target/dav1d/build.ninja"; then
        meson setup "$target/dav1d" "$BUILD_DIR/src/dav1d-1.5.4" \
            --cross-file "$target/android.cross" --prefix "$prefix" --libdir lib \
            --default-library static --buildtype release --wrap-mode nodownload \
            -Db_staticpic=true -Denable_tools=false -Denable_tests=false \
            -Denable_examples=false -Denable_docs=false
    fi
    ninja -C "$target/dav1d" -j "$JOBS"
    ninja -C "$target/dav1d" install

    mkdir -p "$target/ffmpeg"
    (
        cd "$target/ffmpeg"
        export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig"
        export PKG_CONFIG_PATH=
        "$BUILD_DIR/src/ffmpeg-9.0.1/configure" \
            --prefix="$prefix" --target-os=android --arch="$arch" \
            --enable-cross-compile --cc="$cc" --cxx="$cxx" --ld="$cc" \
            --ar="$TOOLCHAIN/bin/llvm-ar" --nm="$TOOLCHAIN/bin/llvm-nm" \
            --ranlib="$TOOLCHAIN/bin/llvm-ranlib" --strip="$TOOLCHAIN/bin/llvm-strip" \
            --pkg-config=pkg-config --pkg-config-flags=--static \
            --extra-cflags="-fPIC -ffile-prefix-map=$BUILD_DIR=/natsuko-ffmpeg-build" \
            --extra-ldflags="-pie -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
            --extra-version=natsuko --enable-pic --disable-shared --enable-static \
            --disable-autodetect --disable-network --disable-doc --disable-debug \
            --disable-everything --disable-avdevice --disable-ffplay \
            --enable-ffmpeg --enable-ffprobe --enable-avcodec --enable-avformat \
            --enable-avfilter --enable-swscale --enable-swresample \
            --enable-pthreads --enable-libdav1d --enable-zlib \
            --enable-protocol=file,pipe \
            --enable-demuxer=mov,matroska,avi,image2,image2pipe,gif,h264,hevc,ivf \
            --enable-decoder=h264,hevc,libdav1d,vp8,vp9,mpeg4,mjpeg,png,bmp,gif,rawvideo \
            --enable-parser=h264,hevc,av1,vp8,vp9,mpeg4video,png \
            --enable-encoder=gif,png,rawvideo,wrapped_avframe \
            --enable-muxer=gif,image2,image2pipe,null,rawvideo \
            --enable-filter=buffer,buffersink,fps,scale,setpts,settb,palettegen,paletteuse,format,null,split,trim,transpose,hflip,vflip,setsar \
            --enable-bsf=extract_extradata,h264_mp4toannexb,hevc_mp4toannexb,av1_frame_split,av1_frame_merge \
            > "$target/configure.log" 2>&1
        make -j "$JOBS" ffmpeg ffprobe > "$target/build.log" 2>&1
    )
    local output="$VENDOR_DIR/jniLibs/$abi"
    mkdir -p "$output"
    for executable in ffmpeg ffprobe; do
        "$TOOLCHAIN/bin/llvm-strip" --strip-all \
            -o "$output/libnatsuko_${executable}.so" "$target/ffmpeg/$executable"
        chmod 755 "$output/libnatsuko_${executable}.so"
        "$TOOLCHAIN/bin/llvm-readelf" --program-headers --dynamic \
            "$output/libnatsuko_${executable}.so" > "$VENDOR_DIR/build-config/$abi-$executable-elf.txt"
    done
    cp "$target/ffmpeg/config_components.h" "$VENDOR_DIR/build-config/$abi-components.h"
    cp "$target/configure.log" "$VENDOR_DIR/build-config/$abi-configure.txt"
}

if test "$#" -eq 0; then
    set -- arm64-v8a x86_64
fi
for abi in "$@"; do
    build_abi "$abi"
done

python3 - "$VENDOR_DIR" <<'PY'
import hashlib
import pathlib
import sys
root = pathlib.Path(sys.argv[1])
files = sorted(root.glob('jniLibs/*/*.so'))
lines = [f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(root)}' for p in files]
(root / 'SHA256SUMS').write_text('\n'.join(lines) + '\n')
PY
echo "Built embedded executables in $VENDOR_DIR/jniLibs"
if test -f "$VENDOR_DIR/jniLibs/arm64-v8a/libnatsuko_ffprobe.so" &&
    test -f "$VENDOR_DIR/jniLibs/x86_64/libnatsuko_ffprobe.so"; then
    python3 "$PROJECT_ROOT/scripts/verify-ffmpeg.py"
fi
