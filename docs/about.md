# 关于与开源许可

## 页面

首页的最后一项为「关于」按钮。打开后显示 Natsuko、实际安装构建的版本名与 Android 内部版本号，以及本应用和所有随包直接/间接开源依赖的名称、版本、许可、项目地址。版本来自 `BuildConfig`，与 `version.properties` 一致；Debug 保留 `-debug` 后缀。

选择条目可离线阅读完整许可证与版权声明；长文本可滚动和选择复制，读取在 IO 线程完成。第三方项目地址由用户主动打开浏览器。Natsuko 自身尚无已确认的公开源码地址，暂不显示该链接；确认后可更新 catalog 中 natsuko 条目的 url。顶部返回按钮和系统返回键都从声明详情回到关于列表，再回到首页。活动重建保留页面/条目，进入关于不会清空最近一次处理结果。

## 许可文件

- 根目录 `LICENSE` 为 Apache 官方完整 2.0 文本；`NOTICE` 指明原创代码的适用范围。
- `THIRD_PARTY_NOTICES.md` 汇总随包组件、对应源码、工具和图标权利边界。
- `third_party/open-source-components.json` 保存已审查的精确 Maven 坐标和原生项目条目。
- `third_party/licenses/` 保存官方上游版权和许可原文；`third_party/ffmpeg/notices/` 与 `third_party/metadata/notices/` 保存随包工具的许可原文。
- `app/open-source.gradle` 在构建时读取各变体的实际 `RuntimeClasspath`，生成 `app/build/generated/openSource/<debug|release>/assets/open_source/index.json` 和离线声明文件。AAR/JAR 内的版权文件原样复制，含嵌套 JAR 内文本。合并而不丢弃公共 META-INF 许可资源。

不在运行时从网络抓取许可证，也不将测试依赖误计为 Release 运行时组件。R8 可能移除未使用的类，清单保守保留该变体全部解析的运行时依赖。

## 新增或升级依赖

1. 查看实际解析的 Debug / Release 运行时依赖（`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`，Debug 同理），含传递依赖。
2. 核对该版本发布的 POM、AAR/JAR、自带原生代码和官方源码内 LICENSE/NOTICE；保留要求的版权和附加条款，不按项目组织或包名前缀推断许可。
3. 在 `third_party/licenses/` 保存缺少的上游声明，更新精确坐标映射和本项目 `THIRD_PARTY_NOTICES.md`。许可证来源地址和版本应可追溯。新增未审查坐标会使构建失败。本地 `files()` / `fileTree()` JAR/AAR 没有已登记坐标时也会被拒绝，不能绕过许可审查。
4. 原生组件升级同时更新完整源码、构建参数、校验和、对应许可及 catalog；不得只修改页面显示版本。
5. 执行下列验证；将用户可见变化记录到 CHANGELOG 的 `## unstaged`。

## 验证与发布

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 scripts/verify-open-source.py app/build/outputs/apk/debug/app-debug.apk
python3 scripts/verify-ffmpeg.py app/build/outputs/apk/debug/app-debug.apk
python3 scripts/verify-metadata.py app/build/outputs/apk/debug/app-debug.apk
python3 scripts/test_verify_open_source.py
python3 scripts/test_verify_ffmpeg.py
./scripts/test-package.sh
./gradlew connectedDebugAndroidTest
```

设备测试覆盖入口、版本、离线全文、返回导航、活动重建、无效清单/路径、所有许可文件可读取，以及关于导航后保留自动处理的结果。

正式打包随附法律文件和 `natsuko-third-party-sources-<版本>.tar.gz`，两个核验脚本检查 Debug APK / Release APK / AAB 内原生文件、声明全文和依赖清单，失败时保留旧发行目录。来源压缩包须与安装包一同提供下载。详情见 [构建发布](build_release.md)、[第三方声明](../THIRD_PARTY_NOTICES.md) 与 [原生审计](native_licenses.md)。

角色图像授权尚需维护者确认，不得将根 Apache 许可解读为该图像已获授权。
