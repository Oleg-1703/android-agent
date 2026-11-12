#!/usr/bin/env bash
set -euo pipefail
# Run only on a clean disposable API 30 emulator, never a configured personal phone.
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
adb="$ANDROID_HOME/platform-tools/adb"
"$adb" wait-for-device
[[ $("$adb" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo "Refusing a non-emulator device" >&2; exit 1; }
[[ $("$adb" shell getprop ro.build.version.sdk | tr -d '\r') == 30 ]] || { echo "Use a clean API 30 emulator" >&2; exit 1; }
for ((attempt=0; attempt<90; attempt++)); do
    [[ $("$adb" shell getprop sys.boot_completed | tr -d '\r') == 1 ]] && break
    sleep 2
done
[[ $("$adb" shell getprop sys.boot_completed | tr -d '\r') == 1 ]]
"$adb" install -r -t app/build/outputs/apk/debug/app-debug.apk
"$adb" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
runner=com.oleg.androidagent.test/com.oleg.androidagent.DeviceSmoke
result=$("$adb" shell am instrument -r -w -e phase write "$runner")
printf '%s\n' "$result"
[[ "$result" == *'INSTRUMENTATION_CODE: -1'* && "$result" == *'PASS:'* ]]
"$adb" shell am force-stop com.oleg.androidagent
result=$("$adb" shell am instrument -r -w -e phase read "$runner")
printf '%s\n' "$result"
[[ "$result" == *'INSTRUMENTATION_CODE: -1'* && "$result" == *'PASS:'* ]]
