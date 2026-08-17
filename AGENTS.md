# AGENTS.md

## Shell

- On native Windows, use PowerShell 7 at `C:\Program Files\PowerShell\7\pwsh.exe`.
- Run Gradle through the checked-in wrapper from the repository root.

## Project

- This repository contains only the native Android App. It does not depend on the TokenFlow Go server or PWA at runtime.
- Application ID and Kotlin namespace: `xyz.mek030399.tokenflow`.
- Debug application ID: `xyz.mek030399.tokenflow.debug`.
- The project is a single Gradle module, `:app`, using Kotlin, Jetpack Compose, Room, KSP, and OkHttp.
- Room is version 5 with explicit migrations `1 -> 2 -> 3 -> 4 -> 5`. Never add destructive fallback. Commit every new schema snapshot under `app/schemas/`.

## Build And Test

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

- Use `testDebugUnitTest --rerun-tasks` after package, serialization, database, or shared behavior changes.
- Instrumentation requires a disposable isolated AVD. Never run it on `127.0.0.1:5557` or another serial with the same Android boot ID.
- The protected device may only receive an explicit `adb install -r` deployment. Never uninstall, clear data, install the test APK, or run instrumentation there.

## Signing And Secrets

- Release signing is external and requires `TOKENFLOW_KEYSTORE_PATH`, `TOKENFLOW_KEYSTORE_PASSWORD`, `TOKENFLOW_KEY_ALIAS`, and `TOKENFLOW_KEY_PASSWORD`.
- Never commit keystores, passwords, API keys, `.tfcfg`, `local.properties`, or build outputs.
- A new application ID does not inherit the old `com.tokenflow.chat` sandbox or signing upgrade path.

## Editing

- Preserve unrelated local changes and generated Room migration assets.
- Prefer existing repository patterns and keep changes scoped to the requested behavior.
- Update `README.md` and the relevant file under `docs/` when build, signing, storage, dependencies, testing, or deployment behavior changes.
