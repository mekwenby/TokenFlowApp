# Android 测试与设备部署

本文把“验证代码”和“向保留用户数据的设备部署”严格分开。`127.0.0.1:5557` 只用于覆盖安装和人工冒烟，不是 instrumentation 测试目标。

## 自动化测试概况

截至 2026-08-17，源码中有：

- 17 个 JVM 测试文件，136 个 `@Test`
- 11 个 instrumentation 测试文件，61 个 `@Test`

计数会随代码演进变化；是否通过以 Gradle 实际结果为准。

### JVM 主要覆盖

- OpenAI Chat、OpenAI Responses、Anthropic 请求、SSE、重放、工具和缓存 usage
- 引擎重试/降级、上下文边界与 ViewModel 多会话并发
- Exa、URL Reader、InfoFlow、SSRF 校验和输出限制
- `.tfcfg` 加密、归档校验和导入冲突策略
- 知识自动检索、引用传播、伪造标记和 Markdown 安全
- Token/排版、主题、笔记重写校验和工作区状态

### Instrumentation 主要覆盖

- Room 外键、级联删除和 `1 -> 5` / `4 -> 5` migration
- 配置原子合并、SecretStore 和回滚
- 附件、相机 EXIF、头像和显示偏好
- 知识预取、引用、笔记快照与并发幂等
- Compose 主要工作流和 Media3 播放状态

## 本地门禁

从项目根目录执行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

这会验证 JVM 测试和 Lint，并构建 App/Test APK，但不会连接设备。

完整仓库门禁还包括从仓库根目录执行：

```powershell
go test ./...
npm test
```

Go 和网页测试不替代 Android 测试，Android 测试也不覆盖真实第三方供应商的在线行为。

## Instrumentation 只能使用隔离设备

不要在受保护实例连接时运行无目标的 `connectedDebugAndroidTest`。该任务会枚举设备，并可能在测试安装/清理阶段影响不应触碰的包。

截至 2026-08-17，当前两个可见序列号：

```text
127.0.0.1:5557
emulator-5556
```

二者报告相同 product/model 和相同 `/proc/sys/kernel/random/boot_id`，是同一 Android 实例的两个 ADB 别名。因此当前两者都禁止 instrumentation。必须启动一个具有不同 boot ID 的独立 AVD。

### 运行前验证不是同一实例

```powershell
$TokenFlowAdb = 'C:\Users\Mek\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$TokenFlowProtectedSerial = '127.0.0.1:5557'
$TokenFlowTestSerial = 'emulator-XXXX'

$TokenFlowProtectedBootId = (& $TokenFlowAdb -s $TokenFlowProtectedSerial shell cat /proc/sys/kernel/random/boot_id).Trim()
$TokenFlowTestBootId = (& $TokenFlowAdb -s $TokenFlowTestSerial shell cat /proc/sys/kernel/random/boot_id).Trim()

if ($TokenFlowProtectedBootId -eq $TokenFlowTestBootId) {
    throw 'Test target is an alias of the protected Android instance.'
}
```

确认测试 AVD 不含需要保留的数据后，显式指定序列号：

```powershell
& $TokenFlowAdb -s $TokenFlowTestSerial install -r -t '.\app\build\outputs\apk\debug\app-debug.apk'
& $TokenFlowAdb -s $TokenFlowTestSerial install -r -t '.\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
& $TokenFlowAdb -s $TokenFlowTestSerial shell am instrument -w `
  'xyz.mek030399.tokenflow.debug.test/androidx.test.runner.AndroidJUnitRunner'
```

测试完成后的包清理由隔离 AVD 自己承担，不对 `127.0.0.1:5557` 执行卸载或 `pm clear`。

## 受保护设备覆盖安装

目标：`127.0.0.1:5557`

允许：

- `adb connect`
- 查询包信息
- `adb install -r` 覆盖 Debug APK
- 显式启动 App 和人工冒烟

禁止：

- `adb uninstall`
- `adb shell pm clear`
- 安装 AndroidTest APK
- `am instrument` 或 `connectedDebugAndroidTest`
- 用 `-d` 强行降级
- 遇到签名冲突后通过卸载绕过

### 部署步骤

```powershell
$TokenFlowAdb = 'C:\Users\Mek\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$TokenFlowDevice = '127.0.0.1:5557'
$TokenFlowDebugApk = '.\app\build\outputs\apk\debug\app-debug.apk'

& $TokenFlowAdb connect $TokenFlowDevice
# 首次安装新包时可能查不到包信息；保留输出用于区分首次安装和后续覆盖。
& $TokenFlowAdb -s $TokenFlowDevice shell dumpsys package xyz.mek030399.tokenflow.debug |
  Select-String 'versionName|versionCode|firstInstallTime|lastUpdateTime'

& $TokenFlowAdb -s $TokenFlowDevice install -r -t $TokenFlowDebugApk
& $TokenFlowAdb -s $TokenFlowDevice shell am start -n `
  'xyz.mek030399.tokenflow.debug/xyz.mek030399.tokenflow.MainActivity'

& $TokenFlowAdb -s $TokenFlowDevice shell dumpsys package xyz.mek030399.tokenflow.debug |
  Select-String 'versionName|versionCode|firstInstallTime|lastUpdateTime'
```

`xyz.mek030399.tokenflow.debug` 首次安装时会创建全新的应用沙箱；后续再次执行 `install -r` 时，其 `firstInstallTime` 应保持不变、`lastUpdateTime` 应更新。当前设备原有基线是旧包 `com.tokenflow.chat.debug`，它不会被新包覆盖或迁移，新旧 Debug 包可以并存。

若返回 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，说明 APK 签名与已安装包不同。停止部署并找回同签名 APK/keystore；不得卸载。若版本号更低，重新构建正确版本，不使用 `-d`。

正式 APK 的包名是 `xyz.mek030399.tokenflow`，与 Debug 包不同。部署前必须先确认目标包和历史签名，不能把 Debug 覆盖成功当作正式升级验证，也不能把旧 `com.tokenflow.chat` 的安装视为新包的上一版本。

## 手工验收矩阵

| 范围 | 最低检查 |
| --- | --- |
| Android 版本 | API 26 手机、API 36 手机 |
| 屏幕 | 手机竖/横屏、`>=600dp` 平板、`>=1000dp` 三栏 |
| 协议 | 三种协议各至少一次连续多轮、停止和重试 |
| 上下文 | 清空上下文分割线、重载、分支和历史附件 |
| 流式 UI | 长正文、过程、Token/cache、完成前后按钮不抖动 |
| Markdown | 长文、表格、代码、外部 URL、本地 citation、伪造 citation |
| 工作区 | 收藏级联、笔记重写、快照去重、知识检索过程和来源预览 |
| 附件 | 图片、相机 EXIF、PDF、Word、Excel、视觉兜底 |
| 生命周期 | 进程中断恢复、旋转、软键盘、切换会话继续生成 |
| 外观 | 中英文、六主题、字体/字间距/行间距、头像裁剪 |
| 语音 | 生成 -> 播放 -> 打开笔记/收藏 -> 返回 Chat，不自动重放 |

真实供应商限流、计费、协议兼容、在线 Exa/InfoFlow/MiMo 和正式签名覆盖升级仍需单独 E2E 验收，不能由 MockWebServer 或 JVM 测试证明。

## 测试失败记录

提交问题时至少记录：

- Git commit、构建变体、包名、versionCode/versionName
- `gradlew --version` 的 Gradle/JVM 信息
- 设备序列号、API level、product/model 和 boot ID 是否与受保护设备重复
- 失败任务与首个根因堆栈，不上传 API Key、完整聊天正文或签名材料
- 涉及迁移时记录升级前 schema/version，不要先清数据复测
