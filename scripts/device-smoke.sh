#!/usr/bin/env bash
set -euo pipefail
# Run only on a clean disposable API 30 emulator, never a configured personal phone.
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
adb="$ANDROID_HOME/platform-tools/adb"
"$adb" wait-for-device
"$adb" install -r -t app/build/outputs/apk/debug/app-debug.apk
"$adb" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
runner=com.oleg.androidagent.test/com.oleg.androidagent.DeviceSmoke
result=$("$adb" shell am instrument -w -e phase write "$runner")
printf '%s\n' "$result"
[[ "$result" == *'INSTRUMENTATION_CODE: -1'* && "$result" == *'PASS:'* ]]
"$adb" shell am force-stop com.oleg.androidagent
result=$("$adb" shell am instrument -w -e phase read "$runner")
printf '%s\n' "$result"
[[ "$result" == *'INSTRUMENTATION_CODE: -1'* && "$result" == *'PASS:'* ]]
