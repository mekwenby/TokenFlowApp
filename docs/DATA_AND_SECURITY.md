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
- 当前 schema：v12
- `exportSchema = true`
- 显式迁移：`1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 9 -> 10 -> 12`
- 兼容迁移：旧结构预发布 v8 通过 `8 -> 9` 增量升级；已有设备的兼容 v11 通过 `11 -> 12` 前向升级。v8/v11 版本号不再复用。已映射表的结构不匹配时由迁移和 Room schema 校验拒绝启动，不做破坏性重建。
- 不使用 destructive fallback
- schema 快照：`app/schemas/xyz.mek030399.tokenflow.data.TokenFlowDatabase/{3,4,5,6,7,9,10,12}.json`

当前表：

| 表 | 内容 |
| --- | --- |
| `providers` | 供应商配置和凭据引用 |
| `models` | 本地模型配置、协议、能力和可选上下文容量 |
| `conversations` | 会话、模型选择、归档/置顶、上下文策略和知识检索范围 |
| `companion_profiles` | 兼容旧 v11 的资料记录，保留与会话的关联；当前无新增功能入口 |
| `context_summaries` | 会话摘要正文、来源范围/摘要校验、模型身份、usage 与引用；随会话级联删除 |
| `messages` | 用户/Assistant/工具消息及 metadata |
| `message_attachments` | 附件索引、提取文本和私有路径 |
| `app_settings` | 默认模型、工具、提示词和全局设置 |
| `bookmarks` | Assistant 消息收藏 |
| `notes` | Markdown 笔记及可选来源消息 ID |
| `agents` | 用户保存的智能体配置 |
| `knowledge_documents` | 知识文件索引、状态和可选 `sourceNoteId` |
| `knowledge_chunks` | 文档分块正文和位置 |
| `knowledge_chunks_fts` | 本地全文检索索引 |
| `cloud_servers` | SSH 地址、固定主机指纹和任务限制，不含私钥 |
| `cloud_mcp_servers` | stdio/HTTP MCP 非敏感定义，不含环境值或请求头值 |
| `cloud_tasks` | 远端任务状态、路径、退出码和产物索引 |
| `cloud_artifact_deliveries` | 产物投递状态、远端/本地缓存路径和错误摘要，不含产物正文 |

### 已有 v11 数据库的前向兼容

部分设备已保存 v11 数据库，其结构比 v10 多出 `conversations.kind TEXT NOT NULL DEFAULT 'CHAT'`，以及 `companion_profiles` 表和会话 ID 唯一索引。用仅支持 v10 的版本打开时会触发数据库降级错误，工作区和配置导入也无法正常访问数据库。

当前统一前向升级到 v12：`10 -> 12` 显式添加对应列、表、唯一索引及外键；`11 -> 12` 不改动已有表或数据，升级后由 Room 校验已映射表的列、默认值、外键和索引。结构不兼容的 v11 会拒绝打开，不通过删表、改写数据库版本号或清空数据绕过。

既有 `kind` 值和 companion 记录保持原样，普通会话新增时默认 `CHAT`；保存既有会话时保留原 kind。`companion_profiles` 的 `conversationId` 是到 `conversations.id` 的级联删除外键且唯一，`id` 为整数主键，名称、语音和模型路径文本均保留。这些兼容字段只为继续读取已有数据，不新增 companion 功能 UI，也不进入现有 `.tfcfg` 配置归档。

### 外键与删除语义

- 删除 provider 会级联删除其 models。
- 删除 model 时，conversation 和 agent 的模型引用设为 `NULL`。
- 删除 conversation 会级联删除 messages；message 再级联删除 attachments 和 bookmarks。
- 删除 conversation 也会级联删除其已有 companion profile。
- 删除 knowledge document 会级联删除 chunks 和对应 FTS 数据。
- note 的 `sourceMessageId` 不是外键；删除会话不会删除已创建的笔记。
- knowledge document 的 `sourceNoteId` 是可空唯一索引，不是外键。笔记导入知识库后是独立快照，编辑或删除笔记不影响副本；删除副本后可从原笔记重新导入。
- 删除 cloud server 会删除其 MCP 定义和本地凭据、解绑会话/智能体；任务历史保留服务器名称快照，远端文件和任务目录不会被删除。

### 智能体与提示词快照

App 不再随 APK 分发默认智能体定义或角色提示词；工作区和分享入口从现有 `agents` 表读取用户保存的智能体。创建和 Markdown 导入继续使用同一存储结构。

创建会话时将智能体提示词和设置保存到现有会话结构中，后续修改或删除智能体不会改写既有会话。本次移除预装定义不删除用户保存的智能体、旧版本已保存的自定义副本或会话提示词快照，不清理历史消息，也不需要数据库迁移。

智能体聊天沿用现有模型和工具数据流，完整系统提示词会随请求发送给用户选择的模型供应商。工具按该智能体或会话保存的设置启用，实际可用性继续遵循用户已有服务配置及各自的数据外发边界。

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
| `filesDir/share_drafts` | 分享附件副本、原子保存的会话草稿与分享事件状态 | 已引用草稿持久；取消/发送后清理，闲置孤立副本过期清理 |
| `filesDir/knowledge` | 知识原文件 | 随知识文档生命周期管理 |
| `filesDir/avatars/...` | 全局与会话头像 | 持久 |
| `cacheDir/camera_captures` | 相机草稿 | 临时，过期清理 |
| `cacheDir/mimo_tts` | TTS WAV | 可重建缓存 |
| App 私有 SharedPreferences | 密文凭据、显示偏好 | 私有；只有凭据密文受 Keystore 保护 |

Room 数据库、附件正文、知识文件、头像和普通偏好没有额外 AES 全盘加密。设备解锁、root、调试备份漏洞或恶意系统组件仍可能扩大风险；文档和 UI 不应声称“所有本地数据均已加密”。

### 笔记 Markdown 导入导出

- 导入通过系统文档选择器逐次读取一个 `.md`，不申请广泛存储权限；文件必须是严格 UTF-8 且不超过 2 MiB。
- 导入内容会复制为 App 私有 Room 中的新笔记，不保留对原文件 URI 的持续访问；正文仅移除可选的 UTF-8 BOM。
- 笔记标题和正文在同一 Room 事务中按 64 KiB BLOB 字节块读取，完整拼接后再解码 UTF-8，保留跨块字符和 NUL。短笔记直接使用首批投影，已有大笔记也不会被截断；此读取修复不改变 schema v7。
- 自动笔记标题使用条件更新，生成期间的编辑或删除优先。会话生成仅更新状态及时间字段，异步会话标题不会覆盖期间的手动改名。
- 导出通过系统文档选择器把单篇笔记正文原样写为 `.md`。导出文件位于用户选择的位置，不再受 App 私有存储和卸载保护，应由用户自行管理访问与备份。
- `.tfcfg` 仍不包含笔记，逐篇导出 `.md` 也不是完整聊天或工作区备份。

### 智能体 Markdown 导入

- 通过系统文档选择器临时授权读取单个 `.md`，沿用严格 UTF-8 和 2 MiB 文件读取上限，并在保存前将去除可选 UTF-8 BOM 后的正文限制为 512 KiB（按 UTF-8 字节计）；扩展名大小写不敏感，不申请广泛存储权限。正文上限为后续智能体与会话读取预留 CursorWindow 空间。
- 文件内容仅移除可选的 UTF-8 BOM，以完整文本保存为新自定义智能体的系统提示词，不保留对原始 URI 的持续访问。导入过程不会发起模型请求；之后使用该智能体聊天时，提示词按现有会话规则发送给所选模型供应商。
- 每次导入使用新 UUID，不从 Markdown 恢复原智能体 ID、API Key 或工具配置。导入后的记录使用现有 `agents` 表，并参与现有智能体 `.tfcfg` 归档；无需数据库迁移。

## 凭据存储

供应商 API Key 与自定义请求头、Exa Key、MiMo Key、Infinite Cloud SSH 私钥与口令，以及 MCP 环境变量值和 HTTP 请求头值均由 [`SecretStore`](../app/src/main/java/xyz/mek030399/tokenflow/data/SecretStore.kt) 管理：

- 密文存放于私有 SharedPreferences `tokenflow_secrets_v2`。
- AES 密钥由 Android Keystore 提供，alias 为 `tokenflow_local_secrets_v2`。
- 算法为 AES-GCM，无明文 key 写入 Room 或配置文件。
- 供应商请求头的名称、值、启用状态及模板管理信息按 provider ID 序列化为加密 JSON；Room 不保存头值，本功能无需数据库迁移。停用的配置保留在加密存储中，发送时不使用。

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

归档包括供应商及其 API Key 和自定义请求头、模型、默认模型、视觉状态/兜底、Exa/MiMo Key、MiMo 音色、全局助手昵称、全局系统提示词、URL Reader、智能体，以及非敏感的 Infinite Cloud 服务器/MCP 定义和已固定主机指纹。

供应商请求头可包含鉴权信息，因此完整配置只写入密码保护的归档。旧归档缺少请求头字段时按空配置处理；若将清空同 ID 供应商当前请求头，导入预览先显示提示，确认后以归档配置替换，不隐式合并本机旧头。

智能体归档包含用户保存的智能体，旧版本已保存的自定义副本正常导入导出；导入按稳定 UUID 合并，同名智能体可以并存。移除预装角色不修改已有配置归档，也不依赖 App 提供默认智能体。

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
- 笔记 `.md` 导入导出和智能体 `.md` 导入使用系统 Storage Access Framework 的临时 URI 授权，无需存储权限。
- 当前设备时间、系统时区和电量/充电状态通过系统公开接口读取，无需新增 Android 权限。

## 网络安全边界

### 离线计算工具

`calculate` 和 `convert_units` 的求值与换算只在设备内完成，不读取 Room、不写入文件，也不直接访问网络。它们仍属于模型工具循环：模型供应商会生成工具名称和参数，App 执行后再把结果发送给该模型供应商。最大工具调用数设为 `0` 时，引擎不会向模型提供这些工具或其他工具。

### 设备信息工具

`get_device_time`、`get_device_timezone` 和 `get_device_battery` 仅在模型发起工具调用时读取设备当前时钟、系统时区及电量/充电状态，不启动持续采集任务。它们不需要联网搜索、网页读取配置或新增权限，遵循现有最大工具调用次数设置；设为 `0` 时引擎不会提供这些工具。

读取本身在设备内完成，结果仍属于对话数据：工具调用和结果会进入本地过程记录，结果随模型请求发送给用户选择的模型供应商。手机的时区不等于位置授权，设备时钟也不保证经过网络校准；缺失的电池数据不会替换为猜测值。

### 模型供应商

Provider Base URL 必须使用 HTTPS，并且不能带 userinfo、query 或 fragment。它是用户明确配置的 API 根地址，允许合法的公网兼容供应商域名。

自定义请求头按供应商作用于模型调用和模型列表请求；启用的同名头覆盖默认头，有效且值非空的 `Authorization`、`x-api-key` 或 `api-key` 启用时抑制协议默认鉴权，允许 API Key 字段留空。一次操作开始时固定配置，后续重试和工具循环不重新生成模板标识或读取中途编辑的头。重定向仅限同源（协议、主机和端口均相同），不同源目标会被拒绝。

启用行的名称和值须通过 HTTP 头语法校验，名称修剪首尾空白并按大小写无关的名称拒绝重复；整行空白不保存。停用行保留但不参与发送或阻断发送。协议/传输管理字段 `Host`、`Content-Length`、`Content-Type`、`Connection`、`Transfer-Encoding`、`Content-Encoding`、`Accept-Encoding`、`TE`、`Trailer`、`Upgrade`、`Proxy-Authorization`、`Keep-Alive` 不允许启用自定义值；`Accept`、`User-Agent`、`anthropic-version` 和普通扩展头仍可配置。校验错误不包含头值。

Claude Code、OpenCode 和 Pi 模板只填充静态 HTTP 头；所需标识在用户应用模板时生成一次，再按普通配置保存。它们不更改模型正文、系统提示词或工具定义，不提供其他客户端的身份、会话或服务权限保证。供应商头不发送到 Exa、URL Reader、Infinite Cloud/MCP 或语音端点。

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
- 自动执行指引和“本次必须使用云端”沿用上述账号权限与附件外发边界。必用模式只要求在当前预算内实际发起一次有效 Cloud/MCP 调用，不证明远端操作成功；只有真实工具结果可以支持完成声明。它不会绕过工具预算或扩大后台任务权限。
- 必用阶段只允许当前生成已暴露的 Cloud/MCP 工具，执行前校验整批调用名称；内置 Cloud 工具还校验字段、类型和数值边界，MCP 参数必须是 JSON 对象，具体工具参数由远端 MCP 服务校验。供应商不支持强制选择或模型未调用时明确失败，不静默降级为文字回答，不自动重放可能产生副作用的远端操作。用户主动重新生成是新的运行，可能再次操作远端。
- 单条消息的执行模式保存在草稿及消息 metadata 中；旧记录缺少该字段时按自动模式读取，不新增数据库迁移。主机私钥、口令和 MCP secrets 仍只进入 `SecretStore`，不会加入执行提示词。
- 文件浏览器及文件工具删除符号链接时只删除链接，移动时保留链接身份。普通目录仍按原有语义删除；父目录中的链接仍可访问，这不是目录沙箱。

### HTML 预览

- 使用 HTML 解析器区分完整文档和片段，在真实 `head` 的开头插入 App 固定的 CSP，过滤输入中的 CSP、自动刷新、`base` 和重复 viewport。
- 允许内联脚本和样式，以及策略中允许的 HTTPS 图片、样式、字体和媒体；禁止外部脚本、连接、iframe、worker、object、表单提交和顶层导航。预览不承诺完全离线。
- WebView 禁止 file/content 访问及 mixed content。注释或脚本字符串中的伪标签不能改变安全策略的插入位置。

## 数据外发清单

| 操作 | 可能发送的数据 | 接收方 |
| --- | --- | --- |
| 模型对话与模型列表 | 对话所需上下文、附件、工具结果，以及所配置的 API Key 或自定义请求头；模型列表不携带对话正文 | 用户配置的模型供应商 |
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

## 搜索、摘要与分享的数据边界

全局消息搜索只查询本地 Room，不调用模型或外部搜索服务。结果只投影命中片段和定位字段；查看原消息沿用本地会话读取。

上下文摘要也是对话数据，没有额外应用层全文加密。压缩会将选中的历史正文、相关附件内容和已有摘要发送给当前模型供应商；不调用摘要工具，也不会自动执行历史中的指令。自动压缩由会话显式开启，调用 usage 独立记录。清空上下文删除当前摘要，恢复完整上下文关闭自动压缩；两者都不删除原始聊天。摘要、会话策略和草稿不导出到 `.tfcfg`，只有新增的模型容量跟随模型配置导出。

外部分享是未经信任的输入。仅读取内容 URI，不接受外部文件路径；立即把受大小限制的副本放入私有目录，后续不依赖外部授权。分享入口不会自动发送、读取网页或运行工具。API 请求仍由用户点击发送触发，并继承目标会话的既有工具配置与数据外发边界。草稿状态使用私有原子文件保存，不写入日志或 Android 保存状态 Bundle；提交请求 ID 用于恢复时排除已经落盘的用户消息，避免重复草稿。

## 生成通知与知识范围

前台生成与完成通知只显示运行数量和通用状态，不展示提问、回复正文、API Key 或模型参数。通知 Intent 仅携带本地会话 ID，服务不对外导出；停止操作取消现有任务。拒绝通知权限后不会额外向系统通知栏发送完成提示，但前台服务仍受 Android 平台约束。

知识范围保存在本地会话中，不属于 .tfcfg 配置交换数据；范围筛选在 FTS 限额之前执行。范围影响后续检索，不抹除既有会话和摘要中已发送的事实。本次没有加入向量检索、Embedding 服务或离线模型依赖。
