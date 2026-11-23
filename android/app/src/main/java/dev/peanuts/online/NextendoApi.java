package dev.peanuts.online;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Nextendo Network's account APIs for Home (wiki.nextendo.network/developers, "Permissions & Scopes"), with the access token of the account signed in in
 * Settings › Online ({@link Nextendo#accessToken}): the player's own profile and friend code (GET /api/me, scope identity), the friend list with what each
 * friend is playing, friend requests, adding by friend code and removing (GET/POST /api/friends..., scope friends), and the number of players online in each
 * game (GET /api/online-counts, public: the Status page's numbers). Pictures come from /api/avatar?pid=, which the site itself loads without a token.
 *
 * Every call here is network: they run on this class's own thread and answer on the UI thread. The PID is used to ask for a picture and to name a friend in
 * a request, never logged or shown.
 */
public final class NextendoApi {
    private static final int TIMEOUT_MS = 15_000;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService NET = Executors.newFixedThreadPool(3, r -> { Thread t = new Thread(r, "nextendo-api"); t.setDaemon(true); return t; });
    private static final LruCache<String, Bitmap> PICTURES = new LruCache<String, Bitmap>(8 << 20) { @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); } };

    private NextendoApi() {}

    /** A call's outcome: the JSON on success, else the reason the player sees */
    public static final class Result {
        public final JSONObject json; public final String error; public final int code;
        Result(JSONObject json, String error, int code) { this.json = json; this.error = error; this.code = code; }
        public boolean ok() { return error == null; }
    }

    /** The player's own account, as the button and the friends page show it */
    public static final class Me {
        public final long pid; public final String username, name, friendCode;
        Me(long pid, String username, String name, String friendCode) { this.pid = pid; this.username = username; this.name = name; this.friendCode = friendCode; }
        public String shown() { return name.isEmpty() ? username : name; }
    }

    /** A friend, a request received or one sent, with the friend's presence (status 0 offline, 1 online, 2 playing; app_id the game's title ID) */
    public static final class Friend {
        public final long pid; public final String username, name, friendCode, titleId, game, detail, platform;
        public final int status; public final boolean favorite;
        Friend(JSONObject f) {
            pid = f.optLong("pid"); username = f.optString("username"); name = f.optString("name"); friendCode = f.optString("friend_code");
            favorite = f.optBoolean("favorite");
            JSONObject p = f.optJSONObject("presence");
            if (p == null) p = new JSONObject();
            status = Math.max(0, p.optInt("status"));
            titleId = p.optString("app_id", "").toUpperCase(Locale.ROOT);
            game = p.optString("jeu", p.optString("game", p.optString("name", "")));
            detail = p.optString("app_detail", "").trim();
            String pn = p.optString("platform_name", "");
            platform = !pn.isEmpty() ? pn : platformName(p.optInt("pf"), p.optString("plateforme", p.optString("platform", "")));
        }
        public String shown() { return !name.isEmpty() ? name : !username.isEmpty() ? username : "Player"; }
        public boolean playing() { return status > 0 && !titleId.isEmpty(); }
    }

    /** The friend list, requests received and requests sent */
    public static final class Friends {
        public final List<Friend> friends = new ArrayList<>(), requests = new ArrayList<>(), sent = new ArrayList<>();
    }

    // The site's own names for the platform codes (nextendo.network's compte.js)
    static String platformName(int pf, String p) {
        switch (pf) {
            case 1: return "Nintendo Switch";
            case 2: return "Nintendo Switch 2";
            case 3: return "Ryujinx Nextendo";
            case 4: return "Citron Nextendo";
            case 5: return "Nextendo App";
            default: break;
        }
        switch (p == null ? "" : p) {
            case "switch": return "Nintendo Switch";
            case "ryujinx": case "emulator": return "Ryujinx Nextendo";
            case "citron": return "Citron Nextendo";
            case "app": return "Nextendo App";
            default: return "";
        }
    }

    // ---------- the calls ----------

    public static void me(Context c, Consumer<Me> done) {
        Context app = c.getApplicationContext();
        NET.execute(() -> {
            Result r = call(app, "GET", "/api/me", null);
            Me m = null;
            if (r.ok()) {
                JSONObject a = r.json.optJSONObject("account");
                if (a != null && a.optLong("pid") != 0) {
                    m = new Me(a.optLong("pid"), a.optString("username"), a.optString("name", a.optString("display_name", "")), a.optString("friend_code"));
                    keepMe(app, m);
                }
            }
            Me fm = m;
            UI.post(() -> done.accept(fm));
        });
    }

    /** The account as last read (pictures and the button's caption before the network answers), or null */
    public static Me cachedMe(Context c) {
        android.content.SharedPreferences p = c.getApplicationContext().getSharedPreferences("peanuts_nextendo_me", Context.MODE_PRIVATE);
        long pid = p.getLong("pid", 0);
        return pid == 0 || Nextendo.username(c) == null ? null : new Me(pid, p.getString("username", ""), p.getString("name", ""), p.getString("friend_code", ""));
    }

    private static void keepMe(Context c, Me m) {
        c.getSharedPreferences("peanuts_nextendo_me", Context.MODE_PRIVATE).edit().putLong("pid", m.pid).putString("username", m.username)
                .putString("name", m.name).putString("friend_code", m.friendCode).apply();
    }

    /** Signed out: the account's cached profile goes too (Nextendo.signOut) */
    static void forget(Context c) {
        c.getApplicationContext().getSharedPreferences("peanuts_nextendo_me", Context.MODE_PRIVATE).edit().clear().apply();
        PICTURES.evictAll();
    }

    public static void friends(Context c, Consumer<Friends> done, Consumer<String> failed) {
        Context app = c.getApplicationContext();
        NET.execute(() -> {
            Result r = call(app, "GET", "/api/friends?images=0", null);
            if (!r.ok()) { UI.post(() -> failed.accept(r.error)); return; }
            Friends f = new Friends();
            fill(f.friends, r.json.optJSONArray("friends"));
            fill(f.requests, r.json.optJSONArray("requests"));
            fill(f.sent, r.json.optJSONArray("sent"));
            // playing first, then online, then offline; favourites first within each, then by name
            Collections.sort(f.friends, (x, y) -> {
                int rx = x.playing() ? 0 : x.status > 0 ? 1 : 2, ry = y.playing() ? 0 : y.status > 0 ? 1 : 2;
                if (rx != ry) return rx - ry;
                if (x.favorite != y.favorite) return x.favorite ? -1 : 1;
                return x.shown().compareToIgnoreCase(y.shown());
            });
            UI.post(() -> done.accept(f));
        });
    }

    private static void fill(List<Friend> out, JSONArray a) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && o.optLong("pid") != 0) out.add(new Friend(o)); }
    }

    /** Sends a friend request to a friend code (SW-0000-0000-0000; the dashes and the prefix are added when left out) */
    public static void addFriend(Context c, String code, Consumer<Result> done) {
        Context app = c.getApplicationContext();
        String fc = friendCode(code);
        NET.execute(() -> {
            Result r;
            if (fc == null) r = new Result(null, "That is not a friend code: it looks like SW-1234-5678-9012", 400);
            else {
                try { r = call(app, "POST", "/api/friends", new JSONObject().put("friend_code", fc)); }
                catch (org.json.JSONException e) { r = new Result(null, e.getMessage(), 0); }
                if (!r.ok() && r.code == 404) r = new Result(null, "No player has the friend code " + fc, 404);
                else if (!r.ok() && r.code == 400) r = new Result(null, "That is your own friend code", 400);
                else if (!r.ok() && r.code == 409) r = new Result(null, "A friend list is full (300 friends at most)", 409);
            }
            Result fr = r;
            UI.post(() -> done.accept(fr));
        });
    }

    /** accept, decline, remove (a friend, or a request sent), block or unblock: POST /api/friends/<action> {"pid": N} */
    public static void friendAction(Context c, String action, long pid, Consumer<Result> done) {
        Context app = c.getApplicationContext();
        NET.execute(() -> {
            Result r;
            try { r = call(app, "POST", "/api/friends/" + action, new JSONObject().put("pid", pid)); }
            catch (org.json.JSONException e) { r = new Result(null, e.getMessage(), 0); }
            Result fr = r;
            UI.post(() -> done.accept(fr));
        });
    }

    /** "SW-1234-5678-9012" from what was typed (digits with or without the dashes and the SW- prefix), or null */
    static String friendCode(String s) {
        String d = (s == null ? "" : s).toUpperCase(Locale.ROOT).replaceAll("^\\s*SW", "").replaceAll("[^0-9]", "");
        return d.length() == 12 ? "SW-" + d.substring(0, 4) + "-" + d.substring(4, 8) + "-" + d.substring(8) : null;
    }

    // ---------- players online in each game (public) ----------

    private static volatile Map<String, Integer> counts = Collections.emptyMap();
    private static volatile Map<String, String> countNames = Collections.emptyMap();
    private static volatile long countsAt;
    private static volatile boolean countsBusy;

    /** The last counts read, by upper-case title ID: every game Nextendo serves is in it, with 0 when nobody plays it now */
    public static Map<String, Integer> counts() { return counts; }
    /** Nextendo's name of a game it serves, by upper-case title ID, or null */
    public static String countName(String titleId) { return titleId == null ? null : countNames.get(titleId.toUpperCase(Locale.ROOT)); }

    /** Reads the counts again when the last read is older than maxAgeMs; done (UI thread) only when they were read */
    public static void refreshCounts(long maxAgeMs, Runnable done) {
        if (countsBusy || System.currentTimeMillis() - countsAt < maxAgeMs) return;
        countsBusy = true;
        NET.execute(() -> {
            try {
                HttpURLConnection h = open(Nextendo.base() + "/api/online-counts");
                Result r = answer(h);
                if (r.ok()) {
                    Map<String, Integer> m = new HashMap<>();
                    JSONObject o = r.json.optJSONObject("counts");
                    if (o != null) for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); m.put(k.toUpperCase(Locale.ROOT), o.optInt(k)); }
                    Map<String, String> names = new HashMap<>();
                    JSONArray j = r.json.optJSONArray("jeux");
                    if (j != null) for (int i = 0; i < j.length(); i++) {
                        JSONObject g = j.optJSONObject(i); JSONArray ts = g == null ? null : g.optJSONArray("titres");
                        if (ts != null) for (int k = 0; k < ts.length(); k++) names.put(ts.optString(k).toUpperCase(Locale.ROOT), g.optString("nom"));
                    }
                    counts = Collections.unmodifiableMap(m);
                    countNames = Collections.unmodifiableMap(names);
                    countsAt = System.currentTimeMillis();
                    UI.post(done);
                } else {
                    countsAt = System.currentTimeMillis() - Math.max(0, maxAgeMs - 30_000L);   // try again in about 30 s, not at every render
                }
            } catch (Exception e) {
                Log.w(Nextendo.TAG, "online counts: " + e.getClass().getSimpleName());
                countsAt = System.currentTimeMillis() - Math.max(0, maxAgeMs - 30_000L);
            } finally {
                countsBusy = false;
            }
        });
    }

    // ---------- pictures ----------

    /** A player's picture (/api/avatar?pid=), or a game's icon (/api/game-icon/<title ID>.jpg) when pid is 0 and titleId is set; done gets null when there is none */
    public static void picture(long pid, String titleId, Consumer<Bitmap> done) {
        String path = pid != 0 ? "/api/avatar?pid=" + pid : titleId != null && titleId.matches("[0-9A-Fa-f]{16}") ? "/api/game-icon/" + titleId.toLowerCase(Locale.ROOT) + ".jpg" : null;
        if (path == null) { done.accept(null); return; }
        Bitmap hit = PICTURES.get(path);
        if (hit != null) { done.accept(hit); return; }
        NET.execute(() -> {
            Bitmap b = null;
            try {
                HttpURLConnection h = open(Nextendo.base() + path);
                if (h.getResponseCode() == 200) try (InputStream in = h.getInputStream()) {
                    byte[] bytes = readAll(in, 4 << 20);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                    int s = 1; while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 256) s *= 2;
                    o = new BitmapFactory.Options(); o.inSampleSize = s;
                    b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                }
                h.disconnect();
            } catch (Exception e) { /* no picture: the letter stays */ }
            if (b != null) PICTURES.put(path, b);
            Bitmap fb = b;
            UI.post(() -> done.accept(fb));
        });
    }

    // ---------- HTTP ----------

    /** One authorized call; a 401 asks for a new access token once and tries again */
    static Result call(Context app, String method, String path, JSONObject body) {
        if (Nextendo.username(app) == null) return new Result(null, "Not signed in to Nextendo", 401);
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                String token = Nextendo.accessToken(app, attempt > 0);
                if (token == null) return new Result(null, "Nextendo did not accept the account: sign in again in Settings › Online", 401);
                HttpURLConnection h = open(Nextendo.base() + path);
                h.setRequestMethod(method);
                h.setRequestProperty("Authorization", "Bearer " + token);
                if (body != null) {
                    h.setDoOutput(true);
                    h.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                }
                Result r = answer(h);
                if (r.code == 401 && attempt == 0) continue;
                return r;
            }
        } catch (java.net.UnknownHostException | java.net.SocketTimeoutException | java.net.ConnectException e) {
            return new Result(null, "Nextendo cannot be reached (no connection?)", 0);
        } catch (Exception e) {
            Log.w(Nextendo.TAG, method + " " + path.replaceAll("\\?.*", "") + ": " + e.getClass().getSimpleName());
            return new Result(null, "Nextendo did not answer (" + e.getClass().getSimpleName() + ")", 0);
        }
        return new Result(null, "Nextendo did not accept the account: sign in again in Settings › Online", 401);
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(TIMEOUT_MS); h.setReadTimeout(TIMEOUT_MS);
        if (!Nextendo.clientId().isEmpty()) h.setRequestProperty("X-Nextendo-Client-Id", Nextendo.clientId());
        h.setRequestProperty("Accept", "application/json");
        h.setRequestProperty("User-Agent", "PeanutsNative");
        return h;
    }

    private static Result answer(HttpURLConnection h) throws Exception {
        int code = h.getResponseCode();
        InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
        byte[] bytes = in == null ? new byte[0] : readAll(in, 8 << 20);
        h.disconnect();
        JSONObject j = null;
        try { j = new JSONObject(new String(bytes, StandardCharsets.UTF_8)); } catch (Exception ignored) { /* not JSON */ }
        if (code == 429) return new Result(null, "Nextendo asks to wait a moment (too many requests)", code);
        if (code >= 400) {
            String m = j == null ? "" : j.optString("message", "");
            if (m.isEmpty() && j != null && j.opt("error") instanceof String) m = j.optString("error");
            return new Result(null, m.isEmpty() ? "Nextendo refused (HTTP " + code + ")" : m, code);   // the server's own English message, never what was sent
        }
        return j == null ? new Result(null, "Nextendo answered something unexpected", code) : new Result(j, null, code);
    }

    private static byte[] readAll(InputStream in, int max) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > max) throw new java.io.IOException("too large"); }
        in.close();
        return b.toByteArray();
    }
}
