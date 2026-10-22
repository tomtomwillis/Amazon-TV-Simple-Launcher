#!/bin/sh
# Installs (or updates) Home on the Fire TV and sets up the Home button.
# Safe to run again any time, e.g. after a Fire OS update.
#
# Usage: ./install-on-tv.sh <TV IP address>      e.g. ./install-on-tv.sh 192.168.1.57
set -e

IP="${1:?Usage: ./install-on-tv.sh <TV IP address>}"
# adb from $ADB, else the PATH, else Android Studio's default SDK location (macOS, then Linux)
if [ -z "$ADB" ]; then
    for c in "$(command -v adb)" "$HOME/Library/Android/sdk/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb"; do
        if [ -n "$c" ] && [ -x "$c" ]; then ADB="$c"; break; fi
    done
fi
[ -n "$ADB" ] || { echo "Can't find adb: install Android Studio, or set ADB=/path/to/adb"; exit 1; }
DEV="$IP:5555"
PKG=xyz.bodyparts.home
SVC=$PKG/$PKG.HomeCatchService
APK="$(cd "$(dirname "$0")" && pwd)/app/build/outputs/apk/release/app-release.apk"

a() { "$ADB" -s "$DEV" "$@"; }

[ -f "$APK" ] || { echo "No APK yet: build it first with ./gradlew assembleRelease"; exit 1; }

"$ADB" connect "$DEV" >/dev/null || true
if [ "$("$ADB" -s "$DEV" get-state 2>/dev/null)" != device ]; then
    echo "Can't talk to the TV yet. If it shows \"Allow USB debugging?\", tick"
    echo "\"Always allow from this computer\", press OK, then run this again."
    exit 1
fi

echo "Installing the app..."
a install -r "$APK"

echo "Letting it see Home button presses..."
a shell pm grant $PKG android.permission.READ_LOGS

echo "Removing Android's 5-second delay on opening it..."
CONSTS=$(a shell settings get global alarm_manager_constants | tr -d '\r')
case "$CONSTS" in
    ""|null) CONSTS="min_futurity=0" ;;
    *min_futurity=*) CONSTS=$(printf '%s' "$CONSTS" | sed 's/min_futurity=[0-9]*/min_futurity=0/') ;;
    *) CONSTS="$CONSTS,min_futurity=0" ;;
esac
a shell settings put global alarm_manager_constants "$CONSTS"

echo "Switching on the Home button helper..."

# Added alongside any other accessibility services (e.g. VoiceView)
enable_helper() {
    CUR=$(a shell settings get secure enabled_accessibility_services | tr -d '\r')
    case "$CUR" in
        *"$SVC"*) ;;
        ""|null) a shell settings put secure enabled_accessibility_services "$SVC" ;;
        *) a shell settings put secure enabled_accessibility_services "$CUR:$SVC" ;;
    esac
    a shell settings put secure accessibility_enabled 1
}

disable_helper() {
    CUR=$(a shell settings get secure enabled_accessibility_services | tr -d '\r')
    OTHERS=$(printf '%s' "$CUR" | tr ':' '\n' | grep -v -x -e "$SVC" -e null -e '' | paste -s -d: - || true)
    a shell settings put secure enabled_accessibility_services "${OTHERS:-null}"
}

# The helper watches for Home presses with a logcat process running as the app
helper_running() {
    PROCS=$(a shell ps -A | tr -d '\r')
    APPUSER=$(printf '%s\n' "$PROCS" | awk -v p=$PKG '$NF == p { print $1; exit }')
    [ -n "$APPUSER" ] && printf '%s\n' "$PROCS" | awk -v u="$APPUSER" '$1 == u && $NF == "logcat"' | grep -q .
}

wait_for_helper() {
    for _ in 1 2 3 4 5; do
        sleep 3
        helper_running && return 0
    done
    return 1
}

enable_helper
# After an update Fire OS restarts the helper by itself, but not always promptly;
# switching it off and on again usually brings it straight back.
if ! wait_for_helper; then
    disable_helper
    sleep 3
    enable_helper
    wait_for_helper || true
fi

a shell am start -n $PKG/.MainActivity >/dev/null
if helper_running; then
    echo "Done. Press Home on the remote to try it."
else
    echo "Installed, but the Home button helper hasn't started. Restart the TV to finish:"
    echo "  $ADB -s $DEV reboot"
fi
