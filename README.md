# Natsuko

Natsuko 是一款只面向 Android 16（API 36）及更高版本的图片与视频处理应用。主页“图片”分类依次提供图片拼接、下载推特视频（GIF）、图片转 PDF、去除黑边、图片调整，另有 PDF 转图片和视频转 GIF；既可从应用内选择文件或输入推文链接，也可在 Android 系统分享面板中直接选择对应功能。

当前正式版本：**1.2.1**（Android 内部版本号 **6**）。功能与改进记录见 [CHANGELOG.md](CHANGELOG.md)，版本配置见 [version.properties](version.properties)，后续更新遵循 [发布规范](docs/release.md)。从首页底部打开「关于」可查看实际安装版本。

## 已实现功能

- 国际化：支持中文和英文；设置 → 语言可选「跟随系统 / 中文 / English」，即时生效并保存选择。默认跟随系统，不支持的系统语言使用英文。详见 [语言设置](docs/i18n.md)。

- 图片拼接：完整等比的整体效果预览、拖动排序、横向/纵向/自动网格布局、等比居中、实时留白颜色、超大图片安全缩放。
- 下载推特视频（GIF）：输入或分享公开 X / Twitter 推文链接，下载可获取的 MP4 视频和动图；完成页可逐个直接进入视频转 GIF，无需重新选择文件。详见 [下载推特视频](docs/download_twitter_video.md)。
- 图片转 PDF：合并或逐图输出、A4/适配图片、自动/横向/纵向、页边距。
- 去除黑边：批量检测并裁去连续的完整黑色边缘，按原始分辨率输出 PNG；选择图片或分享截图后自动处理，无需配置。全部成功后返回首页，可通过“查看结果”打开、保存或分享本次成品。详见 [去除黑边](docs/crop_black_border.md)。
- 图片调整：选择或分享一张图片后进入编辑页，支持自由与固定比例裁剪、−45°～+45° 拉直并自动裁掉空白边角、90° 旋转、左右/上下镜像，以及锁定宽高比的缩小尺寸；撤销、重做与恢复原图覆盖全部编辑。导出为 PNG、JPEG 或 WebP（有损/无损），改变格式或质量不改变像素尺寸，并显示按当前选项实际试编码得到的输出大小。详见 [图片调整](docs/image_adjustment.md)。
- PDF 转图片：多 PDF 批处理、PNG/JPEG、1×–3× 清晰度、逐文件失败报告。
- 视频转 GIF：内置 FFmpeg，支持 MP4/MKV/MOV/WebM 常用容器及 H.264/H.265/AV1 等视频编码；提供自适应调色板、颜色抖动、帧率、采样间隔、最大宽度、循环次数和 300 帧保护上限。详见 [视频转 GIF](docs/video2gif.md)。
- 分享导入：七个独立系统分享目标；“去除黑边”直接开始自动处理，推文文本分享进入下载确认页，“图片调整”进入编辑页（多张时先选择一张），其余功能进入对应配置页。
- 输出管理：默认写入应用专属目录；可在设置中开启“保存至用户存储目录”，自动保存到 `Download/Natsuko` 或自行指定目录下的功能子目录，不再增加时间戳目录。关闭时可在结果页开启同一开关，并立即补存本次结果；支持保存进度、逐文件失败报告和重试。详见 [输出存储](docs/output_storage.md)。
- 颜色模式：在设置的“外观”中随时选择跟随系统、浅色（Light）或深色（Dark）；默认跟随系统，切换立即生效并保留到下次启动，分享导入也使用同一模式。详见 [外观与颜色模式](docs/appearance.md)。

图片、PDF 与 GIF 转换均在设备本地完成。推特下载通过 HTTPS 访问 X 的公开接口与视频 CDN，需要网络；不上传本地媒体，不要求登录，不调用第三方解析站。FFmpeg 与所需解码器随应用安装，安装后即可处理视频，不在运行时下载组件。当前及后续的视频资源处理统一使用 FFmpeg；支持范围由随包构建的组件决定。安装包包含 `arm64-v8a` 和 `x86_64` 两种架构。

默认 Download 输出无需广泛存储权限；更改用户目录时通过系统目录选择器授权。用户目录中的成品可独立于应用保留，应用专属副本会随卸载移除。

## 构建

```bash
./scripts/bootstrap.sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

配置发布签名后构建正式包：

```bash
./scripts/package.sh
```

仅验证构建且不签名时，显式使用 `./scripts/package.sh --unsigned`。产物、发布说明和 SHA-256 校验文件位于 `dist/<version>/`；同版本旧产物自动备份。签名发布、CI 和版本维护详见 [docs/build_release.md](docs/build_release.md)。

## 工程结构

```text
app/src/main/java/com/poppinzhang/natsuko/
├── data/          # URI 元数据、分享导入、输出目录
├── model/         # 功能、选项、进度和处理结果
├── processing/    # 图片/PDF 处理器、视频功能调度
│   └── video/     # 共用的 FFmpeg 视频处理引擎
└── ui/            # Compose 页面、状态和 ViewModel
```

详细设计见 [docs/architecture.md](docs/architecture.md) 与各功能文档。

## 关于与许可证

首页最下方的「关于」显示当前软件版本、随包开源依赖及可离线阅读的完整许可和版权声明，详见 [关于与许可维护](docs/about.md)。

项目原创代码采用 [Apache License 2.0](LICENSE)，适用范围见 [NOTICE](NOTICE)。第三方组件保留各自许可，见 [第三方开源声明](THIRD_PARTY_NOTICES.md)。FFmpeg 采用 LGPL-2.1-or-later，对应源码及重建资料随发布包附带的 `natsuko-third-party-sources-<版本>.tar.gz` 提供。

`natsuko.png` 和派生图标不属于 Apache 授权范围；工程内尚无角色图像授权证明，公开分发前需要核实图像使用和再分发权限。
