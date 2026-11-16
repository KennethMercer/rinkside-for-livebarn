package io.github.kennethmercer.livebarntv;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Native, remote-friendly screens (built in code, no web page involved):
 * Favorites grid, Search, and a venue page listing its rinks, plus centred message cards.
 *
 * Design system: graphite neutrals, a white "lift" for focus (focused item turns white with dark
 * text and grows slightly), amber only for the selected tab, red LIVE pills.
 */
final class TvUi {

    interface Host {
        void watch(Catalog.Venue venue, Catalog.Surface surface);
        void openWebsite(String url);
        void reloadData();
        boolean isTvFavorite(int venueId);
        void setTvFavorite(int venueId, boolean on);
        List<Integer> tvFavorites();
        /** A venue page was opened: a chance to fetch its rinks' live status. */
        void venueOpened(Catalog.Venue venue);
    }

    // ---- design tokens
    static final int BG = 0xFF0E1116;
    static final int SURFACE = 0xFF1A1F27;
    static final int RAISED = 0xFF252B35;
    static final int TEXT = 0xFFF2F4F7;
    static final int TEXT2 = 0xFFA3ACB9;
    static final int TEXT3 = 0xFF6B7480;
    static final int FOCUS_FILL = 0xFFF2F4F7;
    static final int FOCUS_TEXT = 0xFF0E1116;
    static final int FOCUS_TEXT2 = 0xFF4A5568;
    static final int ACCENT = 0xFFFFB547;
    static final int LIVE = 0xFFFF453A;
    static final int CAMERA_ON = 0xFF34C759;
    static final int LOGO_TILE = 0xFFF2F4F7;

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    private static final int MAX_RESULTS = 60;
    private static final int CARD_W = 198;   // dp: 4 × 198 + 3 × 24 = 864 = content width at 960dp
    private static final int GAP = 24;

    private final Activity act;
    private final Host host;
    private final Catalog catalog;
    private final ImageLoader images;
    private final float dp;

    final FrameLayout root;
    private final LinearLayout main;
    private final FrameLayout content;
    private final TextView headerStatus;
    private final LinearLayout tabFavorites, tabSearch;
    private final TextView websiteButton;
    private final ArrayDeque<Screen> stack = new ArrayDeque<>();

    private View statusPanel;
    private Runnable statusOnBack;

    TvUi(Activity act, Host host, Catalog catalog, ImageLoader images) {
        this.act = act;
        this.host = host;
        this.catalog = catalog;
        this.images = images;
        this.dp = act.getResources().getDisplayMetrics().density;

        root = new FrameLayout(act);
        root.setBackgroundColor(BG);
        root.setClickable(true); // swallow stray touches so they never reach the page underneath
        // …but never take the remote's focus: on Android 8+ a clickable view becomes focusable by
        // default, and this invisible full-screen layer was silently grabbing focus at launch.
        root.setFocusable(false);

        main = new LinearLayout(act);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(px(48), px(27), px(48), 0);   // TV overscan-safe margins
        main.setClipChildren(false);
        main.setClipToPadding(false);
        root.addView(main, match());

        // Header: wordmark · tabs ·········· loading status · Website
        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        TextView title = text("RinkSide", 20, TEXT, MEDIUM);
        title.setPadding(0, 0, px(28), 0);
        header.addView(title);
        tabFavorites = tab("Favorites", v -> showFavorites());
        tabSearch = tab("Search", v -> showSearch());
        header.addView(tabFavorites);
        header.addView(tabSearch);
        headerStatus = text("", 15, TEXT3, REGULAR);
        headerStatus.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        headerStatus.setPadding(0, 0, px(16), 0);
        header.addView(headerStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        websiteButton = pillButton("LiveBarn website", v -> host.openWebsite(null));
        header.addView(websiteButton);
        main.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new FrameLayout(act);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.topMargin = px(20);
        main.addView(content, clp);
    }

    // ------------------------------------------------------------------ navigation

    void showFavorites() {
        hideStatus();
        stack.clear();
        push(new FavoritesScreen());
    }

    void showSearch() {
        hideStatus();
        stack.clear();
        push(new SearchScreen());
    }

    void showVenue(Catalog.Venue v) {
        push(new VenueScreen(v));
    }

    private void push(Screen s) {
        Screen cur = stack.peek();
        if (cur != null) {
            View f = root.findFocus();
            cur.lastFocus = f;
            cur.lastFocusTag = f != null ? f.getTag() : null;
        }
        stack.push(s);
        content.removeAllViews();
        s.view = s.build();
        content.addView(s.view, match());
        updateTabs();
        root.post(s::focusDefault);
    }

    /** @return false when there is nothing left to go back to (caller decides about exiting). */
    boolean back() {
        if (statusPanel != null) {
            Runnable r = statusOnBack;
            if (r != null) r.run(); else hideStatus();
            return true;
        }
        if (stack.size() > 1) {
            stack.pop();
            Screen s = stack.peek();
            content.removeAllViews();
            content.addView(s.view, match());
            s.refresh();
            updateTabs();
            // refresh() may have rebuilt the items, so find the previously focused one by its tag.
            View old = s.lastFocus;
            Object tag = s.lastFocusTag;
            root.post(() -> {
                View f = tag != null ? s.view.findViewWithTag(tag) : null;
                if (f == null && old != null && old.isAttachedToWindow()) f = old;
                if (f != null && f.isShown()) f.requestFocus(); else s.focusDefault();
            });
            return true;
        }
        if (!(stack.peek() instanceof FavoritesScreen)) {
            showFavorites();
            return true;
        }
        return false;
    }

    private Object rememberedTag;

    /**
     * Remember the focused item (e.g. the rink you picked) so focus can return to it later.
     * Remembered by tag, not by view: lists get rebuilt (live status refreshes) while you watch.
     */
    void rememberFocus() {
        View f = root.findFocus();
        rememberedTag = f != null ? f.getTag() : null;
    }

    /** Remember a specific item (by its tag, e.g. "rink-123") to focus on the way back. */
    void rememberTag(Object tag) {
        rememberedTag = tag;
    }

    /** Focus the remembered item if it's still on screen, else the screen's default item. */
    void restoreFocus() {
        Object tag = rememberedTag;
        rememberedTag = null;
        root.post(() -> {
            View f = tag != null && statusPanel == null ? content.findViewWithTag(tag) : null;
            if (f != null && f.isShown()) f.requestFocus(); else focus();
        });
    }

    void focus() {
        if (statusPanel != null) {
            View b = statusPanel.findViewWithTag("first");
            if (b != null) b.requestFocus();
            return;
        }
        Screen s = stack.peek();
        if (s == null) showFavorites(); else root.post(s::focusDefault);
    }

    /** Catalog/favourites changed: refresh whatever is on screen. */
    void onDataChanged() {
        Screen s = stack.peek();
        if (s != null) s.refresh();
        headerStatus.setText(catalog.hasVenues() ? "" : "Loading rinks…");
        ensureFocus();
    }

    /** If nothing on these screens has focus (e.g. the focused item was just rebuilt), focus the screen. */
    void ensureFocus() {
        if (!root.isShown()) return;
        if (!hasUsableFocus()) focus();
    }

    /** True when a real item (not a container/background layer) on these screens has focus. */
    boolean hasUsableFocus() {
        View f = root.findFocus();
        if (f == null || !f.isShown()) return false;
        if (f == root || f == main || f == content) return false;
        return statusPanel == null || f != statusPanel || statusPanel.findViewWithTag("first") == null;
    }

    /**
     * Rebuild part of a screen without losing the remote's focus: if focus was inside
     * {@code container}, put it back on the same item (matched by tag) or else on {@code fallback}.
     */
    private void keepFocus(ViewGroup container, Runnable rebuild, Runnable fallback) {
        View focused = root.findFocus();
        boolean had = focused != null && isInside(focused, container);
        Object tag = had ? focused.getTag() : null;
        rebuild.run();
        if (!had) return;
        View again = tag != null ? container.findViewWithTag(tag) : null;
        if (again != null) again.requestFocus(); else fallback.run();
    }

    private void updateTabs() {
        Screen s = stack.peekLast();
        tabFavorites.setSelected(s instanceof FavoritesScreen);
        tabSearch.setSelected(s instanceof SearchScreen);
        tabFavorites.findViewWithTag("bar").setVisibility(s instanceof FavoritesScreen ? View.VISIBLE : View.INVISIBLE);
        tabSearch.findViewWithTag("bar").setVisibility(s instanceof SearchScreen ? View.VISIBLE : View.INVISIBLE);
    }

    // ------------------------------------------------------------------ message card

    static final class Action {
        final String label;
        final Runnable run;

        Action(String label, Runnable run) {
            this.label = label;
            this.run = run;
        }
    }

    /**
     * Centred message card over a dark scrim: title, optional body, optional spinner, and stacked
     * buttons (first = primary, gets focus). Back runs {@code onBack} (or just closes).
     */
    void showStatus(String title, String body, boolean spinner, Runnable onBack, Action... actions) {
        hideStatus();
        FrameLayout scrim = new FrameLayout(act);
        scrim.setBackgroundColor(0xE00E1116);
        scrim.setClickable(true);
        scrim.setFocusable(false);

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(40), px(36), px(40), px(36));
        card.setBackground(round(SURFACE, 24));
        card.setClipChildren(false);
        if (spinner) {
            ProgressBar pb = new ProgressBar(act);
            pb.setIndeterminateTintList(ColorStateList.valueOf(TEXT));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(px(40), px(40));
            plp.bottomMargin = px(20);
            card.addView(pb, plp);
        }
        card.addView(text(title, 26, TEXT, BOLD));
        if (body != null && !body.isEmpty()) {
            TextView b = text(body, 18, TEXT2, REGULAR);
            b.setPadding(0, px(10), 0, 0);
            b.setLineSpacing(0, 1.15f);
            card.addView(b);
        }
        for (int i = 0; i < actions.length; i++) {
            Action a = actions[i];
            TextView btn = blockButton(a.label, v -> a.run.run(), i == 0);
            if (i == 0) btn.setTag("first");
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(56));
            blp.topMargin = px(i == 0 ? 28 : 12);
            card.addView(btn, blp);
        }
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(px(640), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(card, cardLp);

        main.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.addView(scrim, match());
        statusPanel = scrim;
        statusOnBack = onBack;

        scrim.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        scrim.animate().alpha(1f).setDuration(200).start();
        card.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new DecelerateInterpolator(2f)).start();

        View first = scrim.findViewWithTag("first");
        if (first != null) root.post(first::requestFocus);
    }

    void hideStatus() {
        if (statusPanel == null) return;
        root.removeView(statusPanel);
        statusPanel = null;
        statusOnBack = null;
        main.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        focus();
    }

    boolean isStatusShowing() {
        return statusPanel != null;
    }

    // ------------------------------------------------------------------ screens

    private abstract class Screen {
        View view;
        View lastFocus;
        Object lastFocusTag;

        abstract View build();

        void refresh() {
        }

        abstract void focusDefault();
    }

    /** LiveBarn favorites (from your account) plus favorites saved on this TV. */
    private final class FavoritesScreen extends Screen {
        private LinearLayout body;

        @Override
        View build() {
            ScrollView sv = scroller();
            body = column();
            sv.addView(body);
            fill();
            return sv;
        }

        /** Focus was put on the tab only because there was nothing to focus yet (loading). */
        private boolean parkedOnTab;

        private void fill() {
            keepFocus(body, this::fillNow, this::focusDefault);
            // Cards just arrived while focus was parked on the tab: move it to the first card.
            if (parkedOnTab && tabFavorites.isFocused() && firstFocusable(body) != null) focusDefault();
        }

        private void fillNow() {
            body.removeAllViews();
            List<Catalog.Venue> list = new ArrayList<>();
            for (int id : catalog.siteFavorites) {
                Catalog.Venue v = catalog.venues.get(id);
                if (v != null) list.add(v);
            }
            for (int id : host.tvFavorites()) {
                Catalog.Venue v = catalog.venues.get(id);
                if (v != null && !list.contains(v)) list.add(v);
            }

            if (list.isEmpty()) {
                boolean loading = !catalog.hasVenues() || !catalog.siteFavoritesKnown;
                body.addView(emptyState(
                        loading ? (catalog.hasVenues() ? "Loading your favorites…" : "Loading rinks…") : "No favorites yet",
                        loading ? null : "Find a rink in Search and add it here.",
                        loading ? new Action[0] : new Action[]{
                                new Action("Search rinks", TvUi.this::showSearch),
                                new Action("Reload", host::reloadData)}));
                return;
            }

            GridLayout grid = new GridLayout(act);
            grid.setColumnCount(4);
            grid.setUseDefaultMargins(false);
            grid.setClipChildren(false);
            grid.setClipToPadding(false);
            for (int i = 0; i < list.size(); i++) {
                View card = venueCard(list.get(i));
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = px(CARD_W);
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.setMargins(0, 0, (i % 4 == 3) ? 0 : px(GAP), px(GAP));
                grid.addView(card, lp);
            }
            body.addView(grid);
            TextView foot = text("RinkSide is an unofficial app and isn't affiliated with LiveBarn.", 14, TEXT3, REGULAR);
            foot.setPadding(0, px(8), 0, 0);
            body.addView(foot);
        }

        @Override
        void refresh() {
            if (body != null) fill();
        }

        @Override
        void focusDefault() {
            View first = firstFocusable(body);
            parkedOnTab = first == null;
            if (first != null) first.requestFocus(); else tabFavorites.requestFocus();
        }
    }

    private final class SearchScreen extends Screen {
        private EditText box;
        private LinearLayout results;

        @Override
        View build() {
            LinearLayout col = column();

            box = new EditText(act);
            box.setTag("search-box");
            box.setSingleLine(true);
            box.setHint("Rink name or city");
            box.setTextSize(20);
            box.setTypeface(REGULAR);
            box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            box.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{FOCUS_TEXT, TEXT}));
            box.setHintTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{FOCUS_TEXT2, TEXT3}));
            box.setBackground(focusBg(SURFACE, 14));
            box.setPadding(px(24), 0, px(24), 0);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) { fill(); }
            });
            box.setOnEditorActionListener((v, actionId, event) -> {
                hideKeyboard(v);
                View first = firstFocusable(results);
                if (first != null) first.requestFocus();
                return true;
            });
            col.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(64)));

            ScrollView sv = scroller();
            results = column();
            sv.addView(results);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            lp.topMargin = px(16);
            col.addView(sv, lp);
            fill();
            return col;
        }

        private void fill() {
            results.removeAllViews();
            String q = box.getText().toString();
            if (!catalog.hasVenues()) {
                results.addView(note("Loading rinks…"));
                return;
            }
            if (q.trim().length() < 2) {
                results.addView(note(String.format(Locale.US, "Search %,d rinks by name or city", catalog.venues.size())));
                return;
            }
            List<Catalog.Venue> found = catalog.search(q, MAX_RESULTS + 1);
            if (found.isEmpty()) {
                results.addView(note("No rinks found for “" + q.trim() + "”"));
                return;
            }
            boolean more = found.size() > MAX_RESULTS;
            for (int i = 0; i < Math.min(found.size(), MAX_RESULTS); i++) results.addView(venueRow(found.get(i)));
            if (more) results.addView(note("Showing the first " + MAX_RESULTS + " — type more to narrow it down"));
        }

        @Override
        void refresh() {
            if (results != null) keepFocus(results, this::fill, this::focusDefault);
        }

        @Override
        void focusDefault() {
            box.requestFocus();
        }
    }

    /** Two columns: venue identity + actions on the left, its rinks on the right. */
    private final class VenueScreen extends Screen {
        private final Catalog.Venue venue;
        private LinearLayout rinks;
        private LinearLayout actions;

        VenueScreen(Catalog.Venue venue) {
            this.venue = venue;
        }

        @Override
        View build() {
            LinearLayout cols = new LinearLayout(act);
            cols.setOrientation(LinearLayout.HORIZONTAL);
            cols.setClipChildren(false);

            // Left: identity + actions
            LinearLayout left = column();
            left.addView(logoTile(venue, 300, 168, 28));
            TextView name = text(venue.name, 30, TEXT, BOLD);
            name.setPadding(0, px(20), 0, 0);
            name.setMaxLines(3);
            name.setEllipsize(TextUtils.TruncateAt.END);
            left.addView(name);
            TextView place = text(venue.place(), 16, TEXT2, REGULAR);
            place.setPadding(0, px(4), 0, 0);
            left.addView(place);
            actions = column();
            actions.setPadding(0, px(24), 0, 0);
            left.addView(actions);
            fillActions();
            cols.addView(left, new LinearLayout.LayoutParams(px(300), ViewGroup.LayoutParams.MATCH_PARENT));

            // Right: rinks
            ScrollView sv = scroller();
            LinearLayout right = column();
            right.setPadding(px(32), 0, 0, 0);
            right.addView(text("Rinks", 15, TEXT3, MEDIUM));
            rinks = column();
            rinks.setPadding(0, px(8), 0, 0);
            right.addView(rinks);
            sv.addView(right);
            cols.addView(sv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            fillRinks();
            host.venueOpened(venue);
            return cols;
        }

        private void fillActions() {
            actions.removeAllViews();
            boolean site = catalog.siteFavorites.contains(venue.id);
            boolean tv = host.isTvFavorite(venue.id);
            if (site && !tv) {
                TextView cap = text("★  In your LiveBarn favorites", 15, TEXT2, REGULAR);
                cap.setPadding(px(4), 0, 0, px(12));
                actions.addView(cap);
            } else {
                TextView fav = blockButton(tv ? "Remove from Favorites" : "Add to Favorites", v -> {
                    host.setTvFavorite(venue.id, !host.isTvFavorite(venue.id));
                    fillActions();
                    View again = actions.findViewWithTag("fav");
                    if (again != null) again.requestFocus();
                }, false);
                fav.setTag("fav");
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52));
                lp.bottomMargin = px(12);
                actions.addView(fav, lp);
            }
            TextView web = blockButton("Open LiveBarn website", v -> host.openWebsite(null), false);
            web.setTag("web");
            actions.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52)));
        }

        private void fillRinks() {
            rinks.removeAllViews();
            if (venue.surfaces.isEmpty()) {
                rinks.addView(note("No rinks listed for this venue."));
                return;
            }
            for (Catalog.Surface s : venue.surfaces) rinks.addView(rinkRow(venue, s));
        }

        @Override
        void refresh() {
            if (rinks != null) {
                keepFocus(rinks, this::fillRinks, this::focusDefault);
                keepFocus(actions, this::fillActions, this::focusDefault);
            }
        }

        @Override
        void focusDefault() {
            View first = firstFocusable(rinks);
            if (first != null) first.requestFocus(); else {
                View a = firstFocusable(actions);
                if (a != null) a.requestFocus();
            }
        }
    }

    // ------------------------------------------------------------------ building blocks

    private View venueCard(Catalog.Venue v) {
        LinearLayout card = column();
        card.setPadding(px(12), px(12), px(12), px(16));
        card.setBackground(focusBg(SURFACE, 16));
        makeFocusable(card, x -> showVenue(v), 1.06f);
        card.setTag("venue-" + v.id);

        card.addView(logoTile(v, CARD_W - 24, 100, 12));
        TextView name = text(v.name, 18, TEXT, MEDIUM);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(px(4), px(12), px(4), 0);
        inheritFocus(name, TEXT, FOCUS_TEXT);
        card.addView(name);
        TextView place = text(v.place(), 15, TEXT2, REGULAR);
        place.setSingleLine(true);
        place.setEllipsize(TextUtils.TruncateAt.END);
        place.setPadding(px(4), px(2), px(4), 0);
        inheritFocus(place, TEXT2, FOCUS_TEXT2);
        card.addView(place);

        View status = venueStatus(v);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(px(4), px(10), 0, 0);
        card.addView(status, slp);
        return card;
    }

    /** LIVE pill when any rink is streaming, else "N rinks". */
    private View venueStatus(Catalog.Venue v) {
        int live = 0;
        for (Catalog.Surface s : v.surfaces) if (Boolean.TRUE.equals(catalog.isLive(s.id))) live++;
        if (live > 0) return livePill(live > 1 ? "LIVE · " + live : "LIVE");
        int n = v.surfaces.size();
        TextView t = text(n + (n == 1 ? " rink" : " rinks"), 15, TEXT3, REGULAR);
        inheritFocus(t, TEXT3, FOCUS_TEXT2);
        return t;
    }

    private View venueRow(Catalog.Venue v) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(80));
        row.setPadding(px(12), px(12), px(20), px(12));
        row.setBackground(focusBg(SURFACE, 14));
        makeFocusable(row, x -> showVenue(v), 1.02f);
        row.setTag("venue-" + v.id);
        row.addView(logoTile(v, 56, 56, 10));
        LinearLayout names = column();
        names.setPadding(px(16), 0, px(12), 0);
        TextView n = text(v.name, 20, TEXT, MEDIUM);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        inheritFocus(n, TEXT, FOCUS_TEXT);
        names.addView(n);
        TextView p = text(v.place() + (v.country.isEmpty() ? "" : " · " + v.country), 15, TEXT2, REGULAR);
        p.setSingleLine(true);
        p.setEllipsize(TextUtils.TruncateAt.END);
        inheritFocus(p, TEXT2, FOCUS_TEXT2);
        names.addView(p);
        row.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(venueStatus(v));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(8);
        row.setLayoutParams(lp);
        return row;
    }

    private View rinkRow(Catalog.Venue v, Catalog.Surface s) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(72));
        row.setPadding(px(24), 0, px(24), 0);
        row.setBackground(focusBg(SURFACE, 14));
        makeFocusable(row, x -> host.watch(v, s), 1.02f);
        row.setTag("rink-" + s.id);

        TextView name = text(s.name, 22, TEXT, MEDIUM);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        inheritFocus(name, TEXT, FOCUS_TEXT);
        row.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View status;
        boolean cameraUp = s.online && !s.closed && !s.comingSoon;
        Boolean liveNow = catalog.isLive(s.id);
        if (cameraUp && Boolean.TRUE.equals(liveNow)) {
            status = livePill("LIVE");
        } else if (cameraUp && liveNow == null) {
            status = dotLabel("Camera on", CAMERA_ON);
        } else {
            TextView t = text(cameraUp ? "Not live" : s.statusLabel(), 15, TEXT3, REGULAR);
            inheritFocus(t, TEXT3, FOCUS_TEXT2);
            status = t;
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = px(16);
        row.addView(status, slp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(10);
        row.setLayoutParams(lp);
        return row;
    }

    /** White tile so venue logos (often navy) stay readable; initials when there's no logo. */
    private View logoTile(Catalog.Venue v, int wDp, int hDp, int radiusDp) {
        FrameLayout tile = new FrameLayout(act);
        tile.setBackground(round(LOGO_TILE, radiusDp));
        int pad = px(Math.max(6, Math.min(16, hDp / 7)));
        tile.setPadding(pad, pad, pad, pad);
        if (v.logo != null) {
            ImageView logo = new ImageView(act);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            images.load(v.logo, logo);
            tile.addView(logo, match());
        } else {
            TextView initials = text(initials(v.name), Math.max(14, hDp / 4), FOCUS_TEXT2, BOLD);
            initials.setGravity(Gravity.CENTER);
            tile.addView(initials, match());
        }
        tile.setLayoutParams(new LinearLayout.LayoutParams(px(wDp), px(hDp)));
        return tile;
    }

    private static String initials(String name) {
        StringBuilder b = new StringBuilder();
        for (String w : name.split("\\s+")) {
            if (!w.isEmpty() && Character.isLetterOrDigit(w.charAt(0))) b.append(Character.toUpperCase(w.charAt(0)));
            if (b.length() == 2) break;
        }
        return b.toString();
    }

    private TextView livePill(String label) {
        TextView t = text(label, 13, Color.WHITE, BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(px(10), px(4), px(10), px(4));
        t.setBackground(round(LIVE, 6));
        return t;
    }

    private View dotLabel(String label, int dotColor) {
        LinearLayout l = new LinearLayout(act);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setDuplicateParentStateEnabled(true);
        View dot = new View(act);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(dotColor);
        dot.setBackground(d);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(8), px(8));
        dlp.rightMargin = px(8);
        l.addView(dot, dlp);
        TextView t = text(label, 15, TEXT2, REGULAR);
        inheritFocus(t, TEXT2, FOCUS_TEXT2);
        l.addView(t);
        return l;
    }

    private View emptyState(String title, String body, Action[] actions) {
        LinearLayout c = column();
        c.setPadding(px(4), px(40), 0, 0);
        c.addView(text(title, 26, TEXT, BOLD));
        if (body != null) {
            TextView b = text(body, 18, TEXT2, REGULAR);
            b.setPadding(0, px(8), 0, 0);
            c.addView(b);
        }
        LinearLayout row = new LinearLayout(act);
        row.setPadding(0, px(24), 0, 0);
        row.setClipChildren(false);
        for (Action a : actions) {
            TextView btn = blockButton(a.label, v -> a.run.run(), false);
            btn.setPadding(px(28), 0, px(28), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(52));
            lp.rightMargin = px(12);
            row.addView(btn, lp);
        }
        c.addView(row);
        return c;
    }

    private TextView note(String s) {
        TextView t = text(s, 18, TEXT2, REGULAR);
        t.setPadding(px(4), px(16), px(4), px(16));
        return t;
    }

    /** Header tab: label + amber "selected" bar; focus = white pill. */
    private LinearLayout tab(String label, View.OnClickListener click) {
        LinearLayout t = column();
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(px(18), px(8), px(18), px(6));
        t.setBackground(focusBg(Color.TRANSPARENT, 999));
        makeFocusable(t, click, 1.06f);
        TextView tv = text(label, 18, TEXT2, MEDIUM);
        tv.setDuplicateParentStateEnabled(true);
        tv.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{FOCUS_TEXT, TEXT, TEXT2}));
        t.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View bar = new View(act);
        bar.setTag("bar");
        bar.setBackground(round(ACCENT, 2));
        bar.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(24), px(3));
        blp.topMargin = px(6);
        t.addView(bar, blp);
        // Down from a tab always lands on the screen's first item.
        t.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                Screen s = stack.peek();
                if (s != null) s.focusDefault();
            }
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(4);
        t.setLayoutParams(lp);
        return t;
    }

    /** Small rounded header button. */
    private TextView pillButton(String label, View.OnClickListener click) {
        TextView b = text(label, 15, TEXT2, MEDIUM);
        b.setPadding(px(18), px(8), px(18), px(8));
        b.setBackground(focusBg(RAISED, 999));
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{FOCUS_TEXT, TEXT2}));
        makeFocusable(b, click, 1.06f);
        b.setOnKeyListener((v, keyCode, event) -> {
            // Rightmost header item: Right goes nowhere (instead of jumping down into the content).
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                Screen s = stack.peek();
                if (s != null) s.focusDefault();
            }
            return true;
        });
        return b;
    }

    /** Full-width button used in cards and on the venue page. */
    private TextView blockButton(String label, View.OnClickListener click, boolean primary) {
        TextView b = text(label, 18, TEXT, MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setBackground(focusBg(primary ? RAISED : SURFACE, 12));
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{FOCUS_TEXT, TEXT}));
        makeFocusable(b, click, 1.04f);
        return b;
    }

    /** Focus = white lift: grow, rise, and (via state lists) invert text. */
    private void makeFocusable(View v, View.OnClickListener click, float focusScale) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) -> {
            float s = has ? focusScale : 1f;
            view.animate().scaleX(s).scaleY(s).translationZ(has ? px(12) : 0)
                    .setDuration(has ? 150 : 100)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        });
    }

    /** Child text that switches colour with its focusable parent. */
    private static void inheritFocus(TextView t, int normal, int focused) {
        t.setDuplicateParentStateEnabled(true);
        t.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{focused, normal}));
    }

    private Drawable focusBg(int normal, int radiusDp) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, round(FOCUS_FILL, radiusDp));
        sl.addState(new int[]{android.R.attr.state_pressed}, round(FOCUS_FILL, radiusDp));
        sl.addState(new int[]{}, normal == Color.TRANSPARENT ? new ColorDrawable(Color.TRANSPARENT) : round(normal, radiusDp));
        return sl;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(radiusDp));
        return g;
    }

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    private ScrollView scroller() {
        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setClipChildren(false);
        // Room for the focus lift at the edges, and a soft fade at the bottom instead of a hard cut.
        sv.setPadding(px(8), px(8), px(8), px(27));
        sv.setVerticalScrollBarEnabled(false);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(px(48));
        // A ScrollView is focusable by default, which made focus "vanish" into it (e.g. pressing
        // down from a header button). Only its items should ever take focus.
        sv.setFocusable(false);
        sv.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        return sv;
    }

    private static View firstFocusable(ViewGroup g) {
        if (g == null) return null;
        ArrayList<View> list = g.getFocusables(View.FOCUS_DOWN);
        return list.isEmpty() ? null : list.get(0);
    }

    private static boolean isInside(View v, View parent) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == parent) return true;
        return false;
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int px(float dpValue) {
        return Math.round(dpValue * dp);
    }
}
