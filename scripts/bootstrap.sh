#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/Users/$(id -un)/Library/Android/sdk}}"
jdk_root="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"

if [[ ! -x "$jdk_root/bin/java" ]]; then
  if ! command -v brew >/dev/null 2>&1; then
    echo "需要 JDK 17；请先安装 Homebrew 或手动设置 JAVA_HOME。" >&2
    exit 1
  fi
  brew install openjdk@17
fi

sdkmanager_bin="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$sdkmanager_bin" ]]; then
  echo "未找到 Android command-line tools：$sdkmanager_bin" >&2
  echo "请通过 Android Studio SDK Manager 安装 Android SDK Command-line Tools (latest)。" >&2
  exit 1
fi

export JAVA_HOME="$jdk_root"
export ANDROID_HOME="$sdk_root"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

yes | "$sdkmanager_bin" --licenses >/dev/null || true
"$sdkmanager_bin" "platform-tools" "platforms;android-36" "build-tools;36.0.0"

cd "$project_dir"
./gradlew --version
./gradlew help
echo "Natsuko 构建环境已就绪。"
