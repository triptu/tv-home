#!/bin/sh
# Installs the launcher on the TV and applies the home, screensaver and debloat settings.
# Usage: device/tcl-g13/setup.sh [serial]   (build first: cd launcher && ./gradlew assembleDebug)
set -e
export ANDROID_SERIAL=${1:-192.168.1.30:5555}
ADB=$HOME/Library/Android/sdk/platform-tools/adb
DIR=$(dirname "$0")
$ADB install -r "$DIR/../../launcher/app/build/outputs/apk/debug/app-debug.apk"
for p in $(cat "$DIR/removed.txt"); do $ADB shell pm uninstall -k --user 0 "$p" || true; done
for p in $(cat "$DIR/disabled.txt"); do $ADB shell pm disable-user --user 0 "$p"; done
$ADB shell pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION
$ADB shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity
$ADB shell settings put secure screensaver_enabled 1
$ADB shell settings put secure screensaver_components com.gathin.tvhome/.gallery.ArtDream
$ADB shell settings put global no_signal_auto_power_off 0
$ADB shell input keyevent KEYCODE_HOME
