# Third-party notices / 第三方开源声明

Natsuko 原创代码采用 [Apache License 2.0](LICENSE)。第三方组件保留各自的许可和版权；本文件不会更改它们的许可。离线全文可在应用首页底部「关于」中阅读。

## 随应用分发的组件

| 项目 | 当前版本 | 许可 | 上游与声明 |
| --- | --- | --- | --- |
| AndroidX / Jetpack Compose / Material icons | 各模块版本见精确依赖清单 | Apache-2.0；含 graphics-path 原生代码声明 | [AndroidX 源码](https://android.googlesource.com/platform/frameworks/support/)、[许可](third_party/licenses/AndroidX-LICENSE.txt)、[版权](third_party/licenses/AndroidX-NOTICE.txt)、[原生代码声明](third_party/licenses/AndroidX-Graphics-Path-NOTICE.txt) |
| Kotlin standard library | 2.2.21 | Apache-2.0 | [源码](https://github.com/JetBrains/kotlin/tree/v2.2.21)、[许可](third_party/licenses/Kotlin-LICENSE.txt)、[NOTICE](third_party/licenses/Kotlin-NOTICE.txt) |
| kotlinx.coroutines | 1.10.2 | Apache-2.0 | [源码](https://github.com/Kotlin/kotlinx.coroutines/tree/1.10.2)、[许可与版权](third_party/licenses/Coroutines-LICENSE.txt) |
| kotlinx.serialization core | 1.7.3 | Apache-2.0 | [源码](https://github.com/Kotlin/kotlinx.serialization/tree/v1.7.3)、[许可与版权](third_party/licenses/Serialization-LICENSE.txt) |
| JetBrains Java annotations | 23.0.0 | Apache-2.0 | [源码](https://github.com/JetBrains/java-annotations/tree/23.0.0)、[许可与版权](third_party/licenses/JetBrains-Annotations-LICENSE.txt) |
| JSpecify | 1.0.0 | Apache-2.0 | [源码](https://github.com/jspecify/jspecify/tree/v1.0.0)、[许可](third_party/licenses/JSpecify-LICENSE.txt) |
| Guava ListenableFuture | 1.0 | Apache-2.0 | [源码](https://github.com/google/guava)、[版权](third_party/licenses/Guava-NOTICE.txt)、[许可全文](LICENSE) |
| FFmpeg / FFprobe | 9.0.1 | LGPL-2.1-or-later，含单独许可的部分 | [上游](https://ffmpeg.org/)、[许可](third_party/ffmpeg/notices/FFmpeg-LGPL-2.1.txt)、[版权](third_party/ffmpeg/notices/FFmpeg-NOTICE.txt)、[第三方声明](third_party/ffmpeg/notices/FFmpeg-THIRD-PARTY.txt) |
| dav1d | 1.5.4 | BSD-2-Clause，含逐文件版权 | [上游](https://code.videolan.org/videolan/dav1d)、[许可](third_party/ffmpeg/notices/dav1d-BSD-2-Clause.txt)、[第三方声明](third_party/ffmpeg/notices/dav1d-THIRD-PARTY.txt) |
| LLVM compiler runtime / Android NDK components | r28c / Clang 19.0.1 | Apache-2.0 WITH LLVM-exception 及各组件原始许可 | [LLVM 声明](third_party/ffmpeg/notices/LLVM-NOTICE.txt)、[NDK 声明](third_party/ffmpeg/notices/Android-NDK-SYSROOT-NOTICE.txt) |

完整 Maven 坐标（含版本）与审查过的许可文件映射在 [open-source-components.json](third_party/open-source-components.json)。构建按 Debug / Release 实际解析的运行时依赖分别生成清单，包括传递依赖；Debug 的 UI 调试工具仅列入 Debug。AAR/JAR 自带的 LICENSE、NOTICE、COPYING、COPYRIGHT 等文件逐份原样保留在应用 assets 并可阅读。新增或升级坐标如未审查，构建会失败。

## FFmpeg 对应源码和重建

本软件使用 FFmpeg，依 LGPL 2.1 或后续版本分发。This software uses FFmpeg under the GNU Lesser General Public License version 2.1 or later.

This software is based in part on the work of the Independent JPEG Group.

FFmpeg 和 dav1d 静态链接在独立的原生可执行文件中；Natsuko 通过子进程调用，应用本身不链接 FFmpeg 库。未启用 GPL/nonfree 组件。源码未修改，构建配置、完整原始源码归档、源码校验和与构建脚本均随仓库提供。

每次通过 `scripts/package.sh` 打包，同时生成 `natsuko-third-party-sources-<版本>.tar.gz`，含上述对应源码和重建资料。分发 APK/AAB 时，应同时提供该压缩包及本文件、LICENSE、NOTICE；下载页应明确 FFmpeg 的使用和 LGPL 许可，并提供同一发行版对应源码包的下载入口。不能仅给出指向上游最新版源码的链接。重建及替换方式见 [FFmpeg 构建资料](third_party/ffmpeg/README.md)。接收者可按适用许可修改、重建这些组件；本项目不附加禁止相关逆向调试或修改的条款。

Android 的 libc、libm、libz 由设备系统提供，没有将这些系统共享库复制进 FFmpeg 的应用包；编译时带入的运行时/头文件代码声明仍予以保留。

## 构建和测试工具

Gradle Wrapper 的 Java 源码许可头和 [Gradle Apache 2.0 许可](https://github.com/gradle/gradle/blob/v8.13.0/LICENSE) 适用于仓库内 Wrapper；其原有文件与版权声明保留。Android Gradle Plugin、Kotlin/Compose 编译器、JUnit 和 AndroidX 测试库用于构建/测试，不属于正式应用运行时依赖清单。测试 APK 不作为用户应用发行。

## 图像与商标

根目录 `natsuko.png` 及由它缩放而成的图标使用《全修。》角色広瀬ナツ子的图像。这些图像不包含在项目 Apache-2.0 授权范围内，名称与图像的权利属于各自权利人。现有工程未包含该图像的授权证明；公开分发前仍需由维护者核实其使用和再分发权限。开源代码许可证不能替代角色图像或商标授权。
