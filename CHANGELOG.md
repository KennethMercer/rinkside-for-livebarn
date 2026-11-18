# Changelog

## 1.0.1

- Fixed: right after signing in, nothing was highlighted on the Favorites screen (the hidden
  sign-in page kept the remote's focus). The first favorite is now highlighted straight away.
- The player hint now reads "OK play" while paused.

## 1.0 (first public release)

- Native Android TV screens: Favorites, Search across all LiveBarn rinks, venue pages with live status.
- Native video player (Media3) with an info overlay, pause/resume (jumps back to live after a long
  pause), ◀ ▶ to switch between a venue's rinks, and automatic retries on brief network drops.
- Handles one-screen plans: explains when another screen is watching and offers to stop it.
- Clear messages when a rink isn't live, a stream ends, or can't be played (with a website-player fallback).
- Website mode with remote highlight navigation and a pointer, for everything else.
- TV-friendly design: lift-style focus, large type, overscan-safe layout; screensaver allowed on menus.
- Cookie banner on the hidden website is declined automatically.

Pre-release builds (0.1.x–0.2.x) were private test versions under a different app ID; uninstall
them before installing 1.0.
