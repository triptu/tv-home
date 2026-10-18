# TV Remote

TV Remote is a menu bar app for the living room TV. It sends volume, media, navigation and app launches over adb, wakes and sleeps the TV explicitly, starts the Mac's Sunshine desktop in Moonlight, and opens a copied link on the TV. A phone on the same Wi-Fi gets the same buttons from a page the Mac serves.

The app polls nothing. It checks the TV when you open the panel, press Reconnect, or run a command.

## Build and install

You need Xcode with Swift 6 and the Android SDK's adb at `~/Library/Android/sdk/platform-tools/adb`.

```bash
remote/install.sh
```

The script builds a release, assembles `~/Applications/TV Remote.app`, signs it with your Apple Development identity (ad hoc if you have none), and opens it. It refuses to replace a running copy, so quit the app first. macOS asks once for Local Network access. Allow it, or the app cannot reach the TV.

Run the tests with `swift test --package-path remote`.

## Mac on TV

Mac on TV opens a paired Moonlight host's app straight from the TV, so you need to pair once.

1. Open Sunshine on the Mac and grant screen recording.
2. In Moonlight on the TV, add the Mac and enter the PIN in Sunshine's web page.
3. Start the Desktop app once from Moonlight to confirm video arrives.
4. Copy the host UUID and the Desktop app id from Sunshine into TV Remote's Settings.

The button stays disabled until both values are set.

## Phone remote

Turn on Phone remote in the panel. Scan the QR code, or copy the link to the phone. The link carries a fresh token in its fragment. The page keeps it in session storage, so a reload on the phone still works. Turning the toggle off, or quitting, closes the listener and invalidates the link. It is plain HTTP on port 8765, meant for a home LAN.

## Command line

The same executable drives the TV without the panel, which helps when checking the TV from a terminal.

```bash
"$HOME/Applications/TV Remote.app/Contents/MacOS/TVRemote" --probe
"$HOME/Applications/TV Remote.app/Contents/MacOS/TVRemote" --run volume_up --run volume_down
"$HOME/Applications/TV Remote.app/Contents/MacOS/TVRemote" --phone
```

Command ids are in the registry in `Sources/TVRemote/Model.swift`.
