package io.github.kennethmercer.livebarntv;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.io.IOException;
import java.util.Map;

/**
 * Plays a stream URL the LiveBarn page loaded, with Android's own player (Media3/ExoPlayer):
 * hardware decoding, live-edge handling, and a TV-style overlay instead of generic controls.
 *
 * Stage (full screen, on top of everything): video · "Connecting…" card until the first frame ·
 * info overlay (rink, venue, LIVE / Paused, remote hints) shown for a few seconds on start and on
 * any key.
 *
 * The web page stays loaded underneath (paused) so it keeps the stream session alive exactly as it
 * would in a browser; this class only plays the video the page already requested.
 */
@OptIn(markerClass = UnstableApi.class)
final class NativePlayer {

    interface Listener {
        /** Playback failed in a way retrying won't fix; caller should fall back / tell the user. */
        void onNativeFailed(String reason);

        /** The live stream finished. */
        void onStreamEnded();
    }

    /** Network hiccups are retried this many times (2 s apart) before giving up. */
    private static final int MAX_RETRIES = 3;
    private int retries;

    private static final String TAG = "LBTV";
    private static final long START_WATCHDOG_MS = 25_000;
    private static final long OVERLAY_MS = 4_000;
    /** After a pause longer than this, resuming jumps back to live instead of playing old video. */
    private static final long BACK_TO_LIVE_AFTER_MS = 30_000;

    private final Activity activity;
    private final FrameLayout container;
    private final String userAgent;
    private final Listener listener;
    private final float dp;

    private ExoPlayer player;
    private FrameLayout stage;
    private PlayerView view;
    private View connecting;
    private View overlay;
    private TextView stateLabel;
    private boolean firstFrame;
    private long pausedAt;
    private String title = "", subtitle = "";
    private boolean canSwitch;

    private final Runnable watchdog = this::onWatchdog;
    private final Runnable hideOverlay = this::hideOverlayNow;

    NativePlayer(Activity activity, FrameLayout container, String userAgent, Listener listener) {
        this.activity = activity;
        this.container = container;
        this.userAgent = userAgent;
        this.listener = listener;
        this.dp = activity.getResources().getDisplayMetrics().density;
    }

    boolean isActive() {
        return player != null;
    }

    /** Rink + venue shown on the connecting card and the info overlay. */
    void showConnecting(String rink, String venue) {
        title = rink == null ? "" : rink;
        subtitle = venue == null ? "" : venue;
    }

    /** Whether Left/Right can switch rinks (shown as a hint). */
    void setCanSwitch(boolean canSwitch) {
        this.canSwitch = canSwitch;
    }

    void play(String url, Map<String, String> headers) {
        release();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000);

        // Pick the best rendition the TV can actually decode/display (panoramic feeds can exceed 4K).
        DefaultTrackSelector tracks = new DefaultTrackSelector(activity);
        tracks.setParameters(tracks.buildUponParameters().setViewportSizeToPhysicalDisplaySize(activity, true));

        player = new ExoPlayer.Builder(activity).setTrackSelector(tracks).build();
        firstFrame = false;
        pausedAt = 0;
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onLoadError(EventTime eventTime, LoadEventInfo info, MediaLoadData data,
                                    IOException error, boolean wasCanceled) {
                Log.w(TAG, "load error " + info.uri.getHost() + " : " + error);
            }
        });
        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                if (firstFrame) return;
                firstFrame = true;
                container.removeCallbacks(watchdog);
                if (connecting != null) {
                    View c = connecting;
                    c.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                        if (stage != null) stage.removeView(c);
                    }).start();
                    connecting = null;
                }
                showOverlay();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updateStateLabel();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (player == null) return;
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Fell too far behind live (e.g. after a long pause): jump back to live.
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                boolean network = error.errorCode >= PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                        && error.errorCode < 3000; // 2xxx = I/O errors
                if (network && firstFrame && retries < MAX_RETRIES) {
                    // Brief network drop mid-stream: try again quietly before giving up.
                    retries++;
                    Log.w(TAG, "retrying after " + error.getErrorCodeName() + " (" + retries + ")");
                    container.postDelayed(() -> {
                        if (player != null) {
                            player.seekToDefaultPosition();
                            player.prepare();
                        }
                    }, 2000);
                    return;
                }
                listener.onNativeFailed(error.getErrorCodeName());
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) retries = 0;
                if (state == Player.STATE_ENDED && firstFrame) listener.onStreamEnded();
            }
        });

        buildStage();
        view.setPlayer(player);

        player.setMediaSource(new HlsMediaSource.Factory(http)
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(url)));
        player.setPlayWhenReady(true);
        player.prepare();
        container.removeCallbacks(watchdog);
        container.postDelayed(watchdog, START_WATCHDOG_MS);
    }

    // ------------------------------------------------------------------ remote

    /** OK / Play-Pause: toggle, and show the overlay so the state is visible. */
    void togglePlayPause(String action) {
        if (player == null) return;
        boolean play = "play".equals(action) || ("toggle".equals(action) && !player.getPlayWhenReady());
        if (play) {
            boolean stale = pausedAt > 0 && SystemClock.uptimeMillis() - pausedAt > BACK_TO_LIVE_AFTER_MS;
            if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
            if (stale && player.isCurrentMediaItemLive()) player.seekToDefaultPosition();
            pausedAt = 0;
            player.play();
            showOverlay(stale ? "Back to live" : null);
        } else {
            pausedAt = SystemClock.uptimeMillis();
            player.pause();
            showOverlay();
        }
    }

    boolean isPlaying() {
        return player != null && player.getPlayWhenReady();
    }

    boolean isOverlayShowing() {
        return overlay != null && overlay.getVisibility() == View.VISIBLE && overlay.getAlpha() > 0.5f;
    }

    void showOverlay() {
        showOverlay(null);
    }

    void hideOverlayNow() {
        if (overlay == null) return;
        // Stay visible while paused.
        if (player != null && !player.getPlayWhenReady()) return;
        overlay.animate().alpha(0f).setDuration(400).start();
    }

    // ------------------------------------------------------------------ UI

    private void buildStage() {
        stage = new FrameLayout(activity);
        stage.setBackgroundColor(Color.BLACK);
        stage.setClickable(true);
        stage.setFocusable(false);

        view = new PlayerView(activity);
        view.setUseController(false);          // remote handled by the app (OK = pause, etc.)
        view.setShutterBackgroundColor(Color.BLACK);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        view.setKeepScreenOn(true);
        view.setFocusable(false);
        stage.addView(view, match());

        // Mid-stream buffering: small white spinner (only after the first frame).
        ProgressBar buffering = new ProgressBar(activity);
        buffering.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
        buffering.setVisibility(View.GONE);
        stage.addView(buffering, new FrameLayout.LayoutParams(px(40), px(40), Gravity.CENTER));
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                buffering.setVisibility(firstFrame && state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            }
        });

        connecting = connectingCard();
        stage.addView(connecting, match());

        overlay = infoOverlay();
        overlay.setAlpha(0f);
        stage.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        container.addView(stage, match());
    }

    private View connectingCard() {
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(TvUi.BG);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(40), px(36), px(40), px(36));
        card.setBackground(round(TvUi.SURFACE, 24));
        ProgressBar pb = new ProgressBar(activity);
        pb.setIndeterminateTintList(ColorStateList.valueOf(TvUi.TEXT));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(px(40), px(40));
        plp.bottomMargin = px(20);
        card.addView(pb, plp);
        card.addView(text(title, 26, TvUi.TEXT, Typeface.create("sans-serif", Typeface.BOLD)));
        TextView sub = text((subtitle.isEmpty() ? "" : subtitle + " · ") + "Starting video…", 18, TvUi.TEXT2,
                Typeface.create("sans-serif", Typeface.NORMAL));
        sub.setPadding(0, px(10), 0, 0);
        card.addView(sub);
        scrim.addView(card, new FrameLayout.LayoutParams(px(640), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return scrim;
    }

    private View infoOverlay() {
        LinearLayout o = new LinearLayout(activity);
        o.setOrientation(LinearLayout.VERTICAL);
        o.setPadding(px(48), px(27), px(48), px(110));
        // Strong enough to read white text over bright ice.
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE6000000, 0xB3000000, 0x00000000});
        o.setBackground(g);

        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(title, 24, Color.WHITE, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(name);
        stateLabel = text("LIVE", 13, Color.WHITE, Typeface.create("sans-serif", Typeface.BOLD));
        stateLabel.setLetterSpacing(0.08f);
        stateLabel.setPadding(px(10), px(4), px(10), px(4));
        stateLabel.setBackground(round(TvUi.LIVE, 6));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = px(14);
        row.addView(stateLabel, slp);
        o.addView(row);

        TextView venue = text(subtitle, 16, TvUi.TEXT2, Typeface.create("sans-serif", Typeface.NORMAL));
        venue.setPadding(0, px(4), 0, 0);
        o.addView(venue);

        String hints = "OK pause" + (canSwitch ? "  ·  ◀ ▶ other rinks" : "") + "  ·  Back to rinks";
        TextView hint = text(hints, 14, 0xB3FFFFFF, Typeface.create("sans-serif", Typeface.NORMAL));
        hint.setTag("hint");
        hint.setPadding(0, px(10), 0, 0);
        o.addView(hint);
        return o;
    }

    private void showOverlay(String message) {
        if (overlay == null) return;
        updateStateLabel();
        TextView hint = overlay.findViewWithTag("hint");
        if (hint != null && message != null) hint.setText(message);
        overlay.animate().cancel();
        overlay.animate().alpha(1f).setDuration(200).start();
        container.removeCallbacks(hideOverlay);
        container.postDelayed(hideOverlay, OVERLAY_MS);
        if (message != null && hint != null) {
            String hints = "OK pause" + (canSwitch ? "  ·  ◀ ▶ other rinks" : "") + "  ·  Back to rinks";
            container.postDelayed(() -> hint.setText(hints), OVERLAY_MS);
        }
    }

    private void updateStateLabel() {
        if (stateLabel == null || player == null) return;
        boolean paused = !player.getPlayWhenReady();
        stateLabel.setText(paused ? "PAUSED" : "LIVE");
        stateLabel.setBackground(round(paused ? TvUi.RAISED : TvUi.LIVE, 6));
    }

    private void onWatchdog() {
        if (player != null && !firstFrame) listener.onNativeFailed("no video after 25 s");
    }

    void release() {
        container.removeCallbacks(watchdog);
        container.removeCallbacks(hideOverlay);
        if (player != null) {
            player.release();
            player = null;
        }
        if (view != null) {
            view.setPlayer(null);
            view = null;
        }
        if (stage != null) {
            container.removeView(stage);
            stage = null;
        }
        connecting = null;
        overlay = null;
        stateLabel = null;
    }

    // ------------------------------------------------------------------ helpers

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(px(4), 0, px(1), 0x99000000);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(radiusDp));
        return g;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
