# A prompt for setting it up with an AI assistant

Paste this into Claude Code, or another coding assistant that can run shell commands, from a clone
of this repo on a computer on the same Wi-Fi as the TV. It works on Android TV and Google TV. It
does not work on Samsung (Tizen), LG (webOS) or Fire TV.

Before you start, turn on Developer options on the TV (Settings → System → About → press OK on
"Android TV OS build" seven times), then turn on USB debugging, and Wireless debugging if the TV
has it. Keep the TV on.

---

I want to put the tv-home launcher from this repo on my TV and remove the bloat. My TV model is:
<MODEL NAME HERE>. Read README.md first. Work on the real TV step by step, check each step with a
screenshot from the TV (`adb exec-out screencap -p > shot.png`) and look at it. Ask me before
anything that depends on what I use.

Connect
- Connect with `adb`. If the TV shows a pairing code under Wireless debugging, use `adb pair` and
  then `adb connect`. Otherwise try port 5555. Tell me when to accept the "Allow debugging?"
  prompt on the TV.

Back up first
- Save `pm list packages`, `pm list packages -d`, `getprop` and `settings list system|secure|global`
  to a `backup/` folder before changing anything.

Device folder
- Copy `device/tcl-g13/` to `device/<brand>-<model>/`. Build its `removed.txt` (apps to uninstall
  for user 0) and `disabled.txt` (packages to disable) for my TV.
- Sort the installed packages into: manufacturer telemetry and ads, content and recommendation
  services, preloaded apps, and streaming apps. Check `examples/` for lists other people made.
- Never touch the Play Store, Play Services, the keyboard, remote and Bluetooth services, or the
  TV's input, HDMI and tuner apps. If a package name doesn't say what it does, check
  `dumpsys package <name>` and ask me if it is still unclear.
- Ask me with a checklist which streaming apps, voice assistant, casting and firmware update
  service I use. Remove only what I don't tick.
- On Google TV, `com.google.android.apps.tv.launcherx` and `com.google.android.tungsten.setupwraith`
  must be in `disabled.txt`, or Home goes back to Google TV.
- Make sure `restore.sh` undoes every line of both lists.

Launcher
- Ask me which three apps go in the dock and in what order, and put their package names in
  `launcher/app/src/main/res/values/config.xml` (`dock_apps`). Add the TV's own Live TV app to
  `hidden_apps` if I don't want it in All apps.
- Build with `cd launcher && ./gradlew assembleDebug`, and run `./gradlew testDebugUnitTest`.
- Keep targetSdk at 34 or higher. Google TV's Play Protect blocks apps built for older versions.

Install
- Run `device/<brand>-<model>/setup.sh <ip:port>`. Press Home and take a screenshot.
- Check that Home, the Back button from an app, All apps, Art mode and the screensaver work.

When done, tell me what you removed and disabled, and how to undo it with `restore.sh`.

---

Expect three or four checklist questions. Everything can be undone with the restore script.
