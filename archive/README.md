# 历史资产

本目录保存从原 `TokenFlow` 工作区迁入、但不参与当前 App 构建的历史产物。它们不能替代当前源码、测试或发布归档。

## 旧 Debug APK

`legacy-apks/` 中两个文件来自原仓库 `dist/`。文件名不同，但二进制完全相同：

| 文件 | 实际包名 | 实际版本 | versionCode | SHA-256 |
| --- | --- | --- | --- | --- |
| `TokenFlow-2.4.0-debug.apk` | `com.tokenflow.chat.debug` | `2.4.1-debug` | 10 | `023B7BB39E73F6BDE9D6791A1C679F78DE78011C3D220000A2834AACE75FC0C2` |
| `TokenFlow-2.4.1-debug.apk` | `com.tokenflow.chat.debug` | `2.4.1-debug` | 10 | `023B7BB39E73F6BDE9D6791A1C679F78DE78011C3D220000A2834AACE75FC0C2` |

这些 APK 使用旧应用 ID，与当前 `xyz.mek030399.tokenflow` 无法互相覆盖或共享应用数据。APK 默认被 `.gitignore` 排除，只作为本机历史备份。

## 历史设计输出

`design-output/` 保留原工作区 `output/` 下的 App 截图、Logo 探索稿、提示词记录和 QA 图片，并保持原相对目录结构。它们是过程材料，不是当前 UI 的验收基线；当前设计参考以 `design-references/` 和实际 Compose 实现为准。
