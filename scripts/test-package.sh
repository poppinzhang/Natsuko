#!/usr/bin/env bash
set -euo pipefail

# Contract tests use fake Gradle/signing tools; no Android builds or real keys.
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/natsuko-package-test.XXXXXX")"
trap 'rm -rf "$fixture"' EXIT
unset NATSUKO_VERSION_NAME NATSUKO_VERSION_CODE NATSUKO_KEYSTORE NATSUKO_STORE_PASSWORD NATSUKO_KEY_ALIAS NATSUKO_KEY_PASSWORD GITHUB_OUTPUT
export JAVA_HOME="$fixture/jdk" ANDROID_HOME="$fixture/sdk"
mkdir -p "$fixture/scripts" "$JAVA_HOME/bin" "$ANDROID_HOME/build-tools/36.0.0"
cp "$project_dir/scripts/package.sh" "$fixture/scripts/"
cat > "$fixture/gradlew" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ " $* " == *" clean "* ]] || exit 11
[[ "${NATSUKO_VERSION_NAME:-}" == 1.0.0 && "${NATSUKO_VERSION_CODE:-}" == 3 ]] || exit 12
[[ "${FAIL_BUILD:-}" != true ]] || exit 13
if [[ "${CHECK_SIGNING:-}" == true ]]; then
  [[ "$NATSUKO_STORE_PASSWORD" == environment-wins ]] || exit 14
  [[ "$NATSUKO_KEY_ALIAS" == '$(touch SHOULD_NOT_EXIST)' ]] || exit 15
  [[ "$NATSUKO_KEY_PASSWORD" == 'literal=with spaces#and`backticks`' ]] || exit 16
fi
mkdir -p app/build/outputs/apk/debug app/build/outputs/apk/release app/build/outputs/bundle/release app/build/outputs/mapping/release
printf debug > app/build/outputs/apk/debug/app-debug.apk
# Produce both filenames to prove packaging never uses an APK glob.
printf signed > app/build/outputs/apk/release/app-release.apk
printf unsigned > app/build/outputs/apk/release/app-release-unsigned.apk
printf bundle > app/build/outputs/bundle/release/app-release.aab
printf mapping > app/build/outputs/mapping/release/mapping.txt
MOCK
cat > "$ANDROID_HOME/build-tools/36.0.0/apksigner" <<'MOCK'
#!/usr/bin/env bash
[[ "${FAIL_SIGNATURE:-}" != true ]] || exit 1
printf 'Signer #1 certificate SHA-256 digest: mock\n'
MOCK
cat > "$JAVA_HOME/bin/jarsigner" <<'MOCK'
#!/usr/bin/env bash
printf 'jar verified.\n'
MOCK
chmod +x "$fixture/gradlew" "$JAVA_HOME/bin/jarsigner" "$ANDROID_HOME/build-tools/36.0.0/apksigner"
cd "$fixture"

write_version() { printf 'VERSION_NAME=1.0.0\nVERSION_CODE=3\n' > version.properties; }
write_changelog() { printf '# Changelog\n\n## unstaged\n\n## 1.0.0\n\n- Current features.\n\n## 0.9.0\n\n- Old features.\n' > CHANGELOG.md; }
compliance_files=(
  LICENSE NOTICE THIRD_PARTY_NOTICES.md
  docs/native_licenses.md
  third_party/ffmpeg/README.md third_party/ffmpeg/provenance.json
  third_party/open-source-components.json
  third_party/ffmpeg/SHA256SUMS scripts/build-ffmpeg.sh scripts/verify-ffmpeg.py
)
source_directories=(
  third_party/ffmpeg/sources third_party/ffmpeg/build-config third_party/ffmpeg/notices
  third_party/licenses
)
write_compliance() {
  mkdir -p "${source_directories[@]}"
  local file directory
  for file in "${compliance_files[@]}"; do
    mkdir -p "$(dirname "$file")"
    printf 'Required attribution/build material: %s\n' "$file" > "$file"
  done
  for directory in "${source_directories[@]}"; do
    printf 'Required corresponding source material: %s\n' "$directory" > "$directory/fixture.txt"
  done
  cat > scripts/verify-open-source.py <<'MOCK'
import os
from pathlib import Path
import sys

name = Path(__file__).name
artifacts = sys.argv[1:]
assert len(artifacts) == 3, f"Expected debug APK, release APK and AAB: {artifacts}"
assert artifacts[0] == "app/build/outputs/apk/debug/app-debug.apk"
assert artifacts[1] in ("app/build/outputs/apk/release/app-release.apk", "app/build/outputs/apk/release/app-release-unsigned.apk")
assert artifacts[2] == "app/build/outputs/bundle/release/app-release.aab"
assert all(Path(path).is_file() for path in artifacts)
failure_flag = "FAIL_LICENSE_AUDIT" if name == "verify-open-source.py" else "FAIL_NATIVE_AUDIT"
if os.environ.get(failure_flag) == "true":
    raise SystemExit(f"Artifact compliance audit failed: {name}")
with open("verification-calls", "a") as log:
    log.write(name + "\n")
MOCK
  cp scripts/verify-open-source.py scripts/verify-ffmpeg.py
}
expect_failure() {
  local expected="$1"
  shift
  if "$@" > test.log 2>&1; then
    echo "Expected failure: $expected" >&2
    exit 1
  fi
  [[ -n "$expected" ]] || return 0
  awk -v expected="$expected" 'index($0, expected) { found = 1 } END { exit !found }' test.log || {
    cat test.log >&2
    echo "Missing expected error: $expected" >&2
    exit 1
  }
}
write_version
write_changelog
write_compliance
expect_failure '缺少签名配置 NATSUKO_KEYSTORE' ./scripts/package.sh
[[ ! -d app && ! -d dist ]]
expect_failure 'NATSUKO_VERSION_NAME 与 version.properties 不一致' env NATSUKO_VERSION_NAME=2.0.0 ./scripts/package.sh --unsigned
expect_failure '发布标签必须为 v1.0.0' ./scripts/package.sh --tag v2.0.0
expect_failure '发布标签必须为 v1.0.0' ./scripts/package.sh --tag 1.0.0
expect_failure '标签发布不能使用 --unsigned' ./scripts/package.sh --unsigned --tag v1.0.0
printf 'VERSION_NAME=1.0.0\nVERSION_CODE=2147483648\n' > version.properties
expect_failure 'VERSION_CODE 必须为' ./scripts/package.sh --unsigned
printf 'VERSION_NAME=1.0.0\nVERSION_CODE=3\nVERSION_CODE=4\n' > version.properties
expect_failure '包含重复配置键' ./scripts/package.sh --unsigned
write_version
printf '# Changelog\n\n## 1.0.0 - date\n\n- Content.\n' > CHANGELOG.md
expect_failure '精确版本标题' ./scripts/package.sh --unsigned
printf '# Changelog\n\n## unstaged\n\n- Pending feature.\n\n## 1.0.0\n\n- Current features.\n' > CHANGELOG.md
expect_failure 'unstaged 下的内容' ./scripts/package.sh
./scripts/package.sh --unsigned > test.log
[[ -f dist/1.0.0/app-release-unsigned.apk && ! -e dist/1.0.0/app-release.apk ]]
awk '/SIGNING_STATUS=unsigned-verification/ { found = 1 } END { exit !found }' dist/1.0.0/BUILD_INFO.properties
write_changelog

# Values are literal data; environment values take precedence over the local file.
cat > release.properties <<'SIGNING'
NATSUKO_KEYSTORE=key.jks
NATSUKO_STORE_PASSWORD=file-value
NATSUKO_KEY_ALIAS=$(touch SHOULD_NOT_EXIST)
NATSUKO_KEY_PASSWORD=literal=with spaces#and`backticks`
SIGNING
touch key.jks
printf previous > dist/1.0.0/previous-package
CHECK_SIGNING=true NATSUKO_STORE_PASSWORD=environment-wins GITHUB_OUTPUT="$fixture/github-output" ./scripts/package.sh --tag v1.0.0 > test.log
[[ ! -e SHOULD_NOT_EXIST && ! -e dist/1.0.0/release.properties ]]
[[ -f dist/1.0.0/app-release.apk && ! -e dist/1.0.0/app-release-unsigned.apk ]]
[[ -f dist/1.0.0/mapping.txt && -f dist/1.0.0/SIGNING_CERTIFICATE.txt && -f dist/1.0.0/AAB_SIGNATURE.txt ]]
for file in LICENSE NOTICE THIRD_PARTY_NOTICES.md; do
  cmp "$file" "dist/1.0.0/$file"
done
source_name=natsuko-third-party-sources-1.0.0
mkdir extracted-sources
tar -xzf "dist/1.0.0/$source_name.tar.gz" -C extracted-sources
for file in "${compliance_files[@]}"; do
  cmp "$file" "extracted-sources/$source_name/$file"
done
for directory in "${source_directories[@]}"; do
  cmp "$directory/fixture.txt" "extracted-sources/$source_name/$directory/fixture.txt"
done
[[ -s "extracted-sources/$source_name/README.md" ]]
[[ ! -e "extracted-sources/$source_name/release.properties" && ! -e "extracted-sources/$source_name/key.jks" ]]
[[ ! -d "dist/1.0.0/$source_name" ]]
[[ "$(awk '/^verify-open-source.py$/ { count++ } END { print count }' verification-calls)" == 2 ]]
[[ "$(awk '/^verify-ffmpeg.py$/ { count++ } END { print count }' verification-calls)" == 2 ]]
[[ "$(cat github-output)" == version=1.0.0 ]]
[[ "$(cat dist/1.0.0.backup.*/previous-package)" == previous ]]
awk '/SIGNING_STATUS=signed-release/ { found = 1 } END { exit !found }' dist/1.0.0/BUILD_INFO.properties
awk '/Current features/ { found = 1 } /Old features/ { exit 1 } END { exit !found }' dist/1.0.0/RELEASE_NOTES.md
(cd dist/1.0.0 && shasum -a 256 -c SHA256SUMS) > test.log
cp dist/1.0.0/SHA256SUMS previous-checksums
for file in "${compliance_files[@]}"; do
  mv "$file" missing-compliance-file
  expect_failure "缺少或为空的合规文件：$file" ./scripts/package.sh
  cmp previous-checksums dist/1.0.0/SHA256SUMS
  mv missing-compliance-file "$file"
done
cp NOTICE notice-backup
: > NOTICE
expect_failure '缺少或为空的合规文件：NOTICE' ./scripts/package.sh
cmp previous-checksums dist/1.0.0/SHA256SUMS
mv notice-backup NOTICE
for directory in "${source_directories[@]}"; do
  mv "$directory" missing-source-directory
  expect_failure "缺少或为空的第三方源码资料目录：$directory" ./scripts/package.sh
  cmp previous-checksums dist/1.0.0/SHA256SUMS
  mkdir "$directory"
  expect_failure "缺少或为空的第三方源码资料目录：$directory" ./scripts/package.sh
  cmp previous-checksums dist/1.0.0/SHA256SUMS
  rmdir "$directory"
  mv missing-source-directory "$directory"
done
mv scripts/verify-open-source.py missing-audit-script
expect_failure '缺少或为空的合规核验脚本：scripts/verify-open-source.py' ./scripts/package.sh
cmp previous-checksums dist/1.0.0/SHA256SUMS
mv missing-audit-script scripts/verify-open-source.py
expect_failure 'Artifact compliance audit failed: verify-open-source.py' env FAIL_LICENSE_AUDIT=true ./scripts/package.sh
cmp previous-checksums dist/1.0.0/SHA256SUMS
expect_failure 'Artifact compliance audit failed: verify-ffmpeg.py' env FAIL_NATIVE_AUDIT=true ./scripts/package.sh
cmp previous-checksums dist/1.0.0/SHA256SUMS
expect_failure '' env FAIL_BUILD=true ./scripts/package.sh
cmp previous-checksums dist/1.0.0/SHA256SUMS
expect_failure '' env FAIL_SIGNATURE=true ./scripts/package.sh
cmp previous-checksums dist/1.0.0/SHA256SUMS
[[ "$(find dist -maxdepth 1 -name '.1.0.0.*' | wc -l | tr -d ' ')" == 0 ]]
echo 'Packaging safeguards passed (mock Gradle/signing, no Android build).'
