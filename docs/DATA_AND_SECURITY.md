# Android 数据与安全

## 安全边界概览

App 是本地 BYOK 客户端，但“本地”不表示所有内容只留在设备上：用户发起模型、搜索、网页读取或语音功能时，相应内容会发送到用户选择的第三方服务。App 凭据使用 Android Keystore 加密；Room 正文和私有文件依赖 Android 应用沙箱，没有额外的应用层全文加密。

## 应用 ID 迁移

当前应用 ID 和源码 namespace 为 `xyz.mek030399.tokenflow`。旧 `com.tokenflow.chat` 属于另一个 Android 应用身份：

- 新旧包可以并存，但不能互相覆盖升级。
- Room、SharedPreferences、私有附件、知识文件、头像和 Android Keystore 密钥按应用沙箱隔离，不会自动迁移。
- `.tfcfg` 只迁移配置和凭据，不包含会话、消息、收藏、笔记、知识文件或头像，不能作为完整数据迁移方案。
- 在实现并验证专用迁移机制前，不得卸载或清理旧包来“完成迁移”。

## Room 数据库

- 文件名：`tokenflow-local.db`
- 当前 schema：v7
- `exportSchema = true`
- 显式迁移：`1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7`
- 兼容迁移：曾安装预发布 v8 构建时，若数据库结构经完整校验仍等同 v7，则无损回落到 v7；不匹配时拒绝启动，不做破坏性降级
- 不使用 destructive fallback
- schema 快照：`app/schemas/xyz.mek030399.tokenflow.data.TokenFlowDatabase/{3,4,5,6,7}.json`

当前表：

| 表 | 内容 |
| --- | --- |
| `providers` | 供应商配置和凭据引用 |
| `models` | 本地模型配置、协议和能力 |
| `conversations` | 会话、模型选择、归档/置顶和上下文边界 |
| `messages` | 用户/Assistant/工具消息及 metadata |
| `message_attachments` | 附件索引、提取文本和私有路径 |
| `app_settings` | 默认模型、工具、提示词和全局设置 |
| `bookmarks` | Assistant 消息收藏 |
| `notes` | Markdown 笔记及可选来源消息 ID |
| `agents` | 智能体配置 |
| `knowledge_documents` | 知识文件索引、状态和可选 `sourceNoteId` |
| `knowledge_chunks` | 文档分块正文和位置 |
| `knowledge_chunks_fts` | 本地全文检索索引 |
| `cloud_servers` | SSH 地址、固定主机指纹和任务限制，不含私钥 |
| `cloud_mcp_servers` | stdio/HTTP MCP 非敏感定义，不含环境值或请求头值 |
| `cloud_tasks` | 远端任务状态、路径、退出码和产物索引 |
| `cloud_artifact_deliveries` | 产物投递状态、远端/本地缓存路径和错误摘要，不含产物正文 |

### 外键与删除语义

- 删除 provider 会级联删除其 models。
- 删除 model 时，conversation 和 agent 的模型引用设为 `NULL`。
- 删除 conversation 会级联删除 messages；message 再级联删除 attachments 和 bookmarks。
- 删除 knowledge document 会级联删除 chunks 和对应 FTS 数据。
- note 的 `sourceMessageId` 不是外键；删除会话不会删除已创建的笔记。
- knowledge document 的 `sourceNoteId` 是可空唯一索引，不是外键。笔记导入知识库后是独立快照，编辑或删除笔记不影响副本；删除副本后可从原笔记重新导入。
- 删除 cloud server 会删除其 MCP 定义和本地凭据、解绑会话/智能体；任务历史保留服务器名称快照，远端文件和任务目录不会被删除。

### 消息 metadata

User/Assistant 扩展字段保存在消息行内 JSON，而不是独立 Room 列，主要包括：

- 思考与工具过程事件
- 输入、输出、缓存读取/写入 Token usage
- 是否由供应商报告缓存指标
- `KnowledgeCitation` 和用户消息固定的 knowledge chunk IDs
- 视觉兜底生成的附件描述
- 生成回复时的全局助手昵称、内部模型 ID 和真实模型 ID

新增可选字段应提供序列化默认值，确保旧 metadata 可以重新加载。只有关系或查询需要新列时才升级 Room schema。

## 私有文件与缓存

| 位置 | 内容 | 持久性 |
| --- | --- | --- |
| `filesDir/chat_attachments` | 聊天附件副本 | 随消息生命周期管理 |
| `filesDir/knowledge` | 知识原文件 | 随知识文档生命周期管理 |
| `filesDir/avatars/...` | 全局与会话头像 | 持久 |
| `cacheDir/camera_captures` | 相机草稿 | 临时，过期清理 |
| `cacheDir/mimo_tts` | TTS WAV | 可重建缓存 |
| App 私有 SharedPreferences | 密文凭据、显示偏好 | 私有；只有凭据密文受 Keystore 保护 |

Room 数据库、附件正文、知识文件、头像和普通偏好没有额外 AES 全盘加密。设备解锁、root、调试备份漏洞或恶意系统组件仍可能扩大风险；文档和 UI 不应声称“所有本地数据均已加密”。

### 笔记 Markdown 导入导出

- 导入通过系统文档选择器逐次读取一个 `.md`，不申请广泛存储权限；文件必须是严格 UTF-8 且不超过 2 MiB。
- 导入内容会复制为 App 私有 Room 中的新笔记，不保留对原文件 URI 的持续访问；正文仅移除可选的 UTF-8 BOM。
- 导出通过系统文档选择器把单篇笔记正文原样写为 `.md`。导出文件位于用户选择的位置，不再受 App 私有存储和卸载保护，应由用户自行管理访问与备份。
- `.tfcfg` 仍不包含笔记，逐篇导出 `.md` 也不是完整聊天或工作区备份。

## 凭据存储

供应商 API Key、Exa Key、MiMo Key、Infinite Cloud SSH 私钥与口令，以及 MCP 环境变量值和 HTTP 请求头值均由 [`SecretStore`](../app/src/main/java/xyz/mek030399/tokenflow/data/SecretStore.kt) 管理：

- 密文存放于私有 SharedPreferences `tokenflow_secrets_v2`。
- AES 密钥由 Android Keystore 提供，alias 为 `tokenflow_local_secrets_v2`。
- 算法为 AES-GCM，无明文 key 写入 Room 或配置文件。

Android Keystore 只保护 App 内凭据，不是 APK 发布签名 keystore。设备迁移、系统重置或 Keystore 失效后，密文可能无法恢复，应通过重新配置或受密码保护的 `.tfcfg` 恢复。

## 加密配置归档

`.tfcfg` 使用：

- PBKDF2-HMAC-SHA256
- 600,000 次迭代
- 16 字节随机 salt
- AES-256-GCM
- 12 字节随机 IV
- AAD 完整性绑定
- 最短 10 字符密码

归档包括供应商及其 API Key、模型、默认模型、视觉状态/兜底、Exa/MiMo Key、MiMo 音色、全局助手昵称、全局系统提示词、URL Reader、智能体，以及非敏感的 Infinite Cloud 服务器/MCP 定义和已固定主机指纹。

Infinite Cloud SSH 私钥与口令、MCP 环境变量值和 HTTP 请求头值绝不进入 `.tfcfg`。导入后的服务器和 MCP 配置会明确标记为需要重新填写凭据。

归档不包括会话、消息、附件、收藏、笔记、知识文件、头像、Chat 字体/字间距/行间距或其他 UI 偏好。旧格式的 InfoFlow Key 字段只为读取兼容保留，当前不会导出或应用。

归档密码不会保存。`.tfcfg` 本身包含敏感配置，即使已加密也不得提交仓库或通过不受控渠道传播。

## Manifest 与平台权限

[`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) 当前只有 `INTERNET` 权限：

- 相机声明为非必需 feature，拍照交给外部相机应用。
- `FileProvider` 不导出，只临时授权相机草稿 URI。
- `android:usesCleartextTraffic="false"` 默认禁止明文 HTTP；network security config 仅允许 SSH 本地端口转发使用的 loopback 明文连接。
- `android:allowBackup="false"`，并通过 data extraction rules 排除 SharedPreferences、files 和 database 的云备份与设备迁移。
- 不申请 CAMERA、广泛存储或位置权限。
- 笔记 `.md` 导入导出使用系统 Storage Access Framework 的临时 URI 授权，无需存储权限。

## 网络安全边界

### 离线计算工具

`calculate` 和 `convert_units` 的求值与换算只在设备内完成，不读取 Room、不写入文件，也不直接访问网络。它们仍属于模型工具循环：模型供应商会生成工具名称和参数，App 执行后再把结果发送给该模型供应商。最大工具调用数设为 `0` 时，引擎不会向模型提供这些工具或其他工具。

### 模型供应商

Provider Base URL 必须使用 HTTPS，并且不能带 userinfo、query 或 fragment。它是用户明确配置的 API 根地址，允许合法的公网兼容供应商域名。

### URL Reader

URL Reader 面对模型或网页提供的任意 URL，边界更严格：

- 只允许 HTTPS 和 443 端口。
- DNS 解析结果不能包含 loopback、link-local、私网或保留地址。
- 内置 Reader 每次重定向后重新验证目标。
- 限制下载体积和重定向次数。
- WebView 抽取路径禁用 cookie、file/content access 和 mixed content。

不要把 Provider Base URL 的“干净 HTTPS”校验当成 URL Reader 的 SSRF 防护，也不要为了兼容私网供应商而放宽 Reader。

### Infinite Cloud

- SSH 只接受导入的 OpenSSH 私钥认证。首次连接在认证前显示 SHA-256 主机指纹；确认后严格固定，主机密钥变化会阻断连接。
- 远端 helper 不监听网络端口。Shell、Python、JavaScript、SFTP 和 stdio MCP 控制流量均通过已验证的 SSH 会话。
- Streamable HTTP MCP 经 SSH 本地端口转发访问。明文 HTTP 只允许从 App 连接 loopback 隧道端口；HTTPS 保留原始主机名校验。
- 启用后模型无需逐次确认即可执行该 Unix 账号允许的任意命令、联网、文件操作或 `sudo`。App 不提供命令黑名单、目录沙箱或额外权限边界，UI 必须持续明确提示该风险。
- 每次生成前，本次消息的全部附件都会上传到远端请求目录。上传失败会中止生成，不会静默改用本地路径。

## 数据外发清单

| 操作 | 可能发送的数据 | 接收方 |
| --- | --- | --- |
| 模型对话 | 系统提示词、上下文消息、附件图片/提取文本、知识片段、离线计算/单位换算的参数与结果及其他工具结果 | 用户配置的模型供应商 |
| 视觉兜底 | 图片及描述请求 | 用户配置的视觉兜底供应商 |
| Exa 搜索 | 搜索词 | Exa |
| 内置 URL 读取 | 目标 URL、标准 HTTP 请求信息 | 目标网站及网络基础设施 |
| InfoFlow URL 读取 | 目标 URL | 公开 InfoFlow 服务及目标网站 |
| MiMo TTS | 需要合成的 Assistant 文本和音色参数 | Xiaomi MiMo |
| Infinite Cloud | 附件、脚本、命令、文件内容、任务参数与日志 | 用户配置并确认主机指纹的 Linux 服务器 |
| 远端 MCP | MCP 工具参数、文本/结构化结果，以及配置的环境变量或 HTTP 请求头 | 用户配置的远端 MCP 进程或 HTTP endpoint |

App 不需要 TokenFlow 账号，也不会为了对话自动把内容发送到仓库中的 Go 服务。第三方的日志、保留、训练、地域和计费政策不由本 App 控制，取决于用户配置的服务。

## 知识内容安全

- 自动和工具检索只把实际命中片段加入允许集合。
- 注入使用 `<local_knowledge untrusted="true">`，系统提示词明确知识正文不是指令。
- 本地 citation 只有同时存在于 Assistant metadata 允许集合时才可点击，避免模型伪造来源。
- 清空上下文后，旧消息和旧知识片段不再进入新请求。
- 删除知识文档后，历史来源预览返回“来源已不存在”，而不是读取陈旧路径。

## 开发与发布守则

- 不记录 API Key、签名密码、keystore 内容、SSH 私钥/口令或 MCP secret 值。
- 错误日志中避免输出完整请求 body、附件正文、知识片段和 `.tfcfg` 解密内容。
- 数据库升级必须保留旧 schema 快照并提供 migration 测试。
- 新增第三方服务时同步更新本页的数据外发清单、App 内用户协议和依赖清单。
- 发布前检查 Git 中是否误加入 `.jks`、`.keystore`、`.p12`、`.pfx`、`.pem`、`.key`、`.secret`、`.tfcfg` 或 `local.properties`。
