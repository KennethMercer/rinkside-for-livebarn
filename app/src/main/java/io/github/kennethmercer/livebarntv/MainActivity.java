package io.github.kennethmercer.livebarntv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LiveBarn TV.
 *
 * The real LiveBarn website (watch.livebarn.com) always runs in a WebView at the back. It is shown
 * only for signing in (and on request, via the "Website" tab). Everything else is native TV UI
 * (TvUi) fed by what that page already has: its venue cache and the responses it receives.
 *
 * Watching a rink = asking the hidden page to open that rink's live page, then handing the stream
 * playlist it requests to Android's own player (NativePlayer). The page stays loaded, paused, so
 * it keeps the stream session alive exactly as it would in a browser.
 */
public class MainActivity extends Activity implements TvUi.Host {

    private static final String SITE = "https://watch.livebarn.com";
    private static final String HOME_URL = SITE + "/en/favorite";
    private static final long WATCH_TIMEOUT_MS = 30_000;

    /** Present as desktop Chrome so the site shows its full layout instead of "get the app" prompts. */
    private static final boolean DESKTOP_USER_AGENT = true;

    private enum Mode { LOADING, LOGIN, BROWSE, WEBSITE }

    private Mode mode = Mode.LOADING;

    private FrameLayout root;
    private WebView web;
    private CursorView cursor;
    private TvUi ui;
    private NativePlayer nativePlayer;
    private final Catalog catalog = new Catalog();
    private final ImageLoader images = new ImageLoader();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private String tvJs = "";
    private String userAgent;
    private boolean loggedIn;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private boolean cursorMode;
    private boolean centerLongPressed;
    private long lastBackPress;
    private int websiteHistoryStart;
    private Toast toast;

    // ---- watching
    /** Set while the hidden page is starting a stream we asked for from the native UI. */
    private volatile boolean watchPending;
    private String watchUuid = "";
    private int watchSurfaceId;
    /** The rink page we asked for has started loading (reports before that are from the old page). */
    private boolean watchPageStarted;
    private static final String TAG = "LBTV";
    private boolean watchingFromUi;
    private String watchTitle = "";
    private Catalog.Venue watchVenue;
    private Catalog.Surface watchSurface;
    /** Bumped on every watch/cancel so delayed callbacks from an older attempt do nothing. */
    private int watchGeneration;
    private final Runnable watchTimeout = this::onWatchTimeout;
    private volatile String streamUrl;
    private volatile Map<String, String> streamHeaders;
    private volatile String webOnlyUrl;
    private int venueRetries;
    private int playlistErrors;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No window-wide "keep screen on": the TV's screensaver should run on idle menus (burn-in).
        // The native player keeps the screen on only while video is showing.
        // The native player must NOT share the page's cookies: the site's own player never sends
        // cookies to the video CDN, and a leftover Akamai "hdntl" token cookie from an earlier stream
        // made later streams get routed to an unreachable origin server (endless spinner).
        CookieHandler.setDefault(null);
        purgeCdnCookies();
        prefs = getSharedPreferences("lbtv", Context.MODE_PRIVATE);
        tvJs = readAsset("tv.js");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = createWebView();
        root.addView(web, matchParent());

        ui = new TvUi(this, this, catalog, images);
        root.addView(ui.root, matchParent());

        cursor = new CursorView(this);
        cursor.setVisibility(View.GONE);
        root.addView(cursor, matchParent());

        nativePlayer = new NativePlayer(this, root, userAgent, new NativePlayer.Listener() {
            @Override
            public void onNativeFailed(String reason) {
                MainActivity.this.onNativeFailed(reason);
            }

            @Override
            public void onStreamEnded() {
                MainActivity.this.onStreamEnded();
            }
        });
        setContentView(root);

        setMode(Mode.LOADING);
        ui.showStatus("Connecting to LiveBarn…", null, true, null, new TvUi.Action("Open website", () -> openWebsite(null)));
        web.loadUrl(HOME_URL);
    }

    /** Remove video-CDN cookies that older versions (0.1.1–0.2.4) stored via their cookie bridge. */
    private static void purgeCdnCookies() {
        CookieManager cm = CookieManager.getInstance();
        String[] hosts = {"cdn-akamai-livebarn.akamaized.net", "cdn.akamai.livebarn.com"};
        for (String host : hosts) {
            String url = "https://" + host + "/";
            String all = cm.getCookie(url);
            if (all == null) continue;
            for (String pair : all.split(";")) {
                String name = pair.split("=", 2)[0].trim();
                if (name.isEmpty()) continue;
                String expire = name + "=; Max-Age=0; Path=/";
                cm.setCookie(url, expire);
                cm.setCookie(url, expire + "; Domain=" + host);
                cm.setCookie(url, expire + "; Domain=." + host);
            }
        }
        cm.flush();
    }

    // ================================================================ modes

    private void setMode(Mode m) {
        mode = m;
        boolean nativeUi = m == Mode.LOADING || m == Mode.BROWSE;
        ui.root.setVisibility(nativeUi ? View.VISIBLE : View.GONE);
        // The page keeps running underneath (visible to the WebView, just covered) so it isn't throttled.
        web.setFocusable(!nativeUi);
        web.setFocusableInTouchMode(!nativeUi);
        if (nativeUi) {
            setCursorMode(false);
            ui.focus();
        } else {
            web.requestFocus();
        }
    }

    private void showBrowse() {
        // Only the first time (after loading / sign-in) starts on Favorites; coming back from the
        // website keeps whatever screen you were on (a venue, a search…).
        boolean first = mode == Mode.LOADING || mode == Mode.LOGIN;
        setMode(Mode.BROWSE);
        if (first) {
            ui.hideStatus();
            ui.showFavorites();
        } else {
            ui.restoreFocus();
        }
        requestVenues();
    }

    // ================================================================ WebView

    private WebView createWebView() {
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);

        WebView w = new WebView(this);
        w.setBackgroundColor(Color.BLACK);

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        if (DESKTOP_USER_AGENT) {
            s.setUserAgentString(desktopUserAgent(WebSettings.getDefaultUserAgent(this)));
        }
        userAgent = s.getUserAgentString();
        w.addJavascriptInterface(new NativeBridge(), "LBTVNative");

        // Run tv.js before the site's own scripts when possible, so it sees the site's first responses.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && !tvJs.isEmpty()) {
            WebViewCompat.addDocumentStartJavaScript(w, tvJs, Collections.singleton(SITE));
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(w, true);

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                String host = u.getHost() == null ? "" : u.getHost();
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    showToast("That link only works on a phone or computer");
                    return true;
                }
                if (host.endsWith("play.google.com") || host.endsWith("apps.apple.com")
                        || host.endsWith("itunes.apple.com")) {
                    showToast("That link only works on a phone or computer");
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (watchPending && url != null && url.contains("/video/" + watchSurfaceId + "/")) {
                    watchPageStarted = true;
                }
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                injectTvScript();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectTvScript();
                CookieManager.getInstance().flush();
                if (url != null && url.contains("/favorite")) {
                    // If the favourites response was missed, read names off the page instead.
                    main.postDelayed(() -> js("__lbtv.scrapeFavorites()"), 5000);
                }
                // A rink page that nobody wants any more (watch cancelled while it was loading):
                // leave it, so it doesn't hold the account's one stream in the background.
                if (url != null && url.contains("/video/") && !watchPending && !nativePlayer.isActive()
                        && (mode == Mode.BROWSE || mode == Mode.LOADING)) {
                    leaveToHome();
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                recreate();
                return true;
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                root.addView(view, matchParent());
                setCursorMode(false);
            }

            @Override
            public void onHideCustomView() {
                exitCustomView();
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                Bitmap b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.BLACK);
                return b;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });
        return w;
    }

    private void exitCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        WebChromeClient.CustomViewCallback cb = customViewCallback;
        customViewCallback = null;
        if (cb != null) cb.onCustomViewHidden();
        if (mode == Mode.WEBSITE || mode == Mode.LOGIN) web.requestFocus();
    }

    private static String desktopUserAgent(String defaultUa) {
        String major = "130";
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(defaultUa == null ? "" : defaultUa);
        if (m.find()) major = m.group(1);
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + major + ".0.0.0 Safari/537.36";
    }

    private void injectTvScript() {
        if (!tvJs.isEmpty()) web.evaluateJavascript(tvJs, null);
        js("__lbtv.reportPage()");
    }

    private void js(String code) {
        web.evaluateJavascript("(function(){try{return window.__lbtv?(" + code + "):null}catch(e){return null}})()", null);
    }

    // ================================================================ page → app bridge

    /** Called from tv.js on the WebView's JS thread. */
    private final class NativeBridge {
        @JavascriptInterface
        public String mode() {
            Mode m = mode;
            return (m == Mode.BROWSE || m == Mode.LOADING || watchPending) ? "native" : "web";
        }

        @JavascriptInterface
        public boolean enabled() {
            return true;
        }

        /** Whether the app is currently waiting for (or playing) a stream from this page. */
        @JavascriptInterface
        public boolean wantsStream() {
            return watchPending || nativePlayer.isActive() || mode == Mode.WEBSITE;
        }

        @JavascriptInterface
        public void onPage(String path, boolean isLoggedIn) {
            main.post(() -> onPageState(path, isLoggedIn));
        }

        @JavascriptInterface
        public void onVenues(String json) {
            worker.execute(() -> {
                Catalog parsed = new Catalog();
                try {
                    parsed.loadVenues(json);
                } catch (Exception e) {
                    return;
                }
                main.post(() -> {
                    if (parsed.venues.isEmpty()) {
                        // The site fills its cache shortly after sign-in; try again in a moment.
                        if (venueRetries++ < 20) main.postDelayed(MainActivity.this::requestVenues, 3000);
                        return;
                    }
                    catalog.venues.clear();
                    catalog.venues.putAll(parsed.venues);
                    ui.onDataChanged();
                });
            });
        }

        @JavascriptInterface
        public void onFavorites(String json) {
            main.post(() -> {
                try {
                    catalog.setSiteFavorites(json);
                    ui.onDataChanged();
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void onFavoriteNames(String json) {
            main.post(() -> {
                try {
                    JSONArray a = new JSONArray(json);
                    List<String> names = new ArrayList<>();
                    for (int i = 0; i < a.length(); i++) names.add(a.getString(i));
                    catalog.setSiteFavoriteNames(names);
                    ui.onDataChanged();
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void onSurfaces(String json) {
            main.post(() -> {
                if (catalog.updateSurfaces(json)) ui.onDataChanged();
            });
        }

        /** A stream playlist the page loaded successfully (URL + the headers the site sent with it). */
        @JavascriptInterface
        public void onStream(String url, String headersJson, boolean master) {
            main.post(() -> onStreamFromPage(url, headersJson, master));
        }

        @JavascriptInterface
        public void onStreamError(int status) {
            main.post(() -> {
                if (watchPending && ++playlistErrors >= 4) {
                    showNotStreaming("The camera may be off between games. Try again in a minute.");
                }
            });
        }

        /** The rink page says there's nothing to watch ("This live stream has ended", …). */
        @JavascriptInterface
        public void onWatchProblem(String text) {
            main.post(() -> {
                if (watchPending && text != null && !text.isEmpty()) {
                    showNotStreaming(Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".");
                }
            });
        }

        @JavascriptInterface
        public void onFeedModes(String json) {
            main.post(() -> {
                if (catalog.updateFeedModes(json)) ui.onDataChanged();
            });
        }

        /** The site refused a new stream because another session is using the account's slot. */
        @JavascriptInterface
        public void onStreamLimit(String json, String myDeviceId) {
            main.post(() -> handleStreamLimit(json, myDeviceId));
        }

        /** Website mode: OK pressed on the page's video. */
        @JavascriptInterface
        public boolean requestNative() {
            String url = streamUrl;
            if (url == null || url.equals(webOnlyUrl)) return false;
            main.post(() -> {
                if (!nativePlayer.isActive()) startNative();
            });
            return true;
        }
    }

    private void onPageState(String path, boolean isLoggedIn) {
        loggedIn = isLoggedIn;
        if (!isLoggedIn) {
            boolean authFlow = path.contains("/authorize");
            if (watchPending) cancelWatch(false);
            if (nativePlayer.isActive() && watchingFromUi) stopWatching(false);
            if (mode != Mode.LOGIN && mode != Mode.WEBSITE && !authFlow) {
                ui.hideStatus();
                setMode(Mode.LOGIN);
                showToast("Sign in with your LiveBarn account");
            }
            return;
        }
        if (mode == Mode.LOADING || mode == Mode.LOGIN) {
            showBrowse();
            if (!path.contains("/favorite") && !watchPending) web.loadUrl(HOME_URL); // fetch favourites
        } else if (!catalog.hasVenues()) {
            requestVenues();
        }
    }

    private void requestVenues() {
        js("__lbtv.reportCachedFeedModes()");
        if (catalog.hasVenues()) return;
        js("__lbtv.loadVenues()");
    }

    @Override
    public void venueOpened(Catalog.Venue venue) {
        // If it's on the site's Favorites page, open its row there so the site loads live status.
        String name = JSONObject.quote(venue.name);
        js("__lbtv.reportCachedFeedModes() && __lbtv.expandVenue(" + name + ")");
    }

    // ================================================================ TvUi.Host

    @Override
    public void watch(Catalog.Venue venue, Catalog.Surface surface) {
        watchGeneration++;
        watchVenue = venue;
        watchSurface = surface;
        watchTitle = surface.name;
        streamUrl = null;
        webOnlyUrl = null;
        ui.rememberTag("rink-" + surface.id); // Back from the player returns to this rink
        nativePlayer.setCanSwitch(venue.surfaces.size() > 1);
        watchUuid = surface.uuid == null ? "" : surface.uuid;
        watchSurfaceId = surface.id;
        watchPageStarted = false;
        watchPending = true;
        watchingFromUi = true;
        playlistErrors = 0;
        nativePlayer.showConnecting(surface.name, venue.name);
        ui.showStatus(surface.name, venue.name + " · Connecting…", true, () -> cancelWatch(true));
        // 1) Leave any rink page through the site's own navigation (ends that stream session) and
        //    forget the camera mode it remembers from the last rink; 2) open the rink with a fresh
        //    page load so the site picks a camera mode this rink actually has.
        String videoUrl = SITE + "/en/video/" + surface.id + "/live";
        web.evaluateJavascript("(function(){try{return window.__lbtv?__lbtv.prepareWatch():'none'}catch(e){return 'none'}})()",
                r -> {
                    long delay = r != null && r.contains("left") ? 1500 : 0;
                    int gen = watchGeneration;
                    main.postDelayed(() -> {
                        // Only if this is still the current watch attempt (not cancelled/replaced).
                        if (watchPending && gen == watchGeneration) web.loadUrl(videoUrl);
                    }, delay);
                });
        main.removeCallbacks(watchTimeout);
        main.postDelayed(watchTimeout, WATCH_TIMEOUT_MS);
    }

    private void showNotStreaming(String why) {
        if (!watchPending) return;
        watchPending = false;
        main.removeCallbacks(watchTimeout);
        nativePlayer.release();
        ui.showStatus(watchTitle + " isn't live right now", why, false,
                () -> cancelWatch(true),
                new TvUi.Action("Back to rinks", () -> cancelWatch(true)),
                new TvUi.Action("Open website", () -> {
                    watchingFromUi = false;
                    openWebsite(null);
                }));
    }

    /** Leave any rink page via the site's own navigation, which ends its stream session (frees the slot). */
    private void leaveToHome() {
        web.evaluateJavascript("(function(){try{return window.__lbtv?__lbtv.goHome():false}catch(e){return false}})()",
                r -> {
                    String u = web.getUrl();
                    if (!"true".equals(r) && (u == null || !u.contains("/favorite"))) web.loadUrl(HOME_URL);
                });
    }

    private void handleStreamLimit(String json, String myDeviceId) {
        if (!watchPending) return;
        main.removeCallbacks(watchTimeout);
        String device = "another device";
        String rink = "";
        boolean ownLeftover = false;
        try {
            JSONArray sessions = new JSONObject(json).getJSONObject("extra").getJSONArray("existingStreamingSessions");
            if (sessions.length() > 0) {
                JSONObject s = sessions.getJSONObject(0);
                device = s.optString("deviceName", "");
                if (device.isEmpty() || "null".equals(device)) device = s.optString("devicePlatform", "another device");
                rink = rinkName(s.optInt("surfaceId", -1));
                // Same browser signature as this app: almost certainly this TV's own earlier session
                // (e.g. the app was closed or the TV slept mid-stream).
                if (userAgent != null && userAgent.equals(s.optString("deviceType", ""))) {
                    device = "this TV (an earlier session)";
                }
                String id = s.optString("deviceId", "");
                ownLeftover = !id.isEmpty() && myDeviceId != null && myDeviceId.contains(id);
            }
        } catch (Exception ignored) {
        }
        if (ownLeftover) {
            // This TV's own previous stream is still registered: end it and carry on.
            stopOtherStream();
            return;
        }
        nativePlayer.release();
        ui.showStatus("Playing on another screen",
                "Your plan plays on one screen at a time. Now playing: "
                        + (rink.isEmpty() ? "" : rink + " on ") + device + ".",
                false,
                () -> cancelWatch(true),
                new TvUi.Action("Watch here instead", this::stopOtherStream),
                new TvUi.Action("Cancel", () -> cancelWatch(true)));
    }

    private void stopOtherStream() {
        if (watchSurface != null && watchVenue != null) nativePlayer.showConnecting(watchSurface.name, watchVenue.name);
        ui.showStatus(watchTitle, (watchVenue != null ? watchVenue.name + " · " : "") + "Connecting…", true, () -> cancelWatch(true));
        js("__lbtv.answerStreamLimit(true)"); // presses the site's own "Stop" button
        main.removeCallbacks(watchTimeout);
        main.postDelayed(watchTimeout, WATCH_TIMEOUT_MS);
    }

    private String rinkName(int surfaceId) {
        for (Catalog.Venue v : catalog.venues.values()) {
            for (Catalog.Surface s : v.surfaces) if (s.id == surfaceId) return s.name + ", " + v.name;
        }
        return "";
    }

    private void onWatchTimeout() {
        if (!watchPending) return;
        watchPending = false;
        nativePlayer.release();
        ui.showStatus("Couldn't start " + watchTitle,
                "The camera may be off between games. Try again in a minute.", false,
                () -> cancelWatch(true),
                new TvUi.Action("Try again", () -> {
                    if (watchVenue != null && watchSurface != null) {
                        Catalog.Venue v = watchVenue;
                        Catalog.Surface s = watchSurface;
                        cancelWatch(true);
                        main.postDelayed(() -> watch(v, s), 1500);
                    } else {
                        cancelWatch(true);
                    }
                }),
                new TvUi.Action("Back", () -> cancelWatch(true)),
                new TvUi.Action("Open website", () -> {
                    watchingFromUi = false;
                    openWebsite(null);
                }));
    }

    /** Stop trying to start a stream and leave the rink's page (frees the account's stream). */
    private void cancelWatch(boolean leavePage) {
        watchPending = false;
        watchingFromUi = false;
        watchGeneration++; // invalidates any delayed page load from this watch attempt
        main.removeCallbacks(watchTimeout);
        nativePlayer.release();
        ui.hideStatus();
        ui.restoreFocus(); // back on the rink you picked
        if (leavePage) {
            js("__lbtv.answerStreamLimit(false)");
            leaveToHome();
        }
    }

    @Override
    public void openWebsite(String url) {
        ui.rememberFocus(); // so Back from the website returns to the same item
        ui.hideStatus();
        websiteHistoryStart = web.copyBackForwardList().getCurrentIndex();
        setMode(Mode.WEBSITE);
        if (url != null) web.loadUrl(url);
        showToast("Press Back to return to the app");
    }

    private void leaveWebsite() {
        js("__lbtv.media('pause')");
        exitCustomView();
        if (loggedIn) {
            showBrowse();
            leaveToHome(); // leave any video page cleanly; refreshes favourites
        } else {
            setMode(Mode.LOGIN);
        }
    }

    @Override
    public void reloadData() {
        venueRetries = 0;
        catalog.venues.clear();
        catalog.siteFavoritesKnown = false;
        ui.onDataChanged();
        web.loadUrl(HOME_URL);
    }

    @Override
    public boolean isTvFavorite(int venueId) {
        return tvFavorites().contains(venueId);
    }

    @Override
    public void setTvFavorite(int venueId, boolean on) {
        List<Integer> ids = tvFavorites();
        ids.remove(Integer.valueOf(venueId));
        if (on) ids.add(venueId);
        StringBuilder b = new StringBuilder();
        for (int id : ids) b.append(b.length() > 0 ? "," : "").append(id);
        prefs.edit().putString("tvFavorites", b.toString()).apply();
        showToast(on ? "Added to Favorites" : "Removed from Favorites");
    }

    @Override
    public List<Integer> tvFavorites() {
        List<Integer> ids = new ArrayList<>();
        for (String s : prefs.getString("tvFavorites", "").split(",")) {
            try {
                if (!s.isEmpty()) ids.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    // ================================================================ native player hand-off

    /**
     * The page loaded a stream playlist successfully (reported by tv.js with the exact URL and the
     * headers the site sent). Only master playlists start playback; a stale or failed one never does.
     */
    private void onStreamFromPage(String url, String headersJson, boolean master) {
        if (isFinishing() || !master) return;
        Map<String, String> headers = new HashMap<>();
        try {
            JSONObject h = new JSONObject(headersJson);
            java.util.Iterator<String> keys = h.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!k.equalsIgnoreCase("range")) headers.put(k, h.getString(k));
            }
        } catch (Exception ignored) {
        }
        headers.put("Origin", SITE);
        headers.put("Referer", SITE + "/");
        if (watchPending) {
            // The stream we asked for from the TV screens — but only from the new rink page, and only
            // if it's that rink's stream. Right after leaving a rink, the old page's player asks for
            // its (now ended) playlist once more; taking that left the player spinning forever.
            String streamName = Uri.parse(url).getQueryParameter("streamname");
            boolean rightPage = watchPageStarted;
            boolean rightRink = watchUuid.isEmpty() || (streamName != null && streamName.startsWith(watchUuid));
            Log.i(TAG, "stream report host=" + Uri.parse(url).getHost() + " stream=" + streamName
                    + " expected=" + watchUuid + " pageStarted=" + rightPage + " accepted=" + (rightPage && rightRink));
            if (!rightPage || !rightRink) return;
            watchPending = false;
            main.removeCallbacks(watchTimeout);
            streamUrl = url;
            streamHeaders = headers;
            startNative();
            return;
        }
        // Website mode: a new stream started on the page → take it over (1.1 behaviour).
        if (mode != Mode.WEBSITE || nativePlayer.isActive() || url.equals(streamUrl) || url.equals(webOnlyUrl)) return;
        watchingFromUi = false;
        streamUrl = url;
        streamHeaders = headers;
        startNative();
    }

    private void startNative() {
        String url = streamUrl;
        if (url == null || url.equals(webOnlyUrl)) return;
        setCursorMode(false);
        js("__lbtv.suppressWeb(true)");
        nativePlayer.play(url, streamHeaders);
        if (watchingFromUi) ui.hideStatus();
    }

    /** Left/Right in the player: the venue's previous/next rink (live ones first). */
    private void switchRink(int delta) {
        Catalog.Venue v = watchVenue;
        Catalog.Surface cur = watchSurface;
        if (!watchingFromUi || v == null || cur == null || v.surfaces.size() < 2) {
            nativePlayer.showOverlay();
            return;
        }
        List<Catalog.Surface> order = new ArrayList<>(v.surfaces);
        int i = order.indexOf(cur);
        Catalog.Surface next = null;
        // Prefer the next rink that is live; fall back to simply the next one.
        for (int step = 1; step < order.size() && next == null; step++) {
            Catalog.Surface s = order.get(Math.floorMod(i + delta * step, order.size()));
            if (Boolean.TRUE.equals(catalog.isLive(s.id))) next = s;
        }
        if (next == null) next = order.get(Math.floorMod(i + delta, order.size()));
        Catalog.Surface target = next;
        stopWatching(false);
        watch(v, target);
    }

    /** Back from the native player. */
    private void stopWatching(boolean refocus) {
        nativePlayer.release();
        js("__lbtv.suppressWeb(false)");
        if (watchingFromUi) {
            watchingFromUi = false;
            leaveToHome(); // leave the rink's page so the site ends the stream session
            if (refocus) ui.restoreFocus();
        } else {
            web.requestFocus();
        }
    }

    /** Menu / red button in the player: carry on watching in the website's own player. */
    private void switchToWebPlayer() {
        webOnlyUrl = streamUrl;
        nativePlayer.release();
        watchingFromUi = false;
        openWebsite(null);
        // Coming back from the website should land on the rink that was playing.
        if (watchSurface != null) ui.rememberTag("rink-" + watchSurface.id);
        js("__lbtv.playWeb()");
        showToast("Switched to the website player · Back returns to the app");
    }

    /** The live stream finished (e.g. the game/session ended at the rink). */
    private void onStreamEnded() {
        nativePlayer.release();
        js("__lbtv.suppressWeb(false)");
        boolean fromUi = watchingFromUi;
        watchingFromUi = false;
        leaveToHome();
        if (fromUi) {
            ui.showStatus(watchTitle + " has ended", "The live stream from this rink stopped.", false,
                    () -> {
                        ui.hideStatus();
                        ui.restoreFocus();
                    },
                    new TvUi.Action("Back to rinks", () -> {
                        ui.hideStatus();
                        ui.restoreFocus();
                    }));
        }
    }

    private void onNativeFailed(String reason) {
        webOnlyUrl = streamUrl;
        nativePlayer.release();
        if (watchingFromUi) {
            ui.showStatus("This stream won't play here", "Try the website's player instead.\n(" + reason + ")", false,
                    () -> cancelWatch(true),
                    new TvUi.Action("Use website player", () -> {
                        watchingFromUi = false;
                        openWebsite(null);
                        js("__lbtv.playWeb()");
                    }),
                    new TvUi.Action("Back", () -> cancelWatch(true)));
        } else {
            js("__lbtv.playWeb()");
            showToast("Switched to the website player");
        }
    }

    // ================================================================ remote control

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;

        // 1. Native player on screen.
        if (nativePlayer.isActive()) {
            boolean first = down && e.getRepeatCount() == 0;
            if (back) {
                // First Back hides the info overlay; the next one returns to the rink list.
                if (up) {
                    if (nativePlayer.isOverlayShowing() && nativePlayer.isPlaying()) nativePlayer.hideOverlayNow();
                    else stopWatching(true);
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_PROG_RED) {
                if (up) switchToWebPlayer();
                return true;
            }
            if (isMediaKey(code)) {
                if (first) nativePlayer.togglePlayPause(mediaAction(code));
                return true;
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    if (first) nativePlayer.togglePlayPause("toggle");
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (first) switchRink(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (first) nativePlayer.showOverlay();
                    return true;
            }
            return true;
        }

        // 2. Native TV screens: normal Android focus navigation.
        if (mode == Mode.BROWSE || mode == Mode.LOADING) {
            if (back) {
                if (up && !ui.back()) confirmExit();
                return true;
            }
            if (isNavKey(code)) {
                // Safety net: if nothing on the TV screens has focus, the first press just restores it.
                View f = getCurrentFocus();
                if (f == null || !isInside(f, ui.root) || !ui.hasUsableFocus()) {
                    if (down) ui.focus();
                    return true;
                }
            }
            return super.dispatchKeyEvent(e);
        }

        // 3. Website (sign-in or Website tab).
        if (isMediaKey(code)) {
            if (down && e.getRepeatCount() == 0) js("__lbtv.media('" + mediaAction(code) + "')");
            return true;
        }
        if (customView != null) {
            if (back) {
                if (up) exitCustomView();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) onArrow(code, e.getRepeatCount());
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        centerLongPressed = false;
                    } else if (!centerLongPressed && e.isLongPress()) {
                        centerLongPressed = true;
                        setCursorMode(!cursorMode);
                    }
                } else if (up && !centerLongPressed) {
                    onSelect();
                }
                return true;

            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_PROG_RED:
                if (up) setCursorMode(!cursorMode);
                return true;

            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (up) onWebBack();
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean isNavKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
    }

    private static boolean isInside(View v, View parent) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == parent) return true;
        return false;
    }

    private static boolean isMediaKey(int code) {
        return code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_STOP
                || code == KeyEvent.KEYCODE_HEADSETHOOK;
    }

    private static String mediaAction(int code) {
        return code == KeyEvent.KEYCODE_MEDIA_PLAY ? "play"
                : (code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_STOP) ? "pause"
                : "toggle";
    }

    private void confirmExit() {
        long now = SystemClock.uptimeMillis();
        if (now - lastBackPress < 2500) {
            // Make sure no rink page (stream session) is left open, and let the site's own
            // clean-up request go out before the page is destroyed.
            leaveToHome();
            main.postDelayed(this::finish, 400);
        } else {
            lastBackPress = now;
            showToast("Press Back again to exit");
        }
    }

    private void onArrow(int code, int repeat) {
        if (cursorMode) {
            int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
            moveCursor(dx, dy, repeat);
            return;
        }
        String dir = code == KeyEvent.KEYCODE_DPAD_UP ? "up"
                : code == KeyEvent.KEYCODE_DPAD_DOWN ? "down"
                : code == KeyEvent.KEYCODE_DPAD_LEFT ? "left" : "right";
        js("__lbtv.move('" + dir + "')");
    }

    private void onSelect() {
        if (cursorMode) {
            tapAt(cursor.x, cursor.y);
            return;
        }
        web.evaluateJavascript("(function(){try{return window.__lbtv?__lbtv.activate():null}catch(e){return null}})()",
                result -> {
                    try {
                        JSONObject o = new JSONObject(result);
                        if (!o.has("tap")) return;
                        JSONArray p = o.getJSONArray("tap");
                        float f = web.getWidth() / (float) o.getDouble("vw");
                        tapAt((float) p.getDouble(0) * f, (float) p.getDouble(1) * f);
                    } catch (Exception ignored) {
                    }
                });
    }

    private void onWebBack() {
        if (cursorMode) {
            setCursorMode(false);
            return;
        }
        web.evaluateJavascript("(function(){try{return window.__lbtv?__lbtv.back():false}catch(e){return false}})()",
                result -> {
                    if ("true".equals(result)) return;
                    if (mode == Mode.WEBSITE) {
                        if (web.canGoBack() && web.copyBackForwardList().getCurrentIndex() > websiteHistoryStart) {
                            web.goBack();
                        } else {
                            leaveWebsite();
                        }
                        return;
                    }
                    if (web.canGoBack()) web.goBack(); else confirmExit();
                });
    }

    // ================================================================ pointer ("mouse") mode, website only

    private void setCursorMode(boolean on) {
        if (cursorMode == on) return;
        cursorMode = on;
        if (on) {
            if (cursor.x < 0) {
                cursor.x = root.getWidth() / 2f;
                cursor.y = root.getHeight() / 2f;
            }
            cursor.setVisibility(View.VISIBLE);
            cursor.invalidate();
            hoverAt(cursor.x, cursor.y);
            showToast("Pointer mode · Arrows move · OK clicks · Back exits");
        } else {
            cursor.setVisibility(View.GONE);
        }
    }

    private void moveCursor(int dx, int dy, int repeat) {
        float density = getResources().getDisplayMetrics().density;
        float step = (16 + Math.min(repeat, 25) * 4f) * density;
        float w = root.getWidth(), h = root.getHeight();
        float nx = cursor.x + dx * step, ny = cursor.y + dy * step;
        float margin = 2 * density;
        if (ny < margin || ny > h - margin) {
            float scroll = (ny < margin ? -1 : 1) * step * 3;
            js("__lbtv.scrollAt(" + (cursor.x / w) + "," + (cursor.y / h) + "," + (scroll / w) + ")");
        }
        cursor.x = Math.max(margin, Math.min(w - margin, nx));
        cursor.y = Math.max(margin, Math.min(h - margin, ny));
        cursor.invalidate();
        hoverAt(cursor.x, cursor.y);
    }

    private void hoverAt(float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, x, y, 0);
        ev.setSource(InputDevice.SOURCE_MOUSE);
        web.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    private void tapAt(float x, float y) {
        final long t = SystemClock.uptimeMillis();
        MotionEvent downEv = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        downEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(downEv);
        downEv.recycle();
        web.postDelayed(() -> {
            MotionEvent upEv = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
            upEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            web.dispatchTouchEvent(upEv);
            upEv.recycle();
        }, 60);
    }

    private static class CursorView extends View {
        float x = -1, y = -1;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float r;

        CursorView(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            r = 9 * d;
            fill.setColor(Color.argb(200, 255, 255, 255));
            ring.setColor(Color.argb(255, 255, 196, 0));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3 * d);
            setFocusable(false);
            setClickable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (x < 0) return;
            canvas.drawCircle(x, y, r, fill);
            canvas.drawCircle(x, y, r, ring);
        }
    }

    // ================================================================ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
    }

    @Override
    protected void onStop() {
        // Leaving the app: stop playback so the account's single stream is freed up.
        if (nativePlayer.isActive()) stopWatching(true); // returns focus to that rink for when you're back
        if (watchPending) cancelWatch(false);
        js("__lbtv.media('pause')");
        if (web.getUrl() != null && web.getUrl().contains("/video/")) leaveToHome();
        CookieManager.getInstance().flush();
        // Give the site a moment to end the stream session, then stop the hidden page's timers so it
        // doesn't keep polling while the app is in the background.
        stopped = true;
        main.postDelayed(pauseTimersIfStopped, 5000);
        super.onStop();
    }

    private boolean stopped;
    private final Runnable pauseTimersIfStopped = () -> {
        if (stopped && web != null) web.pauseTimers();
    };

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        main.removeCallbacks(pauseTimersIfStopped);
        if (web != null) web.resumeTimers();
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (nativePlayer != null) nativePlayer.release();
        images.shutdown();
        worker.shutdownNow();
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    // ================================================================ helpers

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void showToast(String msg) {
        if (toast != null) toast.cancel();
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        toast.show();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }
}
