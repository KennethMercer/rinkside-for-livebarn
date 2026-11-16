package io.github.kennethmercer.livebarntv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Venues/rinks and favourites, read from what the LiveBarn page itself already has:
 * its local venue cache (IndexedDB "LiveBarnDB/venues") and the responses it receives.
 */
final class Catalog {

    static final class Surface {
        int id;
        /** The site's surface UUID; stream names are this UUID + the camera-mode number. */
        String uuid = "";
        String name;
        boolean online;
        boolean comingSoon;
        boolean closed;
        String status = "";
        int order;

        String statusLabel() {
            if (closed) return "Closed";
            if (comingSoon) return "Coming soon";
            return online ? "Online" : "Offline";
        }
    }

    static final class Venue {
        int id;
        String name = "";
        String city = "";
        String region = "";
        String country = "";
        String logo;
        final List<Surface> surfaces = new ArrayList<>();
        String key = "";

        String place() {
            StringBuilder b = new StringBuilder(city);
            if (!region.isEmpty()) b.append(b.length() > 0 ? ", " : "").append(region);
            return b.toString();
        }

        int onlineCount() {
            int n = 0;
            for (Surface s : surfaces) if (s.online && !s.closed && !s.comingSoon) n++;
            return n;
        }
    }

    final Map<Integer, Venue> venues = new LinkedHashMap<>();
    /** surfaceId → is any camera mode actually streaming now (from the site's feed-mode status). */
    final Map<Integer, Boolean> liveNow = new java.util.HashMap<>();

    /** @return TRUE/FALSE when the site has told us, null when unknown. */
    Boolean isLive(int surfaceId) {
        return liveNow.get(surfaceId);
    }

    /** Body of /surfaceactivefeedmode/... or the site's cached "venuefeedmodes". */
    boolean updateFeedModes(String json) {
        boolean changed = false;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !o.has("surfaceId")) continue;
                JSONObject st = o.optJSONObject("activeFeedModeStatus");
                boolean live = false;
                if (st != null) {
                    java.util.Iterator<String> keys = st.keys();
                    while (keys.hasNext()) if ("active".equalsIgnoreCase(st.optString(keys.next()))) live = true;
                }
                Boolean old = liveNow.put(o.getInt("surfaceId"), live);
                if (old == null || old != live) changed = true;
            }
        } catch (Exception ignored) {
        }
        return changed;
    }
    final LinkedHashSet<Integer> siteFavorites = new LinkedHashSet<>();
    boolean siteFavoritesKnown;
    private List<String> pendingFavoriteNames;

    boolean hasVenues() {
        return !venues.isEmpty();
    }

    /** Compact venue list produced by tv.js loadVenues(). */
    void loadVenues(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        Map<Integer, Venue> fresh = new LinkedHashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Venue v = new Venue();
            v.id = o.getInt("i");
            v.name = o.optString("n", "");
            v.city = o.optString("c", "");
            v.region = o.optString("p", "");
            v.country = o.optString("k", "");
            v.logo = o.optString("l", null);
            if (v.logo != null && v.logo.isEmpty()) v.logo = null;
            JSONArray ss = o.optJSONArray("s");
            if (ss != null) {
                for (int j = 0; j < ss.length(); j++) {
                    JSONObject so = ss.getJSONObject(j);
                    Surface s = new Surface();
                    s.id = so.getInt("i");
                    s.uuid = so.optString("u", "");
                    s.name = so.optString("n", "Rink");
                    s.online = so.optBoolean("o", false);
                    s.comingSoon = so.optBoolean("cs", false);
                    s.closed = so.optBoolean("cl", false);
                    s.status = so.optString("st", "");
                    s.order = so.optInt("x", j);
                    v.surfaces.add(s);
                }
            }
            Collections.sort(v.surfaces, (a, b) -> Integer.compare(a.order, b.order));
            v.key = (v.name + " " + v.city + " " + v.region).toLowerCase(Locale.ROOT);
            fresh.put(v.id, v);
        }
        venues.clear();
        venues.putAll(fresh);
        if (pendingFavoriteNames != null) setSiteFavoriteNames(pendingFavoriteNames);
    }

    /** Body of the site's GET /userfavorite (or POST body): venue ids, or objects holding them. */
    void setSiteFavorites(String json) throws Exception {
        Object root = new org.json.JSONTokener(json.trim()).nextValue();
        JSONArray arr = null;
        if (root instanceof JSONArray) arr = (JSONArray) root;
        else if (root instanceof JSONObject) {
            JSONObject o = (JSONObject) root;
            arr = o.optJSONArray("venueIds");
            if (arr == null) arr = o.optJSONArray("favorites");
            if (arr == null) arr = o.optJSONArray("data");
        }
        if (arr == null) return;
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            Object e = arr.get(i);
            if (e instanceof Number) ids.add(((Number) e).intValue());
            else if (e instanceof JSONObject) {
                JSONObject o = (JSONObject) e;
                if (o.has("venueId")) ids.add(o.getInt("venueId"));
                else if (o.optJSONObject("venue") != null) ids.add(o.getJSONObject("venue").getInt("id"));
                else if (o.has("id")) ids.add(o.getInt("id"));
            } else if (e instanceof String) {
                try { ids.add(Integer.parseInt((String) e)); } catch (NumberFormatException ignored) { }
            }
        }
        siteFavorites.clear();
        siteFavorites.addAll(ids);
        siteFavoritesKnown = true;
    }

    /** Fallback: venue names scraped from the site's Favorites page. */
    void setSiteFavoriteNames(List<String> names) {
        if (venues.isEmpty()) {
            pendingFavoriteNames = names;
            return;
        }
        pendingFavoriteNames = null;
        if (siteFavoritesKnown) return; // real ids already known
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (String n : names) {
            String want = n.trim().toLowerCase(Locale.ROOT);
            for (Venue v : venues.values()) {
                if (v.name.trim().toLowerCase(Locale.ROOT).equals(want)) {
                    ids.add(v.id);
                    break;
                }
            }
        }
        if (!ids.isEmpty()) {
            siteFavorites.clear();
            siteFavorites.addAll(ids);
            siteFavoritesKnown = true;
        }
    }

    /** Fresh rink status from the site's /surface/uuid/... responses. */
    boolean updateSurfaces(String json) {
        boolean changed = false;
        try {
            JSONArray arr = new JSONArray(json);
            Map<Integer, JSONObject> byId = new LinkedHashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && o.has("id")) byId.put(o.getInt("id"), o);
            }
            for (Venue v : venues.values()) {
                for (Surface s : v.surfaces) {
                    JSONObject o = byId.get(s.id);
                    if (o == null) continue;
                    boolean online = o.optBoolean("online", s.online);
                    boolean soon = o.optBoolean("comingSoon", s.comingSoon);
                    if (online != s.online || soon != s.comingSoon) changed = true;
                    s.online = online;
                    s.comingSoon = soon;
                }
            }
        } catch (Exception ignored) {
        }
        return changed;
    }

    List<Venue> search(String query, int limit) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Venue> starts = new ArrayList<>(), contains = new ArrayList<>();
        if (q.length() < 2) return starts;
        String[] words = q.split("\\s+");
        for (Venue v : venues.values()) {
            boolean all = true;
            for (String w : words) if (!v.key.contains(w)) { all = false; break; }
            if (!all) continue;
            if (v.name.toLowerCase(Locale.ROOT).startsWith(q)) starts.add(v); else contains.add(v);
        }
        Collections.sort(starts, (a, b) -> a.name.compareToIgnoreCase(b.name));
        Collections.sort(contains, (a, b) -> a.name.compareToIgnoreCase(b.name));
        starts.addAll(contains);
        return starts.size() > limit ? starts.subList(0, limit) : starts;
    }
}
