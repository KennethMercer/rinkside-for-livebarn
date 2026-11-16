# RinkSide for LiveBarn (Android TV)

Unofficial Android TV client for LiveBarn. App ID / package `io.github.kennethmercer.livebarntv`
(permanent: never change it). See `README.md` (features, how it works) and `CHANGELOG.md`.

## Rules
- The site is protected by AWS WAF bot checks and every API call carries a per-request DPoP proof:
  keep using the real website in the hidden WebView. Never call the LiveBarn API directly, replay or
  forge requests, or work around those checks.
- Accounts may allow only one stream at a time: don't start live playback for testing without the
  account owner's OK. Always end streams with Back (ends the session); avoid force-stopping mid-stream.
- Keep it clearly unofficial ("RinkSide for LiveBarn", not affiliated); never ship LiveBarn logos/branding.
- MIT licensed. Release signing key lives outside the repo (`LBTV_KEYSTORE_PROPERTIES`, default
  `../../Keys/MyLiveBarn/keystore.properties` next to the workspace); never commit keys.
- Versioning: `versionCode` +1 every build; `versionName` semver-ish (1.0, 1.0.1, 1.1…). Add a
  `CHANGELOG.md` entry. Builds go to `dist\RinkSide-<version>.apk` (dist/ is git-ignored).

## Architecture (where things are)
- `MainActivity`: modes LOADING / LOGIN / BROWSE (native screens) / WEBSITE; owns all remote keys;
  watch flow; JS bridge `window.LBTVNative`.
- `TvUi`: native screens + design tokens (graphite palette, white "lift" focus, LIVE pills,
  centred message cards). Focus is restored by view *tags* (`venue-<id>`, `rink-<id>`), because lists
  are rebuilt when live status refreshes.
- `NativePlayer`: Media3 player stage, "Connecting…" card until first frame, info overlay, retries.
- `assets/tv.js`: injected at document start. Reports sign-in (`onPage`), venues (from the site's
  IndexedDB `LiveBarnDB/venues`), favorites (`GET /userfavorite`), live status
  (`/surfaceactivefeedmode`), loaded master playlists (XHR hook → `onStream` with `X-LB-*` headers),
  stream-limit 403s, "stream has ended" texts. Website-mode highlight navigation + pointer.

## Hard-won lessons
- *Leave* rink pages with in-site navigation (`__lbtv.goHome()`), never a reload: the site only
  calls `/surface/streaming/revoke` on in-app route changes. *Enter* a rink with a full `loadUrl`
  after `prepareWatch()` clears `sessionStorage.edgeServer` (else the last rink's camera mode is
  reused → endless HTTP 400 playlist loop).
- Accept a stream only from the new rink page and only if `streamname` starts with the rink's UUID
  (the old page re-requests its playlist once after leaving).
- The native player must not send cookies to the CDN (`CookieHandler.setDefault(null)`): a stale
  Akamai `hdntl` cookie routed later streams to an unreachable origin. Old ones are purged at launch.
- "Camera online" ≠ live; live comes from `activeFeedModeStatus`.
- The hidden page keeps the session alive itself (`/surface/streaming/extend` ~every 9.5 min);
  verified with a 13-minute playback test.
- A clickable full-screen layer becomes focusable on Android 8+ — set `setFocusable(false)`.

## Testing
Shared Android TV emulator `TV_1080p_API36` (see `D:\Android Dev\CLAUDE.md`). The account owner
signs in inside the emulator window; never type credentials.
