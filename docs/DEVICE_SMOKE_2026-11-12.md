# Android 11 smoke evidence — 2026-11-12

Device: disposable AOSP x86_64 API 30 emulator, software CPU emulation, Android 11. Production app source: 312c1e6. Test harness waits for stopped status AND worker exit, with a 30-second bound.

Build: JDK 17 / Gradle 8.9 / SDK 35, `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` succeeded. 31 JVM tests passed; lint 0 errors / 19 English-string localization warnings. APK signature verified with apksigner; manifest minSdk 30 / targetSdk 35 / version 0.2.0.

Command: `ANDROID_HOME=... ./scripts/device-smoke.sh`, exit code 0. No service credentials, user accounts or personal data were configured. Only a synthetic Keystore marker was used, then removed.

```text
Performing Streamed Install
Success
Performing Streamed Install
Success
INSTRUMENTATION_RESULT: stream=PASS: Activity launches on API 30; FLAG_SECURE; encrypted Keystore round-trip; unconfigured foreground service stops safely

INSTRUMENTATION_CODE: -1
INSTRUMENTATION_RESULT: stream=PASS: encrypted state survives force-stop/process restart; test marker removed

INSTRUMENTATION_CODE: -1
```

Not verified here: actual Telegram Bot API behavior, existing FatSecret member OAuth/entitlements, live food search/create/read-back, model/transcription compatibility, FatSecret Accessibility IDs/actions, lock behavior or Realme battery/process restrictions. See INSTALL_RU.md for the phone checklist.
