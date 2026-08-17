# 一念通流 Android App

本仓库是一念通流的独立原生 Kotlin + Jetpack Compose 客户端。App 采用 BYOK（用户自备 API Key）模式，直接连接用户配置的模型供应商，不依赖 TokenFlow Go 服务、`/mobile/v1` 或 `TOKENFLOW_BASE_URL`。

## 当前基线

| 项目 | 当前值 |
| --- | --- |
| 版本 | `2.4.2`（`versionCode 11`） |
| 正式包名 | `xyz.mek030399.tokenflow` |
| Debug 包名 | `xyz.mek030399.tokenflow.debug` |
| Android | `minSdk 26`，`targetSdk 36`，`compileSdk 36` |
| 构建工具 | Gradle `9.4.1`，AGP `9.2.1`，Build Tools `36.0.0` |
| 语言与 UI | Kotlin `2.3.10`，Java 目标 `17`，Compose BOM `2026.06.01` |
| 本地数据库 | Room v5，显式迁移 `1 -> 2 -> 3 -> 4 -> 5` |
| 模型协议 | OpenAI Chat Completions、OpenAI Responses、Anthropic Messages |

版本和 SDK 值以 [`app/build.gradle.kts`](app/build.gradle.kts) 为准；数据库版本以 [`LocalDatabase.kt`](app/src/main/java/xyz/mek030399/tokenflow/data/LocalDatabase.kt) 为准。

`2.4.2` 起，平板横屏及大字体模式下的会话功能入口和工作区导航均使用独立滚动区域，底部页面不会再因可用高度不足而被裁切。

## 快速开始

Windows 上使用 PowerShell 7：

```powershell
cd C:\Users\Mek\Works\TokenFlowApp
.\gradlew.bat --version
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Debug APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

首次配置环境、SDK 路径和故障处理见 [本地编译环境](docs/LOCAL_BUILD.md)。Release 构建不会使用 Debug 签名，必须先阅读 [签名与发布](docs/SIGNING_AND_RELEASE.md)。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [本地编译环境](docs/LOCAL_BUILD.md) | JDK、Android SDK、PowerShell 7、Debug 构建和常见问题 |
| [签名与发布](docs/SIGNING_AND_RELEASE.md) | keystore、四个签名变量、APK/AAB、证书核验、备份与发布清单 |
| [架构说明](docs/ARCHITECTURE.md) | 分层、依赖注入、消息生成、知识检索和响应式 UI |
| [功能与限制](docs/FEATURES.md) | 对话、附件、工作区、知识库、工具、语音和关键上限 |
| [数据与安全](docs/DATA_AND_SECURITY.md) | Room、私有文件、凭据加密、配置归档、网络边界和数据外发 |
| [依赖清单](docs/DEPENDENCIES.md) | 构建插件、直接依赖、版本来源和许可证维护说明 |
| [测试与设备部署](docs/TESTING_AND_DEVICE.md) | JVM/Lint/instrumentation、受保护设备覆盖安装和验收矩阵 |
| [独立项目迁移记录](docs/PROJECT_MIGRATION.md) | 来源、迁移边界、历史资产和 Git 重建状态 |
| [关于页与用户协议历史审阅稿](docs/ABOUT_AND_USER_AGREEMENT_DRAFT.md) | 仅保留审阅记录，不是运行时协议正文的权威来源 |
| [设计参考说明](design-references/README.md) | 现有探索稿的用途和非权威边界 |
| [历史资产说明](archive/README.md) | 旧 APK、截图和 Logo 探索稿的来源与校验值 |

运行时用户协议的唯一源码是 [`app/src/main/res/raw/user_agreement.md`](app/src/main/res/raw/user_agreement.md)，不要从审阅稿复制回 App。

## 目录概览

```text
TokenFlowApp/
├── app/
│   ├── build.gradle.kts             # App 版本、SDK、依赖、签名和构建变体
│   ├── schemas/                     # Room schema 3/4/5 快照
│   └── src/
│       ├── main/                    # App 源码和资源
│       ├── test/                    # JVM 单元测试
│       └── androidTest/             # 设备 instrumentation 测试
├── archive/                         # 旧 APK 和历史设计输出，不参与构建
├── design-references/               # UI 探索图，仅作参考
├── docs/                            # 项目资料
├── gradle/wrapper/                  # 固定版本 Gradle Wrapper
├── AGENTS.md                        # 独立 Android 工程协作规范
└── README.md                        # 本入口
```

## 重要安全约束

- 不要提交 keystore、签名密码、API Key、`.tfcfg`、`local.properties` 或其他私钥材料。
- `xyz.mek030399.tokenflow` 与旧 `com.tokenflow.chat` 是两个独立应用身份，无法互相覆盖或直接继承 Room、私有文件和 Android Keystore 数据。
- 新包首次发布后必须持续使用同一签名身份；更换或遗失 keystore 会导致该新包的现有安装无法覆盖升级。
- `127.0.0.1:5557` 是保留用户数据的部署目标，只允许 `adb install -r` 覆盖安装；不得卸载、清数据、安装测试 APK 或运行 instrumentation。
- 截至 2026-08-17，`emulator-5556` 与 `127.0.0.1:5557` 的 boot ID 相同，是同一 Android 实例的两个别名，不能把前者当作隔离测试设备。
