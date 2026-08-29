# Android 签名与发布

Release 签名决定 Android 是否允许覆盖升级。`xyz.mek030399.tokenflow` 是独立于旧 `com.tokenflow.chat` 的新应用身份；二者不能互相覆盖。新包首次发布时确定签名身份，此后的所有版本必须继续使用同一身份。

## 当前状态

- Release 应用 ID：`xyz.mek030399.tokenflow`
- 旧应用 ID：`com.tokenflow.chat`（仅作迁移识别，不是新包的升级目标）
- 当前版本：`2.5.1`，`versionCode 18`
- Release 开启 R8 代码压缩和资源收缩。
- 仓库不包含 Release keystore 或密码。首个正式签名身份已于 2026-08-18 建立，证书 SHA-256 为 `FEC865BEDC77C742B0E1B3D93A05FCEEBFA6075F46E1C8242B8F2261F0767AFE`，有效期至 2054-01-03。
- 维护者本机可将签名材料放在已被 Git 忽略并限制访问权限的 `.signing/` 目录；该目录不是源码的一部分，必须另做离线备份。GitHub 自动发布是否可用取决于 `release` Environment 是否已正确配置。
- Debug 使用 Android 默认 Debug 签名；不同开发机生成的 Debug APK 可能无法互相覆盖。

## 签名参数

[`app/build.gradle.kts`](../app/build.gradle.kts) 读取四个同名值：

| 参数 | 含义 |
| --- | --- |
| `TOKENFLOW_KEYSTORE_PATH` | keystore 的绝对路径；推荐放在仓库外，维护者本机也可使用受保护且被忽略的 `.signing/` 目录 |
| `TOKENFLOW_KEYSTORE_PASSWORD` | keystore 密码 |
| `TOKENFLOW_KEY_ALIAS` | key alias |
| `TOKENFLOW_KEY_PASSWORD` | 私钥密码 |

读取优先级为 Gradle property（`-P` 或外部 Gradle 配置）优先，其次是环境变量。四个签名值必须全部提供且非空，或全部未定义；部分提供及空值都会在 Gradle 配置阶段失败。普通构建中，任务名包含 `release`，或任务恰为 `build` / `assemble` 时，四项任一缺失也会失败。

`-PfdroidBuild=true` 是唯一允许在四个签名值全部缺失时构建 Release 的入口。该模式专供 F-Droid 从源码生成未签名产物；如果进程中出现任意一个 TokenFlow 签名值，配置会立即失败，防止私钥意外进入 F-Droid 构建环境。属性缺失或为 `false` 时仍执行普通 Release 签名规则，其他属性值无效。

不要把这些值写入：

- `app/build.gradle.kts`
- 已跟踪的 `gradle.properties`
- 根目录 `local.properties`（签名逻辑也不会读取它）
- README、Issue、构建日志或聊天记录

## 已发布项目：先找回对应 keystore

如果 `xyz.mek030399.tokenflow` 已经发布，发布前必须取得它对应的原 keystore，并用历史安装包或发布登记核对证书 SHA-256。旧 `com.tokenflow.chat` 的证书不能改变应用 ID，也不能让两个包互相覆盖。若新包的 keystore 丢失：

- 普通侧载发布无法用新证书覆盖已有安装。
- 不得通过卸载受保护设备上的 App 绕过签名冲突，这会丢失本地数据。
- 若使用 Google Play App Signing，能否恢复或轮换取决于 Play Console 中的实际配置；本仓库不能证明该状态。

`C:\Users\<用户名>\.ssh\LotusSSL` 是后端 SSH 私钥，不是 APK 签名 keystore；Android Keystore 中的 App 凭据加密密钥也不是发布签名。

## 仅限新包尚未发布：创建 keystore

`xyz.mek030399.tokenflow` 已经对外发布过时跳过本节并复用原 keystore。尚未发布时可在仓库外创建全新签名身份：

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair `
  -keystore 'D:\secure\tokenflow-release.p12' `
  -alias 'tokenflow-release' `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000 `
  -storetype PKCS12
```

让 `keytool` 交互式询问密码，不要把密码作为命令行参数。若因本机维护需要把 keystore 放在项目目录内，只能使用被 `.gitignore` 明确排除并限制访问权限的 `.signing/`，同时在项目目录外保存独立备份。`TOKENFLOW_KEYSTORE_PATH` 始终使用绝对路径；相对路径会以 `app/` 模块目录为基准，容易误指向其他文件。

## GitHub 自动构建与发布

仓库包含两条 GitHub Actions 工作流：

- [`android-ci.yml`](../.github/workflows/android-ci.yml) 在 Pull Request 和 `main` 提交上运行完整 Debug 门禁、第三方许可证声明校验和 F-Droid 未签名 Release 构建，并把 Debug APK 作为保留 7 天的 Actions artifact。CI 使用 Build Tools 36 的 `aapt2` 确认产物有效，并要求 `apksigner` 确认它没有签名；未签名 Release 只作构建验证，不上传。该工作流不引用签名 Secrets。
- [`android-release.yml`](../.github/workflows/android-release.yml) 只接受稳定格式的 `vX.Y.Z` 标签。标签提交必须已经包含在 `main` 中，且发布前远端标签仍须指向完成签名构建的同一提交；验证、签名和产物检查全部成功后，工作流才会公开 GitHub Release。

Release 工作流仅构建和公开 APK，不构建或发布 AAB。公开附件固定为 `TokenFlow-X.Y.Z.apk` 和 `TokenFlow-X.Y.Z.apk.sha256`。R8 `mapping.txt` 单独作为保留 90 天的 Actions artifact，不会附到公开 Release；维护者必须在到期前下载并保存到受控的长期归档。

### 配置 release Environment

在 GitHub 仓库的 **Settings > Environments** 中创建名为 `release` 的 Environment。本项目选择标签触发后全自动发布，因此不配置 Required reviewers。Environment 中添加以下 Secrets：

| Secret | 内容 |
| --- | --- |
| `TOKENFLOW_KEYSTORE_BASE64` | 原正式 keystore 文件的 Base64 文本 |
| `TOKENFLOW_KEYSTORE_PASSWORD` | keystore 密码 |
| `TOKENFLOW_KEY_ALIAS` | 原正式 key alias |
| `TOKENFLOW_KEY_PASSWORD` | 私钥密码 |

再添加 Environment variable `TOKENFLOW_CERT_SHA256`，值为原正式证书的 SHA-256：删除冒号和空白后必须正好是 64 位十六进制字符。证书指纹是公开信息，不是密码。

在 PowerShell 7 中读取受保护的本地 keystore 并复制 Base64 文本：

```powershell
$TokenFlowKeystorePath = Read-Host -Prompt 'Release keystore path'
$TokenFlowKeystoreBase64 = [Convert]::ToBase64String(
  [IO.File]::ReadAllBytes($TokenFlowKeystorePath)
)
$TokenFlowEncodedBytes = [Text.Encoding]::UTF8.GetByteCount($TokenFlowKeystoreBase64)

if ($TokenFlowEncodedBytes -gt 48KB) {
  throw 'Base64 keystore exceeds the GitHub Actions 48 KB secret limit.'
}

$TokenFlowKeystoreBase64 | Set-Clipboard
```

将剪贴板内容保存为 `TOKENFLOW_KEYSTORE_BASE64` 后立即清理：

```powershell
Set-Clipboard -Value ''
Remove-Variable TokenFlowKeystoreBase64, TokenFlowEncodedBytes -ErrorAction SilentlyContinue
```

GitHub 单个 Actions Secret 上限为 [48 KB](https://docs.github.com/en/actions/reference/security/secrets)。超过限制时停止配置和发布，不得把明文 keystore 提交到仓库。GitHub Secrets 也不能作为 keystore 的唯一备份。

工作流会将 Base64 内容解码到 runner 的临时目录、限制文件权限，并在构建步骤结束后清理。密码只通过环境变量传给 `assembleRelease --no-configuration-cache`，不会写入 Gradle 参数、artifact 或发布附件。

### 限制标签权限

在 GitHub 仓库 Rulesets 中为 `v*` 标签建立规则，只允许维护者创建或修改。工作流会再次验证标签必须符合 `vX.Y.Z` 且指向 `main` 中已有提交，但这不能代替 GitHub 侧的标签权限控制。

不要移动、删除后复用或强制更新已经发布的版本标签。修复发布内容时应递增 `versionCode` 和版本号后创建新标签。

### 发布新版本

每次自动正式发布都按以下顺序执行：

1. 确认 `release` Environment、四个 Secrets、证书指纹变量和标签 Ruleset 已正确配置。
2. 在 `app/build.gradle.kts` 中递增 `versionCode`、设置新的 `versionName`，并同步当前版本文档。
3. 运行完整本地门禁，提交并推送 `main`，等待 Android CI 成功。
4. 在已通过 CI 的提交上创建并推送与 `versionName` 一致的标签。以 `2.5.1` 为例：

```powershell
git tag -a v2.5.1 -m '一念通流 2.5.1'
git push origin v2.5.1
```

Release 工作流会重新运行完整 Debug 门禁，构建签名 APK，并依次核对：

- `output-metadata.json` 中的应用 ID、Release variant、`versionName`、`versionCode` 和 APK 文件名；
- `apksigner` 返回的签名有效性和证书 SHA-256；
- `aapt2` 返回的包名和版本；
- APK 的 SHA-256 校验文件。

尚无公开同名 Release 时，发布 job 会先创建或复用草稿，只在两个附件上传并复核成功后转为公开 Release。重跑遇到已公开且附件完全一致的 Release 时只验证并成功退出；附件不同则失败，绝不会覆盖公开版本。

## 在当前 PowerShell 会话注入凭据

路径和 alias 可直接设置；密码使用遮罩输入，避免出现在命令历史中：

```powershell
$env:TOKENFLOW_KEYSTORE_PATH = 'D:\secure\tokenflow-release.p12'
$env:TOKENFLOW_KEY_ALIAS = 'tokenflow-release'
$env:TOKENFLOW_KEYSTORE_PASSWORD = Read-Host -Prompt 'Keystore password' -MaskInput
$env:TOKENFLOW_KEY_PASSWORD = Read-Host -Prompt 'Key password' -MaskInput
```

GitHub Actions 的注入方式见上面的自动发布章节：keystore 文件路径由 workflow 在 runner 临时目录中生成，不能把本机绝对路径保存为 GitHub Secret。虽然 Gradle 支持同名 `-P` 参数，但密码可能进入 shell 历史和进程参数，默认不推荐。

构建结束后清理当前进程环境：

```powershell
Remove-Item Env:\TOKENFLOW_KEYSTORE_PATH -ErrorAction SilentlyContinue
Remove-Item Env:\TOKENFLOW_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:\TOKENFLOW_KEY_ALIAS -ErrorAction SilentlyContinue
Remove-Item Env:\TOKENFLOW_KEY_PASSWORD -ErrorAction SilentlyContinue
```

## 本地手动构建 Release

```powershell
cd C:\Users\Mek\Works\TokenFlowApp
.\gradlew.bat testDebugUnitTest lintDebug
.\gradlew.bat assembleRelease
.\gradlew.bat bundleRelease
```

输出：

- APK：`app/build/outputs/apk/release/app-release.apk`
- AAB：`app/build/outputs/bundle/release/app-release.aab`
- R8 mapping：`app/build/outputs/mapping/release/mapping.txt`

`mapping.txt` 对线上混淆堆栈的还原很重要。它位于被 Git 忽略的构建目录，每个版本都应与 APK 和可选 AAB 一起保存到受控的外部发布归档。GitHub Actions 中的 90 天 artifact 只是临时保留，不是长期归档。

## F-Droid 构建与渠道签名

F-Droid 构建服务器不接收本项目的 Release keystore。它使用下面的命令从已标记的源码构建未签名 Release，随后由 F-Droid 基础设施使用独立证书签名：

```powershell
.\gradlew.bat -PfdroidBuild=true assembleRelease --no-configuration-cache
```

不要为该命令设置 `TOKENFLOW_KEYSTORE_PATH`、`TOKENFLOW_KEYSTORE_PASSWORD`、`TOKENFLOW_KEY_ALIAS` 或 `TOKENFLOW_KEY_PASSWORD`，也不要在 F-Droid 元数据中配置本项目私钥。F-Droid APK 与 GitHub Release APK 的证书不同，两个渠道各自形成独立升级链；从一个渠道切换到另一个渠道时，Android 会拒绝直接覆盖安装。切换前应先导出可导出的配置，并明确当前版本不能完整导出全部工作区数据。

`verifyThirdPartyNotices` 会解析 `releaseRuntimeClasspath` 的实际 artifact，并与 App 内 `third_party_notices.md` 中反引号包裹的 `group:artifact:version` 坐标双向比较。新增、删除或升级运行时依赖时，必须同步更新 notices，否则 CI 失败。

## 验证签名和产物

设置本机工具路径：

```powershell
$TokenFlowBuildTools = 'C:\Users\Mek\AppData\Local\Android\Sdk\build-tools\36.0.0'
$TokenFlowReleaseApk = '.\app\build\outputs\apk\release\app-release.apk'
```

验证 APK 签名并打印证书：

```powershell
& "$TokenFlowBuildTools\apksigner.bat" verify --verbose --print-certs $TokenFlowReleaseApk
```

检查包名、版本和 SDK：

```powershell
& "$TokenFlowBuildTools\aapt2.exe" dump badging $TokenFlowReleaseApk
```

生成交付文件 SHA-256：

```powershell
Get-FileHash -Algorithm SHA256 $TokenFlowReleaseApk
```

查看 keystore 公开证书信息：

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -list -v `
  -keystore $env:TOKENFLOW_KEYSTORE_PATH `
  -alias $env:TOKENFLOW_KEY_ALIAS
```

发布前必须把 `apksigner` 输出的证书 SHA-256 与上一正式版本逐字比较。证书指纹可登记在内部发布记录中；密码、私钥内容和 keystore 文件绝不能入库。

## 发布清单

- 确认 `versionCode` 严格高于已发布版本，`versionName` 与发布说明一致。
- 运行 JVM 测试、Lint、Debug 构建和 AndroidTest APK 构建。
- 用原 Release keystore 构建 APK；仅在发布渠道需要时额外构建 AAB。
- 运行 `apksigner verify --verbose --print-certs`，并与历史正式证书指纹比对。
- 用 `aapt2 dump badging` 核对包名、版本、minSdk 和 targetSdk。
- 记录 APK 和可选 AAB 的 SHA-256、构建时间、Git commit 和公开证书指纹。
- 外部归档 APK、可选 AAB、`mapping.txt`、发布说明和校验值。
- 自动发布时确认项目上传的 GitHub Release 附件只包含已验证 APK 和对应 `.sha256`，且两者校验一致；GitHub 自动生成的源码归档不计入该附件检查。
- 在非生产数据设备上验证从上一正式版覆盖升级，不得以全新安装代替升级测试。
- 对受保护设备只执行 [测试与设备部署](TESTING_AND_DEVICE.md) 中的 `adb install -r` 流程。

## 密钥保管

- keystore 至少保留两份位于不同介质的加密备份，并定期验证可读取性。
- 密码与 keystore 分开保管，限制访问权限，记录保管责任和恢复流程。
- 对外只登记证书公开信息，不登记密码或私钥内容。
- 计划轮换前先确认发布渠道是否支持签名升级，不要在发布当天临时更换。
- `.jks`、`.keystore`、`.p12`、`.pfx`、`.pem`、`.key` 和构建输出虽已被 `.gitignore` 排除，仍需在提交前检查 `git status`。
