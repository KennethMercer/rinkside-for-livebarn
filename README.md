# RinkSide for LiveBarn

**An unofficial Android TV app for watching your LiveBarn subscription with the TV remote.**

LiveBarn has apps for phones, iPhone/iPad and Apple TV, but not for Android TV / Google TV.
RinkSide fills that gap: browse your favorite rinks, see which ones are live, and watch full-screen
in Android's own video player, all with the D-pad.

> RinkSide is an independent, unofficial project. It is **not affiliated with, endorsed by or
> supported by LiveBarn Inc.** "LiveBarn" is a trademark of its owner. You need your own LiveBarn
> account and subscription; RinkSide doesn't provide or unlock any content.

## Features

- **Favorites**: your LiveBarn favorites, plus favorites you save on the TV.
- **Search**: find any LiveBarn rink by name or city.
- **Live status**: see which rinks are live right now (red **LIVE** pill).
- **Native player**: hardware-decoded playback with a clean overlay showing the rink, venue and LIVE/PAUSED.
  - **OK** pauses and resumes. Resuming after a long pause jumps back to live.
  - **◀ ▶** switches between a venue's rinks without leaving the player.
  - **Back** returns to the rink list.
- **One-screen plans handled gracefully**: if your plan only allows one stream and another screen is
  watching, RinkSide tells you where, and you choose whether to stop it.
- **Website mode**: the full LiveBarn website with remote navigation (highlight + pointer), for
  anything the app doesn't do natively, such as replays or account settings.

## Install

1. Download `RinkSide-x.y.apk` from the [Releases](../../releases) page.
2. Get it onto your TV. Either:
   - use an app such as *Send Files to TV* or *Downloader* and open the APK on the TV (allow
     "install unknown apps" for that app when asked), or
   - with developer options enabled on the TV: `adb connect <tv-ip>:5555` then `adb install RinkSide-x.y.apk`.
3. Open **RinkSide** from the TV's apps row and sign in with your LiveBarn account (once).

Requires Android TV / Google TV 6.0 or newer.

## Remote controls

| Where | Button | Does |
|---|---|---|
| Screens | Arrows / OK / Back | Move, select, go back (Back twice on Favorites exits) |
| Player | OK or Play/Pause | Pause / resume |
| Player | ◀ ▶ | Previous / next rink at this venue |
| Player | ▲ ▼ | Show rink info |
| Player | Back | Back to the rink list |
| Player | Menu (or red button) | Continue in the website's own player |
| Website mode | Arrows / OK | Move the yellow highlight / click |
| Website mode | Hold OK (or Menu) | Pointer mode for hard-to-reach spots; Back turns it off |
| Website mode | Back | Previous page, then back to the app |

## How it works

RinkSide runs the real LiveBarn website (`watch.livebarn.com`) in a hidden WebView. You sign in
there, and it keeps your session and LiveBarn's protections working exactly as in a browser. The
native screens are built from data that page already has: its own venue list cache and the
favorites and live-status responses it receives.

When you pick a rink, the hidden page opens that rink's live page as normal. The stream playlist it
loads is handed to Android's player (Media3/ExoPlayer). The page stays open, paused, so its own
stream keep-alive keeps working; leaving a rink uses the site's own navigation, so the stream
session is ended properly.

RinkSide never calls LiveBarn's API itself. The only direct requests it makes are for the venue
logo images (from LiveBarn's public image storage) and the video stream the page already requested.

Code tour:
- `app/src/main/java/io/github/kennethmercer/livebarntv/`
  - `MainActivity.java`: modes (sign-in / screens / website / player), remote keys, watch flow
  - `TvUi.java`: native screens and design system
  - `NativePlayer.java`: player and overlay
  - `Catalog.java`: venues, rinks, favorites, live status
  - `ImageLoader.java`: venue logos
- `app/src/main/assets/tv.js`: runs inside the LiveBarn page; reports sign-in state, venues,
  favorites, live status and stream playlists to the app, and provides website-mode navigation.

## Building

Requirements: JDK 17 and the Android SDK (platform 35). The included Gradle wrapper downloads the
right Gradle version.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # signed only if you provide a key (see below), else debug-signed
```

Release signing reads a properties file (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`)
from the path in the `LBTV_KEYSTORE_PROPERTIES` environment variable. Keys are never stored in the
repository.

## Contributing

Issues and pull requests are welcome. LiveBarn's website can change at any time; if something
breaks, please open an issue with what you saw. RinkSide must keep using the real website for
anything involving your account: please don't submit changes that call LiveBarn's API directly or
work around its protections.

## License

[MIT](LICENSE) © 2026 KennethMercer
