# 独立项目迁移记录

## 迁移结果

- 迁移日期：2026-08-17
- 来源：`C:\Users\Mek\Works\TokenFlow\android`
- 目标：`C:\Users\Mek\Works\TokenFlowApp`
- 工程结构：原 `android/` 内容扁平化为独立仓库根目录
- Gradle 根项目名：`TokenFlowApp`
- 应用 ID / namespace：`xyz.mek030399.tokenflow`
- 迁移时版本：`2.4.2`（`versionCode 11`）

迁移保留了当前工作树中的源码、资源、测试、Room schema、Gradle Wrapper、项目资料、设计参考和本机 `local.properties`。原仓库 Git 历史和 `.git` 没有复制；目标目录在验证完成后单独初始化 `main` 分支，不创建初始提交或远端。

## 排除内容

- Gradle/Kotlin 缓存和所有 `build/` 目录
- Go 服务端、PWA、Docker 和部署文件
- 服务端二进制及 Linux 发布产物
- 无项目引用的临时 Compose `classes.jar`
- 任何外部 Android 发布 keystore、密码或 API Key

旧 Debug APK 和 App 历史截图/Logo 稿移入 `archive/`，不参与构建。旧 APK 的实际属性和校验值见 [`archive/README.md`](../archive/README.md)。

## 数据与兼容性

文件迁移不处理 Android 设备数据。旧 `com.tokenflow.chat` 与当前 `xyz.mek030399.tokenflow` 是两个独立应用身份，Room、SharedPreferences、私有文件和 Android Keystore 数据不会自动迁移。不得通过卸载旧包或清理受保护设备数据完成迁移。

## 后续仓库基线

- 新仓库以当前迁移后的完整文件状态作为首个未来提交的候选基线。
- `local.properties`、历史 APK、构建输出、签名材料和导出配置均保持忽略。
- 首次提交前应再次运行 JVM 测试、Lint、Debug 和 AndroidTest APK 构建，并检查 `git status --ignored`。
