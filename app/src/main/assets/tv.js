/*
 * LiveBarn TV helper, injected into watch.livebarn.com by MainActivity.
 *
 * Java owns the remote's keys and calls:
 *   __lbtv.move(dir)        dir = 'up' | 'down' | 'left' | 'right'
 *   __lbtv.activate()       OK button; returns {tap:[x,y], vw} when Java should send a real tap
 *   __lbtv.back()           returns true if it handled Back (closed video / dialog)
 *   __lbtv.media(action)    'toggle' | 'play' | 'pause'
 *   __lbtv.scrollAt(fx, fy, fdy)   pointer-mode scrolling (fractions of the view size)
 *   __lbtv.suppressWeb(on)  keep the page's video paused while the native player shows the stream
 *   __lbtv.playWeb()        fall back to the page's own player (full screen)
 *
 * Calls into Java via window.LBTVNative (requestNative / enabled) when present.
 */
(function () {
  'use strict';
  if (window.__lbtv) return;

  // Automatically fill the screen when a large video starts playing (website mode only).
  var AUTO_FULLSCREEN = true;

  // ------------------------------------------------------------------ bridge to the native app (2.0)
  // Everything here only *reads* what the site already has or receives; it never makes its own
  // requests to LiveBarn.

  function bridge() { return window.LBTVNative || null; }

  /** 'native' while the native TV screens are showing (page is hidden), else 'web'. */
  function isNativeMode() {
    var b = bridge();
    try { return !!b && b.mode() === 'native'; } catch (e) { return false; }
  }

  var favoritesReported = false;

  function observe(url, method, res, reqBody) {
    var b = bridge();
    if (!b) return;
    var path = String(url).replace(/^https?:\/\/[^/]+/, '').split('?')[0];
    if (/\/userfavorite\/?$/.test(path)) {
      if (method === 'GET' && res && res.ok) {
        res.text().then(function (t) { favoritesReported = true; try { b.onFavorites(t); } catch (e) {} });
      } else if (method === 'POST' && typeof reqBody === 'string') {
        // Favourites changed on the website: the request body is the new list.
        try { b.onFavorites(reqBody); } catch (e) {}
      }
    } else if (/\/surface\/uuid\//.test(path) && res && res.ok) {
      res.text().then(function (t) { try { b.onSurfaces(t); } catch (e) {} });
    } else if (/\/surfaceactivefeedmode\//.test(path) && res && res.ok) {
      // Which camera modes are actually streaming right now (the site's "Watch Live" vs "Not enough activity").
      res.text().then(function (t) { try { b.onFeedModes(t); } catch (e) {} });
    } else if (/\/surface\/streaming\/(live|vod)\//.test(path) && res && res.status === 403) {
      // "You're streaming somewhere else": the site shows a pop-up on this (hidden) page.
      res.text().then(function (t) {
        if (!/streaming_session_limit_reached/.test(t)) return;
        var myDevice = '';
        try { myDevice = sessionStorage.getItem('deviceId') || ''; } catch (e) {}
        try { b.onStreamLimit(t, myDevice); } catch (e) {}
      });
    }
  }

  // ---- stream playlists: report only ones that actually loaded (never a failed or stale one)

  (function hookXhr() {
    var P = XMLHttpRequest.prototype;
    if (P.__lbtv) return;
    P.__lbtv = true;
    var open = P.open, setHeader = P.setRequestHeader, send = P.send;
    P.open = function (method, url) {
      this.__lb = { u: String(url), h: {} };
      return open.apply(this, arguments);
    };
    P.setRequestHeader = function (k, v) {
      if (this.__lb) this.__lb.h[k] = String(v);
      return setHeader.apply(this, arguments);
    };
    P.send = function () {
      var x = this, info = x.__lb;
      if (info && /\.m3u8(\?|$)/i.test(info.u)) {
        x.addEventListener('loadend', function () { onPlaylist(info, x); });
      }
      return send.apply(this, arguments);
    };
  })();

  function onPlaylist(info, x) {
    var b = bridge();
    if (!b) return;
    var body = '';
    try { body = typeof x.responseText === 'string' ? x.responseText : ''; } catch (e) {}
    if (x.status === 200 && /#EXTM3U/.test(body)) {
      var master = /#EXT-X-STREAM-INF/.test(body);
      try { b.onStream(x.responseURL || info.u, JSON.stringify(info.h), master); } catch (e) {}
    } else if (x.status >= 400) {
      try { b.onStreamError(x.status); } catch (e) {}
    }
  }

  // ---- "nothing to watch" messages on a rink page (so the app can say so right away)

  var lastProblem = '';
  setInterval(function () {
    if (!/\/video\//.test(location.pathname)) { lastProblem = ''; return; }
    if (!isNativeMode()) return;
    var root = document.querySelector('main') || document.body;
    var t = root ? (root.innerText || '') : '';
    var m = t.match(/this live stream has ended|not enough activity|no live stream|stream is (currently )?(offline|unavailable)|camera is offline/i);
    if (m && m[0] !== lastProblem) {
      lastProblem = m[0];
      try { bridge().onWatchProblem(m[0]); } catch (e) {}
    }
  }, 1000);

  /** The site's cached camera-mode status for rinks it has looked at recently. */
  function reportCachedFeedModes() {
    var b = bridge();
    if (!b) return false;
    try {
      var v = localStorage.getItem('venuefeedmodes');
      if (v) b.onFeedModes(v);
    } catch (e) {}
    return true;
  }

  /** On the Favorites page, open a venue's row so the site loads its rinks' live status. */
  var expanded = {};
  function expandVenue(name) {
    if (!/\/favorite/.test(location.pathname) || expanded[name]) return false;
    var rows = document.querySelectorAll('tbody tr');
    for (var r = 0; r < rows.length; r++) {
      var cells = rows[r].querySelectorAll('td');
      for (var i = 0; i < cells.length; i++) {
        var t = (cells[i].innerText || '').trim();
        if (!t) continue;
        if (t.split('\n')[0].trim().toLowerCase() === String(name).toLowerCase()) {
          expanded[name] = true;
          (cells[i].querySelector('*') || cells[i]).click();
          return true;
        }
        break;
      }
    }
    return false;
  }

  // ---- cookie banner: decline non-essential cookies (the most private choice) so it stops appearing

  function declineCookies() {
    var btn = document.querySelector('.trustarc-declineall-btn');
    if (!btn) {
      var host = document.getElementById('truste-consent-track');
      var bs = host ? host.querySelectorAll('button') : [];
      for (var i = 0; i < bs.length; i++) if (/decline all/i.test(bs[i].innerText || '')) btn = bs[i];
    }
    if (btn && btn.offsetParent !== null) { btn.click(); return true; }
    return false;
  }
  var cookieTries = 0;
  var cookieTimer = setInterval(function () {
    if (declineCookies() || ++cookieTries > 45) clearInterval(cookieTimer);
  }, 1000);

  // ---- in-site navigation (keeps the site's own clean-up, e.g. ending a stream session on leave)

  function spaGo(path) {
    if (location.pathname === path) return true;
    history.pushState({}, '', path);
    window.dispatchEvent(new PopStateEvent('popstate', { state: {} }));
    return true;
  }

  /** Back to Favorites; leaving a video page this way makes the site end its stream session. */
  function goHome() { return spaGo('/en/favorite'); }

  /**
   * Before opening a rink: forget the camera mode the site remembers from the last rink (it would
   * otherwise ask for that mode even on rinks that don't have it), and leave any rink page cleanly.
   * Returns 'left' if it had to leave a rink page first.
   */
  function prepareWatch() {
    try { sessionStorage.removeItem('edgeServer'); } catch (e) {}
    if (/\/video\//.test(location.pathname)) { goHome(); return 'left'; }
    return 'ok';
  }

  function goVideo(surfaceId) {
    var target = '/en/video/' + surfaceId + '/live';
    if (/\/video\//.test(location.pathname) && location.pathname !== target) {
      goHome();
      setTimeout(function () { spaGo(target); }, 1200);
      return true;
    }
    return spaGo(target);
  }

  function limitDialogButton(label) {
    var dialogs = document.querySelectorAll('[role=dialog]');
    for (var i = 0; i < dialogs.length; i++) {
      if (!/streaming somewhere else|1 device at a time/i.test(dialogs[i].innerText || '')) continue;
      var buttons = dialogs[i].querySelectorAll('button');
      for (var j = 0; j < buttons.length; j++) {
        if ((buttons[j].innerText || '').trim().toLowerCase() === label) return buttons[j];
      }
    }
    return null;
  }

  /** Press the site's own "Stop" (end the other stream) or "Not now" in its pop-up. */
  function answerStreamLimit(stop) {
    var btn = limitDialogButton(stop ? 'stop' : 'not now');
    if (!btn) return false;
    btn.click();
    return true;
  }

  (function hookFetch() {
    var orig = window.fetch;
    if (!orig || orig.__lbtv) return;
    var wrapped = function (input, init) {
      var p = orig.apply(this, arguments);
      try {
        var url = typeof input === 'string' ? input : (input && input.url) || String(input);
        if (/oktaapi\.livebarn\.com/.test(url)) {
          var method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
          var body = init && init.body;
          p.then(function (res) { observe(url, method, res.clone(), body); }, function () {});
        }
      } catch (e) { /* never interfere with the site */ }
      return p;
    };
    wrapped.__lbtv = true;
    window.fetch = wrapped;
  })();

  // Login state / route reporting.
  var lastState = '';
  function reportPage() {
    var b = bridge();
    if (!b) return;
    var tok = '';
    try { tok = localStorage.getItem('oktatoken') || ''; } catch (e) {}
    var path = location.pathname;
    var authPage = /\/(signin|signup|authorize|login|forgot|reset|register)/i.test(path);
    var loggedIn = !!tok && tok !== 'null' && !authPage;
    var state = path + '|' + loggedIn;
    if (state === lastState) return;
    lastState = state;
    try { b.onPage(path, loggedIn); } catch (e) {}
  }
  ['pushState', 'replaceState'].forEach(function (fn) {
    var orig = history[fn];
    if (!orig || orig.__lbtv) return;
    history[fn] = function () { var r = orig.apply(this, arguments); setTimeout(reportPage, 0); return r; };
    history[fn].__lbtv = true;
  });
  window.addEventListener('popstate', function () { setTimeout(reportPage, 0); });
  document.addEventListener('DOMContentLoaded', reportPage);
  window.addEventListener('load', reportPage);
  setInterval(reportPage, 1500);

  /** Read the site's own venue cache (IndexedDB) and hand a compact copy to the app. */
  function loadVenues() {
    var b = bridge();
    if (!b) return false;
    function send(list) { try { b.onVenues(JSON.stringify(list)); } catch (e) {} }
    var req;
    try { req = indexedDB.open('LiveBarnDB'); } catch (e) { send([]); return true; }
    req.onupgradeneeded = function () {
      // The site hasn't created its cache yet: back out without creating anything.
      try { req.transaction.abort(); } catch (e) {}
    };
    req.onerror = function () { send([]); };
    req.onsuccess = function () {
      var db = req.result;
      if (!db.objectStoreNames.contains('venues')) { db.close(); send([]); return; }
      var out = [];
      var now = Date.now();
      function ts(v) { if (v == null) return null; var t = typeof v === 'number' ? v : Date.parse(v); return isNaN(t) ? null : t; }
      var cur = db.transaction('venues', 'readonly').objectStore('venues').openCursor();
      cur.onsuccess = function () {
        var c = cur.result;
        if (!c) { db.close(); send(out); return; }
        var v = c.value || {};
        var prov = v.province || {};
        var logo = v.logoUrl ? (v.logoUrl['240'] || v.logoUrl['100'] || '') : '';
        var surfaces = (v.surfaces || []).map(function (s, i) {
          var from = ts(s.closedFrom), until = ts(s.closedUntil);
          var closed = from !== null && now >= from && (until === null || now < until);
          return {
            i: s.id, u: s.uuid || '', n: s.name, o: !!s.online, cs: !!s.comingSoon, cl: closed,
            st: (s.surfaceStatus && s.surfaceStatus.name) || '', x: s.orderIndex != null ? s.orderIndex : i
          };
        });
        var status = v.venueStatus && v.venueStatus.name;
        if (!status || /active/i.test(status)) {
          out.push({ i: v.id, n: v.name, c: v.city || '', p: prov.name || '', k: (prov.country && prov.country.name) || '', l: logo, s: surfaces });
        }
        c.continue();
      };
      cur.onerror = function () { db.close(); send(out); };
    };
    return true;
  }

  /** Fallback when the favourites response wasn't seen: read venue names off the Favorites page. */
  function scrapeFavorites() {
    var b = bridge();
    if (!b || favoritesReported || !/\/favorite/.test(location.pathname)) return false;
    var names = [];
    var rows = document.querySelectorAll('tbody tr');
    for (var r = 0; r < rows.length; r++) {
      var cells = rows[r].querySelectorAll('td');
      for (var i = 0; i < cells.length; i++) {
        var t = (cells[i].innerText || '').trim();
        if (t) { names.push(t.split('\n')[0].trim()); break; }
      }
    }
    if (names.length) { try { b.onFavoriteNames(JSON.stringify(names)); } catch (e) {} }
    return names.length > 0;
  }

  var FOCUS_ATTR = 'data-lbtv-focus';
  var css = [
    '[' + FOCUS_ATTR + '] {',
    '  outline: 4px solid #ffc400 !important; outline-offset: 2px !important;',
    '  box-shadow: 0 0 0 8px rgba(255,196,0,.35) !important;',
    '}',
    'video[' + FOCUS_ATTR + '] { outline-offset: -6px !important; }',
    // Only one highlight: hide the site's own focus ring on anything that isn't ours.
    '*:focus:not([' + FOCUS_ATTR + ']), *:focus-visible:not([' + FOCUS_ATTR + ']) { outline: none !important; }',
    'html[data-lbtv-theater], html[data-lbtv-theater] body { overflow: hidden !important; }',
    '[data-lbtv-anc] {',
    '  transform: none !important; filter: none !important; backdrop-filter: none !important;',
    '  perspective: none !important; contain: none !important; will-change: auto !important;',
    '  z-index: 2147483646 !important;',
    '}',
    'video[data-lbtv-theater-video] {',
    '  position: fixed !important; top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important;',
    '  width: 100vw !important; height: 100vh !important; max-width: none !important; max-height: none !important;',
    '  margin: 0 !important; z-index: 2147483647 !important; background: #000 !important;',
    '  object-fit: contain !important; outline: none !important; box-shadow: none !important;',
    '}',
    '::-webkit-scrollbar { width: 0 !important; height: 0 !important; }'
  ].join('\n');

  function addStyle() {
    if (document.getElementById('lbtv-style')) return;
    var parent = document.head || document.documentElement;
    if (!parent) return; // injected at document start: retried on DOMContentLoaded
    var s = document.createElement('style');
    s.id = 'lbtv-style';
    s.textContent = css;
    parent.appendChild(s);
  }
  addStyle();
  document.addEventListener('DOMContentLoaded', addStyle);

  // ------------------------------------------------------------------ candidates

  var NATIVE_SEL = [
    'a[href]', 'button', 'input:not([type=hidden])', 'select', 'textarea', 'summary', 'video',
    '[role=button]', '[role=link]', '[role=tab]', '[role=menuitem]', '[role=option]',
    '[role=checkbox]', '[role=radio]', '[role=switch]', '[contenteditable=true]',
    '[tabindex]:not([tabindex="-1"])', '[onclick]'
  ].join(',');

  var SKIP_TAGS = { HTML: 1, BODY: 1, SCRIPT: 1, STYLE: 1, svg: 1, path: 1, g: 1, use: 1, circle: 1, rect: 1, line: 1, polygon: 1, polyline: 1, IMG: 1, BR: 1, OPTION: 1 };

  function viewportH() { return window.innerHeight || document.documentElement.clientHeight; }
  function viewportW() { return window.innerWidth || document.documentElement.clientWidth; }

  function nearViewport(r) {
    var h = viewportH();
    return r.width >= 4 && r.height >= 4 && r.bottom > -1.5 * h && r.top < 2.5 * h &&
      r.right > 0 && r.left < viewportW();
  }

  function isShown(el) {
    var cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none' && parseFloat(cs.opacity || '1') > 0.05 &&
      cs.pointerEvents !== 'none';
  }

  function isDisabled(el) {
    return el.disabled === true || el.getAttribute('aria-disabled') === 'true';
  }

  // Topmost open pop-up, if any: navigation stays inside it.
  // Thin bars (e.g. the cookie banner, which also calls itself a modal dialog) don't count,
  // otherwise they would trap the highlight until answered.
  function activeDialog() {
    var list = document.querySelectorAll('[role=dialog], [role=alertdialog], [aria-modal=true], .modal.show, .modal.in');
    for (var i = list.length - 1; i >= 0; i--) {
      var r = list[i].getBoundingClientRect();
      if (r.width > 120 && r.height > viewportH() * 0.2 && isShown(list[i])) return list[i];
    }
    return null;
  }

  function candidates() {
    var scope = activeDialog() || document.body;
    if (!scope) return [];
    var out = [];
    var seen = new Set();

    var natives = scope.querySelectorAll(NATIVE_SEL);
    for (var i = 0; i < natives.length; i++) {
      var el = natives[i];
      if (seen.has(el) || isDisabled(el)) continue;
      var r = el.getBoundingClientRect();
      if (!nearViewport(r) || !isShown(el)) continue;
      seen.add(el);
      out.push({ el: el, r: r });
    }

    // Plain <div>/<span> etc. with click handlers usually have cursor:pointer.
    // Keep only the outermost such element (children inherit the cursor).
    var all = scope.getElementsByTagName('*');
    for (var j = 0; j < all.length; j++) {
      var e = all[j];
      if (seen.has(e) || SKIP_TAGS[e.tagName]) continue;
      var rr = e.getBoundingClientRect();
      if (!nearViewport(rr)) continue;
      var cs = getComputedStyle(e);
      if (cs.cursor !== 'pointer' || cs.visibility === 'hidden') continue;
      var p = e.parentElement;
      if (p && getComputedStyle(p).cursor === 'pointer') continue;
      if (e.closest('a[href],button,[role=button]')) continue;
      seen.add(e);
      out.push({ el: e, r: rr });
    }
    return out;
  }

  // ------------------------------------------------------------------ focus

  var current = null;

  function usable(el) {
    if (!el || !el.isConnected) return false;
    var r = el.getBoundingClientRect();
    return r.width >= 2 && r.height >= 2 && isShown(el);
  }

  function isTextField(el) {
    if (!el) return false;
    if (el.isContentEditable || el.tagName === 'TEXTAREA') return true;
    if (el.tagName !== 'INPUT') return false;
    var t = (el.getAttribute('type') || 'text').toLowerCase();
    return ['text', 'email', 'password', 'search', 'tel', 'url', 'number', 'date', 'time', 'datetime-local', 'month', 'week'].indexOf(t) >= 0;
  }

  function setCurrent(el) {
    if (current && current !== el) current.removeAttribute(FOCUS_ATTR);
    current = el;
    if (!el) return;
    el.setAttribute(FOCUS_ATTR, '');
    // Let hover menus/tooltips react.
    try {
      el.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
      el.dispatchEvent(new MouseEvent('mouseenter', { bubbles: false }));
    } catch (e) { /* ignore */ }
    // Native focus (not for text fields: that's done with a real tap so the keyboard opens).
    if (!isTextField(el) && typeof el.focus === 'function' && el.matches('a[href],button,select,summary,[tabindex]')) {
      try { el.focus({ preventScroll: true }); } catch (e) { /* ignore */ }
    }
    el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'auto' });
  }

  function center(r) { return { x: (r.left + r.right) / 2, y: (r.top + r.bottom) / 2 }; }

  function gap(a1, a2, b1, b2) { return Math.max(0, Math.max(a1, b1) - Math.min(a2, b2)); }

  function score(dir, a, b) {
    var ca = center(a), cb = center(b), primary, ortho, drift;
    if (dir === 'down') {
      if (cb.y <= ca.y + 1 || b.top < a.top + 2) return null;
      primary = Math.max(0, b.top - a.bottom); ortho = gap(a.left, a.right, b.left, b.right); drift = Math.abs(cb.x - ca.x);
    } else if (dir === 'up') {
      if (cb.y >= ca.y - 1 || b.bottom > a.bottom - 2) return null;
      primary = Math.max(0, a.top - b.bottom); ortho = gap(a.left, a.right, b.left, b.right); drift = Math.abs(cb.x - ca.x);
    } else if (dir === 'right') {
      if (cb.x <= ca.x + 1 || b.left < a.left + 2) return null;
      primary = Math.max(0, b.left - a.right); ortho = gap(a.top, a.bottom, b.top, b.bottom); drift = Math.abs(cb.y - ca.y);
    } else {
      if (cb.x >= ca.x - 1 || b.right > a.right - 2) return null;
      primary = Math.max(0, a.left - b.right); ortho = gap(a.top, a.bottom, b.top, b.bottom); drift = Math.abs(cb.y - ca.y);
    }
    return { s: primary + ortho * 4 + drift * 0.15, aligned: ortho === 0 };
  }

  function firstInView(list) {
    var h = viewportH(), best = null, bestScore = Infinity;
    for (var i = 0; i < list.length; i++) {
      var r = list[i].r;
      if (r.top < 0 || r.bottom > h) continue;
      var s = r.top * 3 + r.left;
      if (s < bestScore) { bestScore = s; best = list[i].el; }
    }
    return best || (list[0] && list[0].el) || null;
  }

  function move(dir) {
    addStyle();
    if (theaterVideo()) return 'theater';
    var list = candidates();
    if (!usable(current)) {
      var first = firstInView(list);
      if (first) setCurrent(first);
      else window.scrollBy(0, (dir === 'up' ? -0.6 : 0.6) * viewportH());
      return 'start';
    }
    var a = current.getBoundingClientRect();
    // Left/Right prefer things on the same row; only jump rows when nothing is level.
    var sideways = dir === 'left' || dir === 'right';
    var best = null, bestScore = Infinity, bestAligned = null, bestAlignedScore = Infinity;
    for (var i = 0; i < list.length; i++) {
      var c = list[i];
      if (c.el === current || c.el.contains(current)) continue;
      var s = score(dir, a, c.r);
      if (s === null) continue;
      if (s.s < bestScore) { bestScore = s.s; best = c.el; }
      if (s.aligned && s.s < bestAlignedScore) { bestAlignedScore = s.s; bestAligned = c.el; }
    }
    if (sideways && bestAligned) best = bestAligned;
    if (best) { setCurrent(best); return 'moved'; }
    // Nothing further that way: scroll so more content can come into reach.
    if (dir === 'up' || dir === 'down') window.scrollBy(0, (dir === 'up' ? -0.6 : 0.6) * viewportH());
    return 'edge';
  }

  // ------------------------------------------------------------------ video "theater" (fills the screen)

  var theater = null;
  var autoDone = new WeakSet();

  function theaterVideo() {
    if (theater && !theater.isConnected) exitTheater();
    return theater;
  }

  function enterTheater(v) {
    if (theater === v) return;
    exitTheater();
    theater = v;
    v.setAttribute('data-lbtv-theater-video', '');
    document.documentElement.setAttribute('data-lbtv-theater', '');
    for (var p = v.parentElement; p && p !== document.documentElement; p = p.parentElement) {
      p.setAttribute('data-lbtv-anc', '');
    }
  }

  function exitTheater() {
    if (!theater) return false;
    var v = theater;
    theater = null;
    v.removeAttribute('data-lbtv-theater-video');
    document.documentElement.removeAttribute('data-lbtv-theater');
    var anc = document.querySelectorAll('[data-lbtv-anc]');
    for (var i = 0; i < anc.length; i++) anc[i].removeAttribute('data-lbtv-anc');
    if (v.isConnected) setCurrent(v);
    return true;
  }

  function area(el) { var r = el.getBoundingClientRect(); return r.width * r.height; }

  // ---- native player hand-off (1.1): while Android's player shows the stream, keep the page's
  // own video paused and silent (the page stays loaded so it keeps the stream session alive).
  var suppressed = false;

  function nativeBridge() { return window.LBTVNative || null; }

  function suppressWeb(on) {
    suppressed = !!on;
    if (suppressed) {
      exitTheater();
      var vs = document.querySelectorAll('video');
      for (var i = 0; i < vs.length; i++) { vs[i].muted = true; vs[i].pause(); }
    }
    return suppressed;
  }

  function playWeb() {
    suppressed = false;
    var v = pickVideo();
    if (!v) return false;
    v.muted = false;
    // It was paused while the TV player showed the stream: jump back to the live edge.
    try {
      if (v.seekable && v.seekable.length) {
        var end = v.seekable.end(v.seekable.length - 1);
        if (end - v.currentTime > 6) v.currentTime = Math.max(0, end - 3);
      }
    } catch (e) {}
    enterTheater(v);
    var p = v.play(); if (p && p.catch) p.catch(function () {});
    return true;
  }

  document.addEventListener('play', function (e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO') return;
    if (suppressed) { v.muted = true; v.pause(); }
    else if (isNativeMode()) {
      v.muted = true; // hidden page starting a stream for the native player
      // …and if the app no longer wants a stream (cancelled while loading), don't play at all.
      var b = bridge();
      try { if (b && b.wantsStream && !b.wantsStream()) v.pause(); } catch (e) {}
    }
  }, true);

  document.addEventListener('playing', function (e) {
    var v = e.target;
    if (suppressed && v && v.tagName === 'VIDEO') { v.pause(); return; }
    if (isNativeMode()) return;
    if (!AUTO_FULLSCREEN || !v || v.tagName !== 'VIDEO' || autoDone.has(v)) return;
    if (v.getBoundingClientRect().width < viewportW() * 0.3) return; // ignore thumbnails/previews
    autoDone.add(v);
    enterTheater(v);
  }, true);

  function pickVideo() {
    if (theaterVideo()) return theater;
    var vs = Array.prototype.slice.call(document.querySelectorAll('video'));
    if (!vs.length) return null;
    for (var i = 0; i < vs.length; i++) if (!vs[i].paused) return vs[i];
    vs.sort(function (a, b) { return area(b) - area(a); });
    return vs[0];
  }

  function media(action) {
    var v = pickVideo();
    if (!v) return false;
    if (action === 'pause' || (action === 'toggle' && !v.paused)) v.pause();
    else { var p = v.play(); if (p && p.catch) p.catch(function () {}); }
    return true;
  }

  // ------------------------------------------------------------------ OK / Back

  function activate() {
    addStyle();
    if (theaterVideo()) { media('toggle'); return {}; }
    if (!usable(current)) { move('down'); return {}; }
    var el = current;
    if (el.tagName === 'VIDEO') {
      var nb = nativeBridge();
      if (nb && nb.requestNative()) return {};
      el.muted = false;
      enterTheater(el);
      if (el.paused) media('play');
      return {};
    }
    if (isTextField(el) || el.tagName === 'SELECT') {
      var r = el.getBoundingClientRect();
      var c = center(r);
      return { tap: [c.x, c.y], vw: viewportW() };
    }
    el.click();
    return {};
  }

  function back() {
    if (theaterVideo()) { exitTheater(); return true; }
    var dlg = activeDialog();
    if (dlg) {
      var close = dlg.querySelector('[aria-label="Close" i], [aria-label="close"], .btn-close, button.close, [data-dismiss=modal], [data-bs-dismiss=modal]');
      if (close) { close.click(); return true; }
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
      return true;
    }
    return false;
  }

  // ------------------------------------------------------------------ pointer-mode scrolling

  function scrollAt(fx, fy, fdy) {
    var x = fx * viewportW(), y = fy * viewportH(), dy = fdy * viewportW();
    for (var el = document.elementFromPoint(x, y); el && el !== document.body; el = el.parentElement) {
      var cs = getComputedStyle(el);
      if (/(auto|scroll)/.test(cs.overflowY) && el.scrollHeight > el.clientHeight + 2) {
        el.scrollBy(0, dy);
        return true;
      }
    }
    window.scrollBy(0, dy);
    return true;
  }

  window.__lbtv = {
    version: 2,
    move: move,
    activate: activate,
    back: back,
    media: media,
    scrollAt: scrollAt,
    suppressWeb: suppressWeb,
    playWeb: playWeb,
    loadVenues: loadVenues,
    scrapeFavorites: scrapeFavorites,
    reportPage: reportPage,
    goHome: goHome,
    goVideo: goVideo,
    prepareWatch: prepareWatch,
    answerStreamLimit: answerStreamLimit,
    reportCachedFeedModes: reportCachedFeedModes,
    expandVenue: expandVenue,
    declineCookies: declineCookies
  };
})();
