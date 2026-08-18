# 历史资产

本目录保存从原 `TokenFlow` 工作区迁入、但不参与当前 App 构建的历史产物。它们不能替代当前源码、测试或发布归档。

## 来源、作者与许可

- 本目录内容由项目维护者 `mek` 从原 `TokenFlow` 工作区整理并导入本仓库；维护者对其拥有授权权利的整理、提示词、截图和视觉资产按照仓库根目录的 [Apache License 2.0](../LICENSE) 提供。
- `design-output/` 中的 App 界面图片来自历史版本的运行截图或 QA 截图。它们仅记录当时的界面状态，不代表当前版本。
- `design-output/imagegen/tokenflow-logo-options/` 保存 AI 辅助生成的 Logo 探索图；对应的项目提示词和输出文件名记录在 `tokenflow-logo-prompts.jsonl`。仓库未保留具体生成服务或模型的可核验记录，因此不在此推定或补写。
- 如单个文件另有明确的第三方版权或许可标注，以该标注为准。Apache-2.0 不授予项目名称或相关商标的使用权。

这些材料均不是运行时依赖，不会打包进当前 APK。源码分发或 F-Droid 构建可以删除本目录，不影响应用功能或构建结果。

## 旧 Debug APK

`legacy-apks/` 中两个文件来自原仓库 `dist/`。文件名不同，但二进制完全相同：

| 文件 | 实际包名 | 实际版本 | versionCode | SHA-256 |
| --- | --- | --- | --- | --- |
| `TokenFlow-2.4.0-debug.apk` | `com.tokenflow.chat.debug` | `2.4.1-debug` | 10 | `023B7BB39E73F6BDE9D6791A1C679F78DE78011C3D220000A2834AACE75FC0C2` |
| `TokenFlow-2.4.1-debug.apk` | `com.tokenflow.chat.debug` | `2.4.1-debug` | 10 | `023B7BB39E73F6BDE9D6791A1C679F78DE78011C3D220000A2834AACE75FC0C2` |

这些 APK 使用旧应用 ID，与当前 `xyz.mek030399.tokenflow` 无法互相覆盖或共享应用数据。APK 默认被 `.gitignore` 排除，只作为本机历史备份。

## 历史设计输出

`design-output/` 保留原工作区 `output/` 下的 App 截图、Logo 探索稿、提示词记录和 QA 图片，并保持原相对目录结构。它们是过程材料，不是当前 UI 的验收基线；当前设计参考以 `design-references/` 和实际 Compose 实现为准。
