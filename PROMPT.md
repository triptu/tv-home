# Setting up tv-home with a coding agent

This file is for the agent. Give your agent (Claude Code, Codex or similar, running on a computer
on the same Wi-Fi as the TV) this line, then answer its questions:

> Set up github.com/triptu/tv-home on my TV. Follow PROMPT.md in that repo.

---

## For the agent

Set up the tv-home launcher on the user's Android TV or Google TV and remove the bloat. Work on the
real TV step by step. After each step take a screenshot (`adb exec-out screencap -p > shot.png`)
and look at it. Ask the user only about what depends on them: which apps they use and what goes in
the dock. Decide everything else yourself. Everything you change must be reversible.

### Connect
- Clone the repo. Make sure `adb`, JDK 17 and the Android SDK with platform 34 are installed.
- Find the TV on the network (`adb mdns services`, or scan the subnet for port 5555). If the TV
  shows a pairing code under Wireless debugging, use `adb pair`, then `adb connect`. Tell the user
  when to accept the "Allow debugging?" prompt on the TV.
- Turn off sleep while you work (`settings put system screen_off_timeout`, `svc power stayon true`)
  and restore it at the end.

### Back up
- Save `pm list packages`, `pm list packages -d`, `getprop` and `settings list system|secure|global`
  to a local `backup/` folder (it is in `.gitignore`) before changing anything.

### Debloat
- Copy `device/tcl-g13/` to `device/<brand>-<model>/` and build its `removed.txt` (uninstalled for
  user 0 with `pm uninstall -k --user 0`) and `disabled.txt` (`pm disable-user --user 0`).
- Sort installed packages into manufacturer telemetry and ads, content and recommendation services,
  preloaded apps and streaming apps. `examples/` has lists other people made for Xiaomi, TCL, Sanyo
  and OnePlus TVs. Check unclear packages with `dumpsys package <name>`.
- Never touch the Play Store, Play Services, the keyboard, remote and Bluetooth services, or the
  TV's input, HDMI, picture and tuner services.
- Ask the user with one checklist which streaming apps, voice assistant, casting and firmware update
  service they use. Remove only what they don't tick.
- On Google TV, `com.google.android.apps.tv.launcherx` and `com.google.android.tungsten.setupwraith`
  must be in `disabled.txt`, or the Home button goes back to Google TV.
- `restore.sh` must undo every line of both lists (`cmd package install-existing`, `pm enable`).

### Launcher
- Ask which three apps go in the dock and in what order. Put their package names in
  `launcher/app/src/main/res/values/config.xml` (`dock_apps`). Add apps the user never wants to see
  to `hidden_apps`.
- Build with `cd launcher && ./gradlew assembleDebug testDebugUnitTest`.
- Keep targetSdk at 34 or higher. Google TV's Play Protect blocks sideloaded apps built for older
  Android versions, and the install hangs on a dialog on the TV.
- Targeting SDK 30+ needs the `<queries>` block in the manifest, or the launcher can't see the
  installed apps.

### Install
- Run `device/<brand>-<model>/setup.sh <ip:port>`, press Home and take a screenshot.
- Check Home, Back from an app, All apps, Art mode and the screensaver (set a short
  `screen_off_timeout` to trigger it, then restore it).
- The TV may drop off Wi-Fi for a few minutes. Wait for port 5555 to come back rather than assuming
  it crashed.

### Things the user may ask for later
- **Screensaver:** `settings put secure screensaver_components` with
  `com.gathin.tvhome/.ArtDream` (home painting and quote, the default),
  `com.gathin.tvhome/.gallery.ArtDream` (framed gallery) or `com.gathin.tvhome/.ClockDream`
  (drifting clock).
- **Their own art:** add a JPEG (about 1920 px on the long side) to
  `launcher/app/src/main/assets/art/` and an entry to `catalog.json` (`file`, `title`, `artist`,
  `year`, `kind`, `width`, `height`, `credit`). Pictures at least 1.3 times wider than tall also
  show on the home screen. Run `./gradlew testDebugUnitTest`: it fails if any text on a picture
  falls below 4.5:1 contrast, and `-PinkSheet=/tmp/ink.png` writes a contact sheet to check.
- **Quotes:** `launcher/app/src/main/assets/quotes.json`, one `{"text", "author"}` each. Keep them
  short and check the attribution.
- **Undo everything:** `device/<brand>-<model>/restore.sh <ip:port>`.

When done, tell the user what you removed and disabled, and how to undo it.
