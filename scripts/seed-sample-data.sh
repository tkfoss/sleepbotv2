#!/usr/bin/env bash
# Insert N nights (default 30) of random sample sleep entries through SleepBot's intent API.
set -euo pipefail
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
N="${1:-30}"; now=$(date +%s); today=$(( now - now % 86400 ))
for d in $(seq "$N" -1 1); do
  s=$(( ((today - d*86400) + (RANDOM % 180 + 60) * 60 - 3*3600) * 1000 ))
  w=$(( s + (RANDOM % 240 + 300) * 60000 ))
  "$ADB" shell "am start -W -a android.intent.action.RUN -n com.sleepbot.app/.api.IntentApiActivity --ez Sleep true --es dummy dummybugger" >/dev/null; sleep 1.2
  "$ADB" shell "am start -W -a android.intent.action.RUN -n com.sleepbot.app/.api.IntentApiActivity --ez 'Wake up' true --el SleepTime $s --el WakeUpTime $w --ez DialogFree true --es dummy dummybugger" >/dev/null; sleep 1.2
done
echo "Inserted $N entries."
