# Contributing

Fixes and setups for other TVs are welcome.

## Add your TV

1. Copy `device/tcl-g13/` to `device/<brand>-<model>/` and edit `removed.txt` and `disabled.txt`.
   One package per line.
2. Put the model, Android version and firmware build in a comment at the top of `setup.sh`.
   `adb shell getprop ro.product.model`, `ro.build.version.release` and `ro.build.fingerprint`
   print them.
3. Only list packages you have removed or disabled and lived with for a few days.
4. Check that `restore.sh` brings every one of them back.

## Change the launcher

The launcher in `launcher/` is plain Java with AndroidX RecyclerView. It is meant for TVs with
1 GB of RAM:

- No blur, no video, no network access, no background services.
- Anything that differs per TV goes in `app/src/main/res/values/config.xml`, not in code.
- Keep D-pad navigation predictable. Test Up, Down, Left and Right from every edge.
- Run `./gradlew testDebugUnitTest` after changing the art, the quotes or the text layout.
- Test on a real TV and include a screenshot (`adb exec-out screencap -p > shot.png`).

Things that don't fit: recommendation rows, content feeds, ads and telemetry.

By contributing you agree your work is released under the repo's MIT licence.
