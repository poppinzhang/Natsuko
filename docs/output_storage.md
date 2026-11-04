# 输出存储

## 设置与结果页

首页的“设置”入口提供“保存至用户存储目录”开关，默认关闭。开关开启后，各项功能生成的图片、PDF、GIF 和下载的视频自动保存到用户目录；关闭后，新成品只保存在应用专属目录。开关和所选目录在应用重启后保留。关闭开关不会删除已经保存的成品。

默认用户目录为 `Download/Natsuko`。设置中可通过系统目录选择器重新指定目录，或恢复默认目录。单独更改目录不会将关闭的开关开启；开关已开启且仍保留本次结果时，更改或恢复目录会自动将本次成品保存到新目录。历史批次不会自动迁移，旧目录中的成品也不会删除。取消选择或未能取得目录授权时，保留原有设置。

开关关闭且本次处理有成功成品时，“处理完成”页在“保存位置”下方显示“保存至用户存储目录”开关。此处与设置页控制同一持久设置；开启后立即保存本次成功成品，后续处理也自动保存。如果当前自定义目录的授权已失效，需重新选择并授权后才能启用。

保存过程中显示进度，完成后更新实际保存位置。批处理中部分文件无法保存时，其余文件继续保存；失败成品保留应用专属副本，结果页显示逐文件错误并提供重试。重试只保存当前目标目录中尚未成功的成品，不重复复制已经保存到该目录的文件。

“去除黑边”自动处理全部成功后返回首页，成功提示中的“查看结果”可重新打开本批次的保存位置、补存开关和分享操作；最新结果在开始新工作流或重置前保留。若发生处理失败或用户目录保存失败，则直接展示结果页。未检测到黑边和全黑图片也会生成完整尺寸的 PNG 副本，在文件名下方独立显示“未检测到可裁剪黑边（保持原尺寸）”，并遵循同一保存设置。

## 目录结构

用户目录中的成品直接保存在功能子目录，不再按运行时间创建额外目录。应用专属副本仍按批次使用独立的功能/运行子目录，以隔离批次并清理未完成文件；文件命名规则保持不变：

| 功能 | 关闭开关时的应用专属目录 | 默认用户目录 |
| --- | --- | --- |
| 图片拼接 | `Pictures/Natsuko/ImageMerge/<时间戳>/` | `Download/Natsuko/ImageMerge/` |
| 去除黑边 | `Pictures/Natsuko/CropBlackBorder/<时间戳>/` | `Download/Natsuko/CropBlackBorder/` |
| 图片转 PDF | `Documents/Natsuko/ImagesToPdf/<时间戳>/` | `Download/Natsuko/ImagesToPdf/` |
| PDF 转图片 | `Pictures/Natsuko/PdfToImages/<时间戳>/` | `Download/Natsuko/PdfToImages/` |
| 视频转 GIF | `Pictures/Natsuko/VideoToGif/<时间戳>/` | `Download/Natsuko/VideoToGif/` |
| 下载推特视频（GIF） | `Pictures/Natsuko/DownloadTwitterVideo/<时间戳>/` | `Download/Natsuko/DownloadTwitterVideo/` |
| 图片调整 | `Pictures/Natsuko/ImageAdjustment/<时间戳>/` | `Download/Natsuko/ImageAdjustment/` |

图片调整每次保存都在新的应用专属批次目录中生成 `<原文件名>_adjusted.<扩展名>`；用户目录遇到同名文件时由系统分配唯一名称，不覆盖原图或先前成品。导出前的试编码只存放在应用缓存中，不出现在上述目录或结果列表中。

推特视频下载完成后，沿用自动保存、结果页补存和逐文件重试规则。点击“视频转 GIF”时直接读取保留的应用专属视频，生成的新 GIF 独立写入 `VideoToGif` 功能目录并沿用当时的保存设置；源视频及其用户目录副本继续保留。

自定义目录作为输出根目录，直接在其中建立 `<功能>/`，不额外添加 `Natsuko` 或时间戳层级。自动保存、结果页补存和失败重试均使用这一结构。此前已经保存的时间戳目录及其中的成品保留原位，不自动迁移或删除。表中应用专属路径位于 Android 为 Natsuko 管理的外部存储空间，并非用户可见的公共 Pictures 或 Documents 根目录。

## Android 授权与保留规则

默认目录通过 `MediaStore.Downloads` 保存本应用创建的文件，不请求媒体库或全盘存储权限，也不需要弹出存储权限对话框。Android 10 及以上允许应用无存储权限访问自有 Downloads 文件，见 [Android 共享媒体存储文档](https://developer.android.com/training/data-storage/shared/media#storage-permission)。

自定义目录使用 `ACTION_OPEN_DOCUMENT_TREE`，由用户在系统界面授权，并持久保存该目录的读写授权。Android 对可选位置有限制，例如不允许授权整个 Download 根目录；可选择允许的子目录。授权被撤销、目录被移动或删除时，需重新授权或在设置中恢复默认目录，见 [Android 文档与目录授权文档](https://developer.android.com/training/data-storage/shared/documents-files)。

处理器先生成应用专属副本，再由存储层将成品写入用户目录；因此用户目录写入失败不会丢弃已完成的处理结果。写入不完整的用户文件会尝试清理。用户目录保存成功后，结果页通过该文件的 URI 打开或分享；尚未保存成功的结果仍通过应用专属副本打开或分享。

用户目录中的成品独立于应用保留。应用专属副本会随卸载移除，长期保留成品应确认已成功保存至用户目录，见 [Android 应用专属文件文档](https://developer.android.com/training/data-storage/app-specific)。
