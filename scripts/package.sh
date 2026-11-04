#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
unsigned=false
release_tag=""
fail() { echo "打包失败：$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --unsigned) unsigned=true; shift ;;
    --tag)
      [[ $# -ge 2 ]] || fail "--tag 需要版本标签。"
      release_tag="$2"; shift 2 ;;
    --help|-h)
      echo "用法：./scripts/package.sh [--unsigned] [--tag v<version>]"
      echo "默认构建已签名正式包；--unsigned 仅用于本地或 CI 验证。"
      exit 0 ;;
    *) fail "未知参数：$1" ;;
  esac
done

# Parse literal KEY=value data, never source signing files or print their values.
load_properties() {
  local path="$1" kind="$2" line key value seen="|" line_number=0
  [[ -f "$path" ]] || fail "缺少 $(basename "$path")。"
  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*$ || "$line" == \#* ]] && continue
    [[ "$line" == *=* ]] || fail "$(basename "$path"):$line_number 格式必须为 KEY=value。"
    key="${line%%=*}"
    value="${line#*=}"
    case "$kind:$key" in
      version:VERSION_NAME|version:VERSION_CODE|signing:NATSUKO_KEYSTORE|signing:NATSUKO_STORE_PASSWORD|signing:NATSUKO_KEY_ALIAS|signing:NATSUKO_KEY_PASSWORD) ;;
      *) fail "$(basename "$path"):$line_number 包含不支持的配置键。" ;;
    esac
    [[ "$seen" != *"|$key|"* ]] || fail "$(basename "$path"):$line_number 包含重复配置键。"
    seen="$seen$key|"
    if [[ "$kind" == version || -z "${!key+x}" ]]; then
      printf -v "$key" '%s' "$value"
    fi
  done < "$path"
}

VERSION_NAME=""
VERSION_CODE=""
load_properties "$project_dir/version.properties" version
[[ "${VERSION_NAME:-}" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || fail "VERSION_NAME 必须是 major.minor.patch 数字版本。"
[[ "${VERSION_CODE:-}" =~ ^[1-9][0-9]*$ && ${#VERSION_CODE} -le 10 ]] || fail "VERSION_CODE 必须为 1 至 2100000000 的整数。"
[[ "$VERSION_CODE" -le 2100000000 ]] || fail "VERSION_CODE 必须为 1 至 2100000000 的整数。"
[[ -z "${NATSUKO_VERSION_NAME+x}" || "$NATSUKO_VERSION_NAME" == "$VERSION_NAME" ]] || fail "NATSUKO_VERSION_NAME 与 version.properties 不一致；请修改版本文件。"
[[ -z "${NATSUKO_VERSION_CODE+x}" || "$NATSUKO_VERSION_CODE" == "$VERSION_CODE" ]] || fail "NATSUKO_VERSION_CODE 与 version.properties 不一致；请修改版本文件。"
export NATSUKO_VERSION_NAME="$VERSION_NAME" NATSUKO_VERSION_CODE="$VERSION_CODE"

if [[ -n "$release_tag" ]]; then
  [[ "$unsigned" == false ]] || fail "标签发布不能使用 --unsigned。"
  [[ "$release_tag" == "v$VERSION_NAME" ]] || fail "发布标签必须为 v${VERSION_NAME}，与 version.properties 的版本一致。"
fi

[[ -f "$project_dir/CHANGELOG.md" ]] || fail "缺少 CHANGELOG.md。"
release_notes="$(awk -v version="$VERSION_NAME" -v unsigned="$unsigned" '
  /^## / {
    in_version = ($0 == "## " version)
    in_unstaged = ($0 == "## unstaged")
    if (in_version) versions++
    if (in_unstaged) unstaged++
    next
  }
  in_version { print; if ($0 !~ /^[[:space:]]*$/) content = 1 }
  in_unstaged && $0 !~ /^[[:space:]]*$/ { pending = 1 }
  END {
    if (versions != 1 || !content) {
      print "CHANGELOG.md 必须有唯一且非空的精确版本标题：## " version > "/dev/stderr"
      exit 1
    }
    if (unstaged > 1 || (pending && unsigned != "true")) {
      print "正式发布前请将 ## unstaged 下的内容归入当前版本，并删除重复的 unstaged 标题。" > "/dev/stderr"
      exit 1
    }
  }
' "$project_dir/CHANGELOG.md")" || fail "发布说明校验未通过。"

# Corresponding native sources and attribution must accompany every distribution.
# Check these before building so a missing legal file cannot replace an old package.
compliance_files=(
  LICENSE NOTICE THIRD_PARTY_NOTICES.md
  docs/native_licenses.md
  third_party/ffmpeg/README.md
  third_party/ffmpeg/provenance.json
  third_party/ffmpeg/SHA256SUMS
  third_party/open-source-components.json
  scripts/build-ffmpeg.sh scripts/verify-ffmpeg.py
)
source_directories=(
  third_party/ffmpeg/sources
  third_party/ffmpeg/build-config
  third_party/ffmpeg/notices
  third_party/licenses
)
for file in "${compliance_files[@]}"; do
  [[ -s "$project_dir/$file" ]] || fail "缺少或为空的合规文件：$file"
done
for directory in "${source_directories[@]}"; do
  [[ -d "$project_dir/$directory" && -n "$(find "$project_dir/$directory" -type f -print -quit)" ]] || fail "缺少或为空的第三方源码资料目录：$directory"
done
[[ -s "$project_dir/scripts/verify-open-source.py" ]] || fail "缺少或为空的合规核验脚本：scripts/verify-open-source.py"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/Users/$(id -un)/Library/Android/sdk}}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
gradle_options=(--no-daemon)
if [[ "$unsigned" == true ]]; then
  gradle_options+=("-PnatsukoUnsignedRelease=true")
  release_apk="app-release-unsigned.apk"
  signing_status="unsigned-verification"
else
  if [[ -f "$project_dir/release.properties" ]]; then
    load_properties "$project_dir/release.properties" signing
  fi
  for key in NATSUKO_KEYSTORE NATSUKO_STORE_PASSWORD NATSUKO_KEY_ALIAS NATSUKO_KEY_PASSWORD; do
    [[ "${!key:-}" =~ [^[:space:]] ]] || fail "缺少签名配置 ${key}；请配置 release.properties/环境变量，或显式使用 --unsigned 验证。"
    export "$key"
  done
  [[ "$NATSUKO_KEYSTORE" == /* ]] || NATSUKO_KEYSTORE="$project_dir/$NATSUKO_KEYSTORE"
  [[ -f "$NATSUKO_KEYSTORE" ]] || fail "发布密钥库文件不存在。"
  apksigner="$ANDROID_HOME/build-tools/36.0.0/apksigner"
  [[ -x "$apksigner" && -x "$JAVA_HOME/bin/jarsigner" ]] || fail "请安装 Android Build Tools 36.0.0 和 JDK 17 以验证发布签名。"
  release_apk="app-release.apk"
  signing_status="signed-release"
fi

output_dir="$project_dir/dist/$VERSION_NAME"
mkdir -p "$project_dir/dist"
staging_dir="$(mktemp -d "$project_dir/dist/.$VERSION_NAME.XXXXXX")"
trap 'if [[ -n "${staging_dir:-}" ]]; then rm -rf "$staging_dir"; fi' EXIT

cd "$project_dir"
# Clean prevents stale signed/unsigned artifacts and mapping files entering a release.
./gradlew "${gradle_options[@]}" clean testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease
artifacts=(
  "app/build/outputs/apk/debug/app-debug.apk"
  "app/build/outputs/apk/release/$release_apk"
  "app/build/outputs/bundle/release/app-release.aab"
)
for artifact in "${artifacts[@]}"; do
  [[ -f "$artifact" ]] || fail "构建未生成预期文件：$artifact"
  cp "$artifact" "$staging_dir/"
done
python3 scripts/verify-open-source.py "${artifacts[@]}"
python3 scripts/verify-ffmpeg.py "${artifacts[@]}"
cp CHANGELOG.md version.properties LICENSE NOTICE THIRD_PARTY_NOTICES.md "$staging_dir/"
printf '%s\n' "$release_notes" > "$staging_dir/RELEASE_NOTES.md"
if [[ -f app/build/outputs/mapping/release/mapping.txt ]]; then
  cp app/build/outputs/mapping/release/mapping.txt "$staging_dir/mapping.txt"
fi
if [[ "$unsigned" == false ]]; then
  "$apksigner" verify --verbose --print-certs "$staging_dir/$release_apk" > "$staging_dir/SIGNING_CERTIFICATE.txt"
  LC_ALL=C "$JAVA_HOME/bin/jarsigner" -verify "$staging_dir/app-release.aab" > "$staging_dir/AAB_SIGNATURE.txt"
  awk '/^jar verified\./ { verified = 1 } END { exit !verified }' "$staging_dir/AAB_SIGNATURE.txt" || fail "AAB 签名验证未通过。"
fi

# Preserve repository-relative paths so the included native build script runs
# directly after extraction. The release workflow publishes every staged file.
source_name="natsuko-third-party-sources-$VERSION_NAME"
source_dir="$staging_dir/$source_name"
mkdir -p "$source_dir"
for file in "${compliance_files[@]}"; do
  mkdir -p "$source_dir/$(dirname "$file")"
  cp "$file" "$source_dir/$file"
done
for directory in "${source_directories[@]}"; do
  mkdir -p "$source_dir/$(dirname "$directory")"
  cp -R "$directory" "$source_dir/$(dirname "$directory")/"
done
cat > "$source_dir/README.md" <<SOURCE_README
# Natsuko $VERSION_NAME — corresponding third-party sources

This archive accompanies the Natsuko application artifacts in the same release.
It contains the original FFmpeg and dav1d source archives, their license notices,
source provenance, the shipped executable checksums, build configuration reports,
and the scripts needed to rebuild both Android ABIs. It also contains the
application's reviewed dependency inventory and complete license texts under
third_party/open-source-components.json and third_party/licenses/. See
third_party/ffmpeg/README.md for tool prerequisites and detailed instructions,
and [docs/native_licenses.md](docs/native_licenses.md) for distribution and
replacement instructions.

From this extracted directory, with Android NDK r28c installed:

    ANDROID_NDK_HOME=/absolute/path/to/android-ndk-r28c ./scripts/build-ffmpeg.sh
    python3 scripts/verify-ffmpeg.py

The build creates third_party/ffmpeg/jniLibs and refreshes SHA256SUMS. Save a copy
of the included SHA256SUMS first if you want to retain the shipped binary hashes.
The sources and scripts work without downloading source archives during the build.
Byte-for-byte identity across different host toolchains is not promised.

LICENSE covers original Natsuko code. Third-party material retains its upstream
licenses; read NOTICE, THIRD_PARTY_NOTICES.md, and third_party/ffmpeg/notices/.
SOURCE_README
COPYFILE_DISABLE=1 tar -czf "$staging_dir/$source_name.tar.gz" -C "$staging_dir" "$source_name"
rm -rf "$source_dir"

revision="$(git -C "$project_dir" rev-parse --verify HEAD 2>/dev/null || true)"
[[ -n "$revision" ]] || revision="unavailable"
cat > "$staging_dir/BUILD_INFO.properties" <<METADATA
VERSION_NAME=$VERSION_NAME
VERSION_CODE=$VERSION_CODE
SIGNING_STATUS=$signing_status
RELEASE_TAG=$release_tag
GIT_COMMIT=$revision
BUILT_AT_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)
MIN_SDK=36
TARGET_SDK=36
METADATA
(
  cd "$staging_dir"
  LC_ALL=C shasum -a 256 ./* > SHA256SUMS
)

# Keep any previous package intact until every build, copy and verification succeeds.
backup_dir=""
if [[ -e "$output_dir" ]]; then
  backup_dir="$(mktemp -d "$project_dir/dist/$VERSION_NAME.backup.XXXXXX")"
  rmdir "$backup_dir"
  mv "$output_dir" "$backup_dir"
fi
if ! mv "$staging_dir" "$output_dir"; then
  [[ -z "$backup_dir" ]] || mv "$backup_dir" "$output_dir"
  fail "无法归档新的构建产物。"
fi
staging_dir=""
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf 'version=%s\n' "$VERSION_NAME" >> "$GITHUB_OUTPUT"
fi
echo "打包完成：${output_dir}（${signing_status}，versionCode=${VERSION_CODE}）"
[[ -z "$backup_dir" ]] || echo "原产物已保留：$backup_dir"
ls -lh "$output_dir"
