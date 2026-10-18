#!/bin/sh
# Installs the launcher on the TV and applies the home, screensaver and debloat settings.
# Usage: device/tcl-g13/setup.sh [serial]   (build first: cd launcher && ./gradlew assembleDebug)
set -e
export ANDROID_SERIAL=${1:-192.168.1.30:5555}
ADB=${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}
DIR=$(dirname "$0")
$ADB install -r "$DIR/../../launcher/app/build/outputs/apk/debug/app-debug.apk"
for p in $(cat "$DIR/removed.txt"); do $ADB shell pm uninstall -k --user 0 "$p" || true; done
for p in $(cat "$DIR/disabled.txt"); do $ADB shell pm disable-user --user 0 "$p"; done
$ADB shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity
$ADB shell settings put secure screensaver_enabled 1
$ADB shell settings put secure screensaver_components com.gathin.tvhome/.ArtDream
$ADB shell settings put global no_signal_auto_power_off 0
# Screensaver after 5 minutes idle; the TV turns off after 4 hours with no input.
$ADB shell settings put system screen_off_timeout 300000
$ADB shell settings put secure sleep_timeout 14400000
$ADB shell settings put secure attentive_timeout 14400000
$ADB shell settings put global window_animation_scale 0
$ADB shell settings put global transition_animation_scale 0
$ADB shell settings put global animator_duration_scale 0
$ADB shell input keyevent KEYCODE_HOME
