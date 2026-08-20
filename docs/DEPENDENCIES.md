# Android 依赖清单

依赖版本以 [`app/build.gradle.kts`](../app/build.gradle.kts) 为准。工程没有 Version Catalog；插件版本位于根 [`build.gradle.kts`](../build.gradle.kts)，仓库来源位于 [`settings.gradle.kts`](../settings.gradle.kts)。

## 构建工具

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| Gradle Wrapper | 9.4.1 | 可复现构建入口 |
| Android Gradle Plugin | 9.2.1 | Android 构建、打包、Lint、R8 |
| Kotlin Compose plugin | 2.3.10 | Compose 编译 |
| Kotlin Serialization plugin | 2.3.10 | Kotlin 序列化代码生成 |
| KSP plugin | 2.3.11 | Room 编译器代码生成 |
| Compose BOM | 2026.06.01 | 统一 Compose UI、Material 和测试组件版本 |

工程启用 AGP built-in Kotlin、AndroidX、non-transitive R、configuration cache 和并行构建。插件仓库为 Google Maven、Maven Central 和 Gradle Plugin Portal；普通依赖只允许 Google Maven 与 Maven Central，模块不能自行增加仓库。

## App 直接依赖

| 依赖 | 版本 | 用途 | 主要许可证 |
| --- | --- | --- | --- |
| AndroidX Activity Compose | 1.11.0 | Activity 与 Compose 入口 | Apache-2.0 |
| AndroidX Lifecycle runtime/viewmodel Compose | 2.9.4 | 生命周期与 ViewModel | Apache-2.0 |
| Compose UI / Material 3 / Material Icons Extended | BOM 管理 | UI、主题、图标 | Apache-2.0 |
| Media3 ExoPlayer | 1.8.0 | MiMo WAV 播放与 Audio Focus | Apache-2.0 |
| kotlinx-coroutines-android | 1.10.2 | coroutine、Flow、异步任务 | Apache-2.0 |
| kotlinx-serialization-json | 1.9.0 | API、metadata、归档 JSON | Apache-2.0 |
| EvalEx | 3.7.0 | 设备内 BigDecimal 科学表达式求值 | Apache-2.0 |
| OkHttp | 4.12.0 | 模型、工具和 TTS HTTP/SSE | Apache-2.0 |
| AndroidX Room runtime/ktx/compiler | 2.8.4 | SQLite、DAO、Migration、KSP | Apache-2.0 |
| AndroidX ExifInterface | 1.4.2 | 相机图片方向修正 | Apache-2.0 |
| jsoup | 1.23.1 | HTML 解析与正文提取 | MIT |
| pdfbox-android | 2.0.27.0 | PDF 文本提取 | Apache-2.0 |
| Apache POI ooxml/scratchpad | 5.4.1 | Word/Excel 文本提取 | Apache-2.0 |
| commonmark + autolink + GFM tables | 0.24.0 | Markdown、链接和表格 | BSD-2-Clause |

“主要许可证”是维护摘要，不替代各发布 artifact 内的版权与许可证文件。App 会随包分发 [`third_party_notices.md`](../app/src/main/res/raw/third_party_notices.md)，其中列出 `releaseRuntimeClasspath` 最终解析得到的全部直接和传递 artifact 坐标，并提供适用许可证全文及必要 NOTICE；用户可在“关于 > 第三方开源声明”中离线阅读。

`verifyThirdPartyNotices` 会双向比较声明中的反引号坐标与 Release 运行时解析结果：缺失依赖或遗留坐标都会使构建失败。坐标集合来自 Gradle 解析结果，许可证及 NOTICE 文本则应同时核对精确 artifact 缓存和对应版本的上游源代码分发，不能根据 group 名称自动推断。

## 测试依赖

| 依赖 | 版本 | 范围 |
| --- | --- | --- |
| JUnit 4 | 4.13.2 | JVM 单元测试 |
| kotlinx-coroutines-test | 1.10.2 | coroutine/Flow 测试 |
| OkHttp MockWebServer | 4.12.0 | 三协议和工具 HTTP 测试 |
| AndroidX Test Ext JUnit | 1.2.1 | instrumentation runner 集成 |
| Espresso Core | 3.6.1 | Android UI 交互 |
| Room Testing | 2.8.4 | DAO、外键和 migration |
| Compose UI Test JUnit4 | BOM 管理 | Compose semantics/UI 测试 |

Debug 变体还包含 Compose UI tooling 和 test manifest；这些组件不会作为 Release 运行时依赖打包。

## 依赖职责边界

- 三种模型协议没有引入供应商 SDK，由 OkHttp、Kotlin Serialization 和本地 adapter 实现。
- EvalEx 只负责设备内表达式求值；App 在外层限制语法、函数和复杂度，并自行实现受限单位注册表。它不发起网络请求。
- 核心知识检索使用 Room FTS，不依赖向量数据库或 embedding SDK。
- PDFBox/POI 只在设备上提取文本，不负责渲染文档 UI。
- MarkdownRenderer 以 commonmark-java AST 为基础，并在 App 层执行 HTML/链接/citation 安全策略。
- Exa、InfoFlow 和 MiMo 是可选远端服务，不是 Gradle SDK 依赖。

## 升级流程

1. 在独立改动中更新一个依赖域，避免同时升级 AGP、Kotlin、Room 和 Compose。
2. 查阅新版本的 minSdk、JDK、Kotlin/KSP 兼容要求及许可证变化。
3. 若升级 Room 或改变实体，新增显式 migration，并提交新的 schema JSON。
4. 同步更新 App 内 [`third_party_notices.md`](../app/src/main/res/raw/third_party_notices.md)，复核新增或变更组件的许可证、版权声明、嵌入数据许可证和上游 NOTICE。
5. 运行 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest verifyThirdPartyNotices`。
6. 对涉及 UI、数据库、相机、Media3 或 Keystore 的升级，在隔离设备运行对应 instrumentation。
7. Release 构建后检查 R8 warning、包体、启动和 `mapping.txt`。
8. 同步更新本清单和[本地编译环境](LOCAL_BUILD.md)。

默认英文运行时用户协议由 [`app/src/main/res/raw/user_agreement.md`](../app/src/main/res/raw/user_agreement.md) 提供，简体中文本地化文件位于 `app/src/main/res/raw-zh-rCN/user_agreement.md`；[`ABOUT_AND_USER_AGREEMENT_DRAFT.md`](ABOUT_AND_USER_AGREEMENT_DRAFT.md) 仅是历史审阅材料。
