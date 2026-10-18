#!/bin/sh
# Restores every package removed by the debloat. Usage: ./restore.sh [serial]
export ANDROID_SERIAL=${1:-192.168.1.30:5555}
ADB=$HOME/Library/Android/sdk/platform-tools/adb
for p in $(cat "$(dirname "$0")/removed.txt"); do $ADB shell cmd package install-existing $p; done
for p in $(cat "$(dirname "$0")/disabled.txt" 2>/dev/null); do $ADB shell pm enable $p; done
