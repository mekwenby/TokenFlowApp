<p align="center">
  <img src="app/src/main/res/drawable-nodpi/tokenflow_logo_on_light.png" width="180" alt="一念通流 Logo">
</p>

# 一念通流 Android App

[![Android CI](https://github.com/mekwenby/TokenFlowApp/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/mekwenby/TokenFlowApp/actions/workflows/android-ci.yml)

一念通流是一款原生 Android AI 客户端，使用 Kotlin、Jetpack Compose、Room 和 OkHttp 构建。应用采用 BYOK（用户自备 API Key）模式，直接连接用户配置的模型与工具服务，不依赖 TokenFlow Go 服务、PWA、`/mobile/v1` 或 `TOKENFLOW_BASE_URL`。

## 主要功能

- 支持 OpenAI Chat Completions、OpenAI Responses 和 Anthropic Messages 兼容接口。
- 提供流式对话、思考与工具过程、停止、重试、分支、上下文清理和多会话并发生成。
- 助手消息显示可在全局设置中修改的昵称，并保留生成回复时实际使用的模型 ID。
- 支持图片、相机、PDF、Word、Excel、纯文本和源码附件，以及视觉模型兜底。
- 内置收藏、Markdown 笔记、智能体和本地知识库；笔记支持逐篇导入和导出 `.md`，知识引用可在 App 内定位并预览原文。
- 模型可调用完全在设备内执行的科学计算器和单位换算工具，无需联网工具服务。
- 可选 Exa 联网搜索、内置或 InfoFlow URL 读取，以及 Xiaomi MiMo 语音生成。
- 支持中英文、深浅色与多套主题、字体排版设置，以及手机、平板和超宽屏布局。
- 会话和工作区数据保存在本地；API Key 通过 Android Keystore 支持的加密存储保护。

完整能力、限制和数值上限见 [功能与限制](docs/FEATURES.md)。

## 当前基线

| 项目 | 当前值 |
| --- | --- |
| 版本 | `2.4.5`（`versionCode 14`） |
| 正式包名 | `xyz.mek030399.tokenflow` |
| Debug 包名 | `xyz.mek030399.tokenflow.debug` |
| 正式证书 SHA-256 | `FEC865BEDC77C742B0E1B3D93A05FCEEBFA6075F46E1C8242B8F2261F0767AFE` |
| Android | `minSdk 26`，`targetSdk 36`，`compileSdk 36` |
| 构建工具 | Gradle `9.4.1`，AGP `9.2.1`，Build Tools `36.0.0` |
| 语言与 UI | Kotlin `2.3.10`，Java 目标 `17`，Compose BOM `2026.06.01` |
| 本地数据库 | Schema v6（AndroidX Room `2.8.4`），显式迁移 `1 -> 2 -> 3 -> 4 -> 5 -> 6` |
| 模型协议 | OpenAI Chat Completions、OpenAI Responses、Anthropic Messages |

版本和 SDK 值以 [`app/build.gradle.kts`](app/build.gradle.kts) 为准；数据库版本以 [`LocalDatabase.kt`](app/src/main/java/xyz/mek030399/tokenflow/data/LocalDatabase.kt) 为准。

## 下载 APK

- 正式版从 [GitHub Releases](https://github.com/mekwenby/TokenFlowApp/releases) 下载。本项目自动上传的 Release 附件只有使用项目正式证书签名的 APK 及其 SHA-256 校验文件；GitHub 还会自动提供对应源码归档。
- 项目已完成 F-Droid 构建适配，计划申请收录到 [F-Droid 主仓库](https://f-droid.org/packages/xyz.mek030399.tokenflow/)，但尚未提交申请。F-Droid 从公开源码独立构建并使用 F-Droid 自己的证书签名，不使用或接触项目正式私钥。
- 每次 `main` 提交和 Pull Request 都会运行 [Android CI](https://github.com/mekwenby/TokenFlowApp/actions/workflows/android-ci.yml)。成功构建的 Debug APK 可在对应运行记录的 Artifacts 区域下载，保留 7 天。Pull Request artifact 可能包含尚未合并的贡献者代码，只能在信任其来源时安装，并且不要录入真实 API Key 或私人数据。

Debug APK 使用 `xyz.mek030399.tokenflow.debug` 包名和临时 Debug 证书，只适合测试，不能作为正式版升级包。正式发布必须先把版本提交合入 `main`，再推送与 `versionName` 完全一致的 `vX.Y.Z` 标签。

GitHub Release 与 F-Droid APK 虽然包名相同，但签名不同，不能相互覆盖安装。切换渠道前必须卸载现有版本；卸载会删除应用私有数据。当前配置导出不包含完整会话、消息、附件、收藏、笔记、知识文件、头像或显示偏好，因此在需要保留这些数据时不要切换安装渠道。

## 从源码构建

### 环境要求

- JDK 17 或更高版本
- Android SDK Platform 36
- Android Build Tools 36.0.0
- Windows 环境使用 PowerShell 7

项目通过已检入的 Gradle Wrapper 固定构建工具版本，不需要安装全局 Gradle。首次构建会联网下载 Wrapper、插件和 Maven 依赖。Android Studio 通常会自动生成本机的 `local.properties`；该文件已被 Git 忽略。

Windows PowerShell 7：

```powershell
# 在仓库根目录执行
.\gradlew.bat --version
.\gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug assembleDebugAndroidTest
```

macOS 或 Linux：

```bash
# 在仓库根目录执行
./gradlew --version
./gradlew testDebugUnitTest --rerun-tasks lintDebug assembleDebug assembleDebugAndroidTest
```

F-Droid 构建服务器使用专用属性生成由 F-Droid 后续签名的未签名 Release APK：

```bash
./gradlew -PfdroidBuild=true assembleRelease --no-configuration-cache
```

`fdroidBuild=true` 仅用于 F-Droid 的源码构建和本地兼容性验证。该模式拒绝任何签名变量，产物不得作为 GitHub 正式版或手工分发包。普通 Release 构建仍然要求完整的四项签名变量。

Debug APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

首次配置环境、SDK 路径和故障处理见 [本地编译环境](docs/LOCAL_BUILD.md)。普通 Release 构建不会使用 Debug 签名，必须先阅读 [签名与发布](docs/SIGNING_AND_RELEASE.md)。

## 首次使用

安装应用后，添加至少一个模型供应商和模型，或导入已有的加密 `.tfcfg` 配置。模型、搜索、网页读取和语音服务的账户、API Key、配额及费用均由用户自行管理。

应用不提供内置模型服务，也不需要配套服务端。使用自定义 Base URL 时，请只连接你信任且有权使用的 HTTPS 服务。

## 数据与安全

- 聊天、收藏、笔记、智能体、知识文件和相关配置主要保存在应用私有存储中。
- 只有在执行相应功能时，请求内容才会按用户配置发送到模型供应商或可选工具服务；离线计算和单位换算不自行联网，但工具参数和结果会作为对话上下文发送给模型供应商。
- API Key 会发送到对应服务进行认证；自定义服务地址的运营者将能够接收相应凭据和请求内容。
- `.tfcfg` 导出配置使用用户设置的密码加密，但不包含完整聊天与工作区数据；逐篇导出的笔记 `.md` 也不是完整备份。
- 不要在 Issue、日志或提交中包含 API Key、keystore、密码、`.tfcfg`、`local.properties` 或私人对话内容。

完整的数据流、存储边界和安全限制见 [数据与安全](docs/DATA_AND_SECURITY.md)。运行时用户协议的唯一源码是 [`app/src/main/res/raw/user_agreement.md`](app/src/main/res/raw/user_agreement.md)。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [本地编译环境](docs/LOCAL_BUILD.md) | JDK、Android SDK、PowerShell 7、Debug 构建和常见问题 |
| [签名与发布](docs/SIGNING_AND_RELEASE.md) | keystore、签名变量、APK/AAB、证书核验和发布清单 |
| [架构说明](docs/ARCHITECTURE.md) | 分层、依赖注入、消息生成、知识检索和响应式 UI |
| [功能与限制](docs/FEATURES.md) | 对话、附件、工作区、知识库、工具、语音和关键上限 |
| [数据与安全](docs/DATA_AND_SECURITY.md) | Room、私有文件、凭据加密、配置归档、网络边界和数据外发 |
| [依赖清单](docs/DEPENDENCIES.md) | 构建插件、完整运行时依赖、版本来源和第三方许可证维护说明 |
| [测试与设备部署](docs/TESTING_AND_DEVICE.md) | JVM、Lint、instrumentation、设备部署和验收矩阵 |
| [独立项目迁移记录](docs/PROJECT_MIGRATION.md) | 来源、迁移边界、历史资产和 Git 重建状态 |
| [设计参考说明](design-references/README.md) | 现有探索稿的用途和非权威边界 |
| [历史资产说明](archive/README.md) | 历史截图和 Logo 探索稿的来源 |

## 目录概览

```text
TokenFlowApp/
├── .github/workflows/               # GitHub CI 和正式 APK 自动发布
├── app/
│   ├── build.gradle.kts             # App 版本、SDK、依赖、签名和构建变体
│   ├── schemas/                     # Room schema 3/4/5/6 快照
│   └── src/
│       ├── main/                    # App 源码和资源
│       ├── test/                    # JVM 单元测试
│       └── androidTest/             # 设备 instrumentation 测试
├── archive/                         # 历史设计输出，不参与构建
├── design-references/               # UI 探索图，仅作参考
├── docs/                            # 项目文档
├── fastlane/metadata/android/       # F-Droid 商店文案、图标和版本说明；截图待申请前补充
├── gradle/wrapper/                  # 固定版本 Gradle Wrapper
├── LICENSE                          # Apache License 2.0
└── README.md                        # 项目入口
```

## 参与贡献

欢迎通过 Issue 报告可复现的问题或讨论改进建议，通过 Pull Request 提交范围明确的修复和功能。提交前请：

1. 保持改动聚焦，不提交本机配置、凭据或构建产物。
2. 遵循现有 Kotlin、Compose、Room 和测试模式。
3. 数据库结构变化必须提供显式迁移，并提交新的 `app/schemas/` 快照；不得添加破坏性回退。
4. 运行完整检查：

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug assembleDebugAndroidTest
```

Instrumentation 测试必须使用可丢弃的隔离 AVD，不要在保留用户数据的设备上运行。

## 许可证

除另有明确标注的第三方内容外，本仓库中由项目维护者拥有授权权利的代码、文档和视觉资产均按照 [Apache License 2.0](LICENSE) 提供。第三方组件继续适用各自的许可证和版权声明；详情见 [依赖清单](docs/DEPENDENCIES.md)，随 APK 分发的完整文本位于 `app/src/main/res/raw/third_party_notices.md`。

Apache License 2.0 不授予项目名称或相关商标的使用权。
