#!/usr/bin/env bash
# Boot the "sb" test emulator (720x1280 xhdpi, same as the original screenshots),
# install the latest SleepBot build and launch it.
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/dist/SleepBot-4.0.0-debug.apk"
[ "${1:-}" = "--build" ] && { (cd "$ROOT" && ./gradlew :app:assembleDebug -q); APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"; }

if ! "$ADB" devices | grep -q emulator; then
  "$SDK/emulator/emulator" -avd sb -no-boot-anim >/dev/null 2>&1 &
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
fi
"$ADB" install -r -g "$APK"
"$ADB" shell am start -n com.sleepbot.app/.MainActivity
