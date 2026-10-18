#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")"
scratch="${TVREMOTE_BUILD:-.build}"
app="$HOME/Applications/TV Remote.app"

if pgrep -f "$app/Contents/MacOS/TVRemote" >/dev/null; then
  echo "TV Remote is running. Quit it from its menu, then run install.sh again." >&2
  exit 1
fi

swift build -c release --scratch-path "$scratch"
bin="$(swift build -c release --scratch-path "$scratch" --show-bin-path)"

rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp Info.plist "$app/Contents/Info.plist"
cp "$bin/TVRemote" "$app/Contents/MacOS/TVRemote"
cp -R "$bin/TVRemote_TVRemote.bundle" "$app/Contents/Resources/"

identity="$(security find-identity -v -p codesigning | awk -F'"' '/Apple Development/ { print $2; exit }')"
codesign --force --sign "${identity:--}" "$app"
codesign --verify --strict "$app"
echo "Signed with ${identity:-an ad hoc signature}"

open "$app"
