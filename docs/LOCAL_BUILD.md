# Android 本地编译环境

本文以 Windows 和 PowerShell 7 为主，所有命令均从 `C:\Users\Mek\Works\TokenFlowApp` 执行。项目使用 Gradle Wrapper，不需要单独安装全局 Gradle。

## 项目要求

| 组件 | 项目基线 | 说明 |
| --- | --- | --- |
| PowerShell | 7.x | 本仓库在 Windows 上统一使用 `C:\Program Files\PowerShell\7\pwsh.exe` |
| JDK | 至少 17 | Java 源码和字节码目标为 17；当前机器使用 JDK 21 |
| Android SDK Platform | 36 | 对应 `compileSdk` 和 `targetSdk` |
| Android Build Tools | 36.0.0 | 在 App 构建脚本中固定 |
| Android Platform Tools | 可用的近期版本 | 提供 `adb`；仅构建 APK 时不是必需 |
| 内存 | Gradle 最多 3 GiB | 由 `org.gradle.jvmargs=-Xmx3g` 配置 |

Gradle Wrapper 固定为 `9.4.1`，并校验下载包 SHA-256：

```text
2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
```

## 当前机器实测快照

以下信息于 2026-08-17 在本机核验，用于复现当前可工作的环境，不是所有开发机都必须使用相同绝对路径。

| 项目 | 实测值 |
| --- | --- |
| 操作系统 | Windows 11 企业版 LTSC，`10.0.26100`，amd64 |
| PowerShell | `7.6.4` |
| `JAVA_HOME` | `C:\Users\Mek\sdk\jdk-21.0.2` |
| Gradle Launcher/Daemon JVM | OpenJDK `21.0.2` |
| Android SDK | `C:\Users\Mek\AppData\Local\Android\Sdk` |
| 已装 Platforms | 34、35、36 |
| 已装 Build Tools | 33.0.1、34.0.0、35.0.0、36.0.0 |
| Command-line Tools | 12.0 |
| Platform Tools / ADB | 37.0.0 / ADB 1.0.41 |
| ADB 可执行文件 | `C:\Users\Mek\AppData\Local\Android\Sdk\platform-tools\adb.exe` |

本机未设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`；项目通过未提交的 `local.properties` 定位 SDK。`adb` 当前也不在 `PATH` 中。

## 首次配置

### 1. 使用 PowerShell 7

```powershell
& 'C:\Program Files\PowerShell\7\pwsh.exe'
```

### 2. 选择 JDK

当前机器可直接设置：

```powershell
$env:JAVA_HOME = 'C:\Users\Mek\sdk\jdk-21.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
javac -version
```

如使用 Android Studio，应让 Gradle JDK 与命令行 JDK 保持一致，避免同一工作树产生两个不同的 Gradle daemon 环境。

### 3. 配置 Android SDK

在项目根目录的 `local.properties` 中写入本机 SDK 路径：

```properties
sdk.dir=C\:\\Users\\your-name\\AppData\\Local\\Android\\Sdk
```

`local.properties` 已被 Git 忽略，只存放机器相关配置。签名参数不会从此文件读取。

确认 SDK 36 和 Build Tools 36.0.0 已安装；缺失时通过 Android Studio SDK Manager 安装，或使用 SDK Command-line Tools：

```powershell
& 'C:\path\to\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat' `
  'platforms;android-36' `
  'build-tools;36.0.0' `
  'platform-tools'
```

### 4. 验证工具链

```powershell
cd C:\Users\Mek\Works\TokenFlowApp
.\gradlew.bat --version
.\gradlew.bat tasks --group build
```

`--version` 应显示 Gradle 9.4.1，并显示所选 JDK。首次运行会联网下载 Wrapper、插件和 Maven 依赖。

## 常用构建命令

仅运行 JVM 单元测试：

```powershell
.\gradlew.bat testDebugUnitTest
```

运行静态检查：

```powershell
.\gradlew.bat lintDebug
```

构建 Debug App 和 instrumentation 测试 APK：

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
```

本地提交前的 Android 门禁：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest verifyThirdPartyNotices
```

不要用裸 `assemble` 或 `build` 代替上述 Debug 任务。项目会把这两个任务视为包含 Release，并在签名参数缺失时立即失败。

## 构建产物

| 产物 | 路径 | 应用 ID |
| --- | --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` | `xyz.mek030399.tokenflow.debug` |
| AndroidTest APK | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `xyz.mek030399.tokenflow.debug.test` |
| 签名 Release APK | `app/build/outputs/apk/release/app-release.apk` | `xyz.mek030399.tokenflow` |
| F-Droid 未签名 Release APK | `app/build/outputs/apk/release/app-release-unsigned.apk` | `xyz.mek030399.tokenflow` |
| Release AAB | `app/build/outputs/bundle/release/app-release.aab` | `xyz.mek030399.tokenflow` |

普通签名 Release 产物需要外部签名参数，详见 [签名与发布](SIGNING_AND_RELEASE.md)。

F-Droid 从源码构建时使用显式属性生成未签名 Release：

```powershell
.\gradlew.bat -PfdroidBuild=true assembleRelease --no-configuration-cache
```

运行前必须确保四个 `TOKENFLOW_*` 签名值全部未设置。该模式不读取或生成项目私钥，产物由 F-Droid 使用自己的证书签名，因此不能直接覆盖从 GitHub Release 安装的版本。普通 Release 构建仍必须提供全部四个签名值。

## 常见问题

### 找不到 Android SDK

检查 `local.properties` 是否位于项目根目录，并确认路径转义正确。不要提交该文件。

### Gradle 使用了错误的 JDK

运行：

```powershell
.\gradlew.bat --version
```

检查 `Launcher JVM` 和 `Daemon JVM`。调整 `JAVA_HOME` 或 Android Studio 的 Gradle JDK 后重新执行命令；无需删除项目文件。

### 找不到 adb

本机 `adb` 不在 `PATH`，使用完整路径：

```powershell
& 'C:\Users\Mek\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
```

### Release 构建提示缺少 TOKENFLOW_KEYSTORE

这是预期保护。Debug 构建请使用 `assembleDebug`；正式发布请按 [签名与发布](SIGNING_AND_RELEASE.md) 配置四个签名值。

### F-Droid 构建提示检测到签名值

这是预期保护。清理当前进程中的四个 `TOKENFLOW_*` 签名环境变量或 Gradle properties 后再运行 `-PfdroidBuild=true assembleRelease`。不要把正式签名材料传给 F-Droid 构建。

### 依赖或 Wrapper 下载失败

确认可访问 `services.gradle.org`、Google Maven 和 Maven Central。仓库配置禁止在模块内临时增加其他仓库；确需变更时应审查后修改 `settings.gradle.kts`。
