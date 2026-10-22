#!/bin/bash
# Installs the debug build and opens XRdroid's phone screen, which starts the screens on the glasses by
# itself (and again whenever they reconnect), then shows the first log lines.
# Usage: scripts/deploy.sh [--no-install]. Set ANDROID_SERIAL when more than one device is connected.
set -euo pipefail
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/build/logcat.txt"
mkdir -p "$ROOT/build"

if [ "${1:-}" != "--no-install" ]; then
  "$ADB" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" | tail -1
fi

pkill -f "logcat -v time XRdroid" 2>/dev/null || true
"$ADB" logcat -c
("$ADB" logcat -v time XRdroid:V AndroidRuntime:E '*:S' > "$LOG" 2>&1 &)

"$ADB" shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.xrdroid/.MainActivity >/dev/null
sleep 6
grep -v "render " "$LOG" | grep XRdroid | tail -8 || true
grep "render " "$LOG" | tail -1 || true
echo "Full log: $LOG"
