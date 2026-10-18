# tv-home

A minimal home screen for Android TV and Google TV that shows a painting until you want to watch something.

![The home screen: Hokusai's Red Fuji with the clock, a quote by Dieter Rams and a dock of three apps](docs/home-red-fuji.jpg)

It installs on any Android TV or Google TV with Android 8 or later.

- [Why](#why)
- [What you get](#what-you-get)
- [Principles](#principles)
- [Set it up on your TV](#set-it-up-on-your-tv)
- [Credits](#credits)

## Why

<img src="docs/stock-google-tv.jpg" width="200" align="right" alt="The stock Google TV home: a Prime Video ad, Top picks for you and an app row">

The stock Google TV home screen is mostly ads and rows of recommendations. On TVs with little memory it is also
slow.

This home screen shows one painting at a time, a clock and a quote, plus the few apps you
actually open. It uses less memory than the stock one, and you decide everything on it.

The picture on the right is the stock home on the same TV, dimmed behind a system dialog.

<br clear="right">

## What you get

### Home

A public-domain painting fills the screen and changes every 45 seconds, with a slow pan and zoom.
A small clock and the date sit top left, with a quote under them. Two round buttons top right open
Art mode and Settings. A small dock floats at the bottom with your apps and an All apps tile.
Cards are muted until you focus one, then it lifts.

The quote's ink changes with each painting: dark ink on light paper, white ink on dark or busy
paintings, with just enough shade behind it to stay readable.

<img src="docs/home-crepe-ginger.jpg" width="49%" alt="Home on a light botanical painting by Shaikh Zain al-Din, with dark ink"> <img src="docs/home-boating-party.jpg" width="49%" alt="Home on Renoir's Luncheon of the Boating Party, with white ink">
<img src="docs/home-ninth-wave.jpg" width="49%" alt="Home on Aivazovsky's The Ninth Wave">

### Idle

After 8 seconds without a key press, the dock and buttons fade out. The painting, a dim clock and
the quote stay. The first key press only wakes the screen, so you never open an app by accident.

![Idle home on Bierstadt's Among the Sierra Nevada: dim clock and the quote, no dock](docs/idle-sierra-nevada.jpg)

### Art mode

The palette button opens a gallery: one painting at a time in a frame with an ivory mat, on a
walnut wall, with a museum-style plaque. Left and right on the remote move between paintings. It
opens on the painting that was on the home screen. It also shows up as Gallery in the TV's own app list.

<img src="docs/art-mode-monet.jpg" width="49%" alt="Art mode: Monet's The Beach at Sainte-Adresse, framed, with a plaque"> <img src="docs/art-mode-krishna.jpg" width="49%" alt="Art mode: Krishna and the Gopis, a Jaipur miniature, framed, with a plaque">

### Screensaver

The default screensaver is the home screen with nothing else on it: the painting and the quote,
with the painting's title, artist and year bottom left. The framed Gallery is also available as a
screensaver, and so is a plain drifting clock.

<img src="docs/screensaver-oriole.jpg" width="49%" alt="Screensaver: Black-headed Oriole by Shaikh Zain al-Din, with a quote and caption"> <img src="docs/screensaver-temeraire.jpg" width="49%" alt="Screensaver: Turner's The Fighting Temeraire, with a quote and caption">

### All apps

The last tile in the dock opens every installed app in a grid over the dimmed painting.

![All apps grid over a dimmed painting](docs/all-apps.jpg)

This screenshot is from a slightly older build, which still showed the quote in a box.

### The art

54 pictures: Monet, Van Gogh, Hokusai, Klimt, Turner, Indian miniatures, Himalayan mandalas, and
NASA, Hubble and Webb images. The 31 landscape ones rotate on the home screen. Art mode and the
Gallery screensaver show all of them.

![Contact sheet of all 54 pictures](docs/art-collection.jpg)

### Debloat

The TV's ads, recommendation rows, demos and telemetry are switched off, and the apps you don't use
are removed. Nothing is rooted or flashed, and one script puts it all back. The home screen uses less
than half the memory of Google TV's own launcher.

### Mac and phone remote

[`remote/`](remote/) is a small Mac menu bar app that drives the TV over adb: volume, media keys,
navigation, app launches, sleep and wake, and the Mac's desktop in Moonlight. A phone on the same
Wi-Fi gets the same buttons from a page the Mac serves. See [`remote/README.md`](remote/README.md).

## Principles

- **The art comes first.** The screen is a painting with a few small things on it, never the other way round.
- **Only what you use.** Three apps in the dock, everything else one press away in All apps.
- **Nothing that tracks or recommends.** No network access, no accounts, no content rows. The launcher only lists installed apps and opens them.
- **Readable on every painting.** The text colour and shade are measured per painting (`InkRule.java`) so text reaches a WCAG contrast of 4.5:1. A unit test checks this on every home picture.
- **Small and fast on cheap hardware.** Plain Java with RecyclerView, no blur, no video, no background services.
- **Everything reversible.** Nothing is flashed or rooted. Each change in `device/` has a restore.

## Set it up on your TV

Let a coding agent do it. That is how this one was made: an agent connected to the TV over Wi-Fi,
looked at it through screenshots, asked a few questions and did the rest.

1. On the TV, turn on Developer options (press OK on the build number in Settings → About seven
   times), then turn on USB debugging and Wireless debugging.
2. On a computer on the same Wi-Fi, open Claude Code, Codex or another coding agent and give it this:

   > Set up github.com/triptu/tv-home on my TV. Follow PROMPT.md in that repo.

3. Answer its questions: which apps you use, which go in the dock, what to keep. Accept the
   "Allow debugging?" prompt on the TV when it appears.

After that, ask it for whatever you want: different apps in the dock, your own paintings or photos,
more quotes, a different screensaver, putting everything back the way it was. The details it needs
are in [`PROMPT.md`](PROMPT.md).

It works on Android TV and Google TV, not on Samsung, LG or Fire TV.

## Credits

This started as a fork of [tv-vibehack](https://github.com/Gathin23/tv-vibehack) by Gathin23 (MIT),
which debloats Android TVs and replaces the home screen with an Apple TV style launcher. The
launcher's base comes from it: the plain Java app, its package name `com.gathin.tvhome`, the All apps
grid, D-pad navigation, the Clock screensaver and the Outfit font. The debloat lists in
`examples/` and the idea of an AI assistant doing the debloat ([`PROMPT.md`](PROMPT.md)) also come
from there.

Since then it has been reworked around the art: the rotating paintings and quotes, the adaptive ink and its
test, Art mode, the art screensavers, the floating dock, the idle fade, the debloat setup in
`device/`, and the move to target SDK 34.

- **Art:** public domain or CC0, from The Metropolitan Museum of Art, the National Gallery of Art,
  the Art Institute of Chicago, Wikimedia Commons (PD-Art) and other museums, and NASA. Each
  picture's source is in `catalog.json`.
- **Fonts:** [Outfit](https://fonts.google.com/specimen/Outfit) and
  [Cormorant Garamond](https://fonts.google.com/specimen/Cormorant+Garamond), both under the SIL
  Open Font License.
- **Quotes:** checked against [Wikiquote](https://en.wikiquote.org/).
