# 发布规范

1. 后续每次修改或增加功能都需要完善根目录 [CHANGELOG.md](../CHANGELOG.md)。未发布前，将改动放在 `## unstaged` 标题下；`unstaged` 与其它版本标题使用相同的二级标题。发布具体版本时，将 `## unstaged` 改为 `## <版本号>`，记录发布日期和实际完成的功能、改进、修复及已知限制。下一次改动时再在顶部添加 `## unstaged`。
2. 根目录 [version.properties](../version.properties) 是版本的唯一来源：`VERSION_NAME` 为 `主版本.次版本.修订号`，必须与更新日志的版本标题一致；`VERSION_CODE` 是 Android 内部正整数版本号，每次发布必须递增，不因版本名调整而回退。不要只通过临时环境变量变更版本。
3. 正式打包前，先完成版本日志、相关功能文档和版本配置；有待发布内容留在 `unstaged` 下时不可正式打包。打包脚本会检查版本、日志和签名，校验不通过时停止。
4. 使用 `./scripts/package.sh` 构建正式包，必须配置专用发布签名；同一应用后续版本复用相同密钥。`./scripts/package.sh --unsigned` 仅用于明确标识的未签名验证构建，不能作为可安装正式包分发。
5. 每次打包执行单元测试与 Lint，并构建 Debug APK、Release APK 和 Release AAB。具备 Android 16 设备/模拟器时，同时执行 `./gradlew connectedDebugAndroidTest`。检查 APK 的包名、版本、最低 Android 版本及发布签名，并核验归档校验和。
6. 产物存放在 `dist/<版本号>/`，附带更新日志、当前版本发布说明、版本元数据、混淆映射和 SHA-256 校验文件。同版本旧目录在成功生成新产物后备份，避免残留的未签名包混入正式包。
7. 如通过 GitHub 发布，tag 使用 `v<版本号>` 并与版本配置一致，发布说明取自当前版本的 CHANGELOG 条目。创建或推送 tag、上传公开 Release 应在明确要求发布到远端时执行；本地正式打包不等同于远端发布。

## 1.0.0 基线

2026-09-14 将当前全部已实现内容确立为正式 `1.0.0`。此前 `dist/1.0.0` 和 `dist/1.1.0` 是本地试打包产物，不据此编写虚构的正式发布历史。由于试打包的内部版本号已使用 1、2，本次 `VERSION_CODE=3`，之后继续递增。

## 下一次发布示例

开发期间在 CHANGELOG 顶部新增：

```markdown
## unstaged

### 修复

- 记录实际修复的用户可见问题。
```

例如在 1.2.1 之后发布 1.2.2 时，将该标题改为 `## 1.2.2`，补上发布日期，并更新 `version.properties`：

```properties
VERSION_NAME=1.2.2
VERSION_CODE=7
```

然后执行正式打包与验证。工具链、签名配置和 CI 详情见 [构建与发布流程](build_release.md)。
