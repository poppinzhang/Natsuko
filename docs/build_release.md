# 构建与发布流程

## 固定工具链

- JDK 17
- Gradle 8.13（由 Wrapper 固定）
- Android Gradle Plugin 8.13.2
- Kotlin / Compose Compiler 2.2.21
- compileSdk / targetSdk / minSdk 36
- Android Build Tools 36.0.0

不要使用系统 Gradle 代替 `./gradlew`。`scripts/bootstrap.sh` 会检查/安装 JDK 17，并通过现有 Android command-line tools 安装所需 SDK 包和接受许可证。

## 内置 FFmpeg

仓库在 `third_party/ffmpeg/jniLibs/` 保存 `arm64-v8a` 与 `x86_64` 的 FFmpeg/FFprobe 原生可执行文件。普通 Gradle 构建直接将它们嵌入 APK/AAB，无需开发者另行下载视频后端；终端用户安装后也不需要下载任何 FFmpeg 组件。构建缺少对应原生文件时应失败，不能生成视频功能依赖后续下载的安装包。

二进制以 `libnatsuko_ffmpeg.so`、`libnatsuko_ffprobe.so` 命名，以 Android 原生库目录布局打包并在安装时解压；运行时从 `nativeLibraryDir` 执行。检查原生 ELF 的 16 KB 页面对齐，并保留原生库解压设置，不能仅把可执行文件放入应用缓存或 assets 后直接运行。

更换 FFmpeg 版本、启用编解码器或增加 ABI 时，按 [FFmpeg 构建资料](../third_party/ffmpeg/README.md) 重建并更新所有架构的二进制、源码来源、构建参数、校验和与许可证资料。以该目录记录为版本及构建来源的唯一依据；不要恢复已停更 FFmpegKit 的远端依赖，也不要引入运行时下载器。

## 内置图片元数据工具

图片位置复制使用随包提供的 ExifTool 13.53 / Perl 5.40.2。普通 Gradle 构建校验 `third_party/metadata/SHA256SUMS`，将两种 ABI 的解释器、离线模块与固定桥接脚本打入 APK/AAB。缺少任一组件时构建失败；用户无需另装软件或下载组件。

重建说明及原始源码见 [元数据工具构建资料](../third_party/metadata/README.md)。修改后执行 `python3 scripts/verify-metadata.py app/build/outputs/apk/debug/app-debug.apk`，验证程序、模块、脚本与声明确实随包提供；通过 Android 16 设备测试验证各格式读写与冲突流程。发布源码归档同时包含这些组件及构建补丁。

## 开源信息和对应源码

应用原创代码使用 Apache-2.0，其他组件保留各自许可。每个构建由实际解析的运行时依赖生成离线「关于」清单；新增/升级依赖必须更新审查清单，详见 [关于与许可维护](about.md)。

打包会核验三个产物中的许可证和原生组件，并归档 `LICENSE`、`NOTICE`、`THIRD_PARTY_NOTICES.md` 与 `natsuko-third-party-sources-<版本>.tar.gz`。该源码包包含 FFmpeg/dav1d 原始源码、构建参数、校验和、构建脚本及第三方声明。下载渠道应同时提供安装包和对应源码包；GitHub CI 会随产物自动上传这些文件。缺失法律文件、源码或产物内声明会阻止归档。`natsuko.png` 的角色图像授权仍需维护者核实。

## 版本与更新日志

根目录 `version.properties` 是构建、归档目录和 CI 的共同版本来源：

```properties
VERSION_NAME=1.3.0
VERSION_CODE=7
```

`VERSION_NAME` 使用 `主版本.次版本.修订号`；`VERSION_CODE` 每次正式发布递增。1.0.0 的内部版本号为 3，因为旧试打包已经使用 1 和 2。Gradle 不再回退到硬编码版本。旧 `NATSUKO_VERSION_NAME` / `NATSUKO_VERSION_CODE` 环境变量如存在，必须与文件一致，不能覆盖文件中的版本。

每次功能改动先记录到 `CHANGELOG.md` 的 `## unstaged` 下；发布时将标题改为版本号，补齐日期并更新版本文件。正式打包要求存在对应版本的日志，且没有非空 `unstaged` 内容。详见 [发布规范](release.md)。从首页底部打开「关于」可查看当前构建的版本名及内部版本号；Debug 版本名附加 `-debug`，包名附加 `.debug`。

## 日常验证

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

修改打包流程后，先运行不依赖 Android 构建或真实密钥的脚本回归测试：

```bash
./scripts/test-package.sh
```

它检查版本/日志不匹配、缺失签名、签名参数按字面读取、产物隔离、旧包备份、构建或验签失败时保留旧包等发布边界。

有 Android 16 设备或模拟器时：

```bash
./gradlew connectedDebugAndroidTest
```

JVM 测试覆盖拼接布局、预览布局、黑边检测、PDF 页面布局、视频采样计划，以及图片调整的几何、自动裁边、比例拖动与撤销历史；仪器测试覆盖单/多文件导入、分享载荷与截图路由、无效输入、图片/PDF 转换、GIF 文件解码、图片预览及排序交互，以及用户目录自动保存、结果页补存、设置同步、目录授权和失败重试。图片调整的设备测试验证单图与分享导入、多图选择、无效与动画输入、旋转/镜像/裁剪/拉直/缩小后的成品像素与尺寸、EXIF 方向与元数据、透明度与 JPEG 背景、快速切换参数后的成品字节、取消导出、放弃确认、配置重建与用户目录保存。去除黑边验证完整黑边、阈值边界、透明或较亮边缘的保护、无黑边及全黑输入；设备测试验证 PNG 输出、超限/损坏输入的逐文件失败，以及本地选择和分享截图的自动处理工作流。存储测试使用真实 MediaStore 和仅存在于测试 APK 的文档提供者，验证写入失败与取消后的残缺文件清理。

## 正式打包

推特下载的验证分为两部分：链接/token 与网络边界通过 JVM 替身测试；元数据、多视频部分失败、取消清理、文本分享、用户目录保存和下载结果转 GIF 通过 Android 仪器测试。常规测试不依赖 X 网络；真实公开接口与视频 CDN 连通性在发布前另行抽查，接口变更、限流和访问限制不由离线测试保证。

先按下一节配置签名，再执行：

```bash
./scripts/package.sh
```

脚本校验版本、CHANGELOG 和签名配置，执行单元测试、Lint、Debug APK、Release APK 和 Release AAB 构建。输出到 `dist/<VERSION_NAME>/`，同时归档 `CHANGELOG.md`、当前版本的 `RELEASE_NOTES.md`、公开版本元数据 `BUILD_INFO.properties`、`version.properties`、R8 `mapping.txt`（如有）与 `SHA256SUMS`。签名构建还附带 APK/AAB 签名验证结果。打包只收集本次生成的对应签名类型产物；成功替换同版本目录前备份到 `dist/<VERSION_NAME>.backup.<随机后缀>/`，避免混入历史未签名包。

Release APK 是正式安装包，AAB 用于应用商店分发；Debug APK 用于开发验证。所有正式升级必须复用同一签名身份和递增的内部版本号。

未准备签名、仅需验证构建时，显式执行：

```bash
./scripts/package.sh --unsigned
```

该模式生成 `app-release-unsigned.apk` 和未签名 AAB，并在元数据中注明状态；未签名 APK 不能直接安装。不能把此构建作为已签名正式包交付。

## Release 签名

正式构建需要以下四个值。支持环境变量，或在项目根目录的 `release.properties` 中保存不带引号的 `KEY=value` 配置；环境变量优先。配置文件按数据读取，不会作为 Shell 脚本执行。

```properties
NATSUKO_KEYSTORE=/absolute/path/natsuko-release.jks
NATSUKO_STORE_PASSWORD=replace-with-your-store-password
NATSUKO_KEY_ALIAS=natsuko
NATSUKO_KEY_PASSWORD=replace-with-your-key-password
```

也可在已安全配置这四个环境变量的终端直接运行打包命令。不要把真实密码写入文档、命令行参数、日志或公开产物。

本地首次发布时创建的专用密钥位于 `signing/natsuko-release.jks`，对应配置位于根目录 `release.properties`。两份文件仅当前用户可读写，已被 `.gitignore` 排除，不会复制到 `dist/`。**请将密钥库和配置文件一起安全备份，后续版本持续复用；不要在重新打包时重新生成密钥。**

## 产物校验

确认签名 APK 的包名为 `com.poppinzhang.natsuko`、版本与 `version.properties` 一致、最低 Android 版本为 API 36，并使用 SDK Build Tools 验证签名和对齐。例如在项目根目录运行（工具路径按本机 SDK 调整）：

```bash
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs dist/1.3.0/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 -v 4 dist/1.3.0/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging dist/1.3.0/app-release.apk
(cd dist/1.3.0 && shasum -a 256 -c SHA256SUMS)
```

具备设备/模拟器时运行仪器测试，并安装正式 APK 验证能启动、显示正确版本和功能入口。视频改动还应在安装包中检查两种 ABI 的 FFmpeg/FFprobe 已实际打包，并在断网状态下验证视频元数据读取、GIF 转换、损坏输入与取消清理。构建通过不等于全部设备、文件格式均已验证；视频兼容性取决于内置 FFmpeg 构建支持的容器、编解码器及输入文件本身。

## GitHub Actions

`.github/workflows/android.yml` 在 push、pull request 和手动触发时执行构建验证，另在 Android 16 AOSP ATD 模拟器上运行仪器测试。普通 CI 允许显式未签名构建；推送 `v<版本号>` tag 时，必须与 `version.properties` 一致且提供完整发布签名，然后创建 GitHub Release 并上传产物，发布说明来自对应 CHANGELOG 条目。

在仓库 Secrets 配置：

- `NATSUKO_KEYSTORE_BASE64`
- `NATSUKO_STORE_PASSWORD`
- `NATSUKO_KEY_ALIAS`
- `NATSUKO_KEY_PASSWORD`

第一个值是应持续复用的发布密钥库的 base64 文本。正式 tag 缺少签名配置时失败，不自动退化为未签名发布。不要使用 CI 运行次数作为公开版本号或 Android 内部版本号。

本地构建不会创建/推送 Git tag 或上传远端 Release。发布时先提交代码和版本配置，再运行打包脚本，使产物元数据记录对应提交；验证通过后创建同一提交的 `v<版本号>` tag。未配置远端时，仅完成本地提交、tag 和正式打包。
