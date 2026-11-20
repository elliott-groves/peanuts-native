package dev.peanuts;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Companion packages built into this APK: android/app/build.gradle's -PnxCompanions=<TITLEID>-<Package>-<version>.dsmod.zip[,...] copies each into
 * assets/companions/ and lists it in assets/companions/index.json with its SHA-256, taken at build time. {@link Catalog#fetchVersions()} offers them beside the
 * GitHub releases (a release of the same title and version is preferred), so the catalog screen, a game's Build step and the ctl "companion" command install
 * them the usual way; {@link Installer} copies one out of the APK instead of downloading it, and checks the digest as for a download. Without the option the
 * index is empty and nothing changes.
 */
public final class Bundled {
    static final String DIR = "companions";
    static final String SCHEME = "asset:";
    private static volatile Context app;

    private Bundled() {}

    /** Every process, at its start (dev.peanuts.ui.AppScreens.install, from NxApp) */
    public static void init(Context c) { if (c != null) app = c.getApplicationContext(); }

    /** The packages in this APK, by title (upper-case title ID); empty when there are none or the index cannot be read */
    static Map<String, List<Catalog.Item>> items() {
        Map<String, List<Catalog.Item>> out = new LinkedHashMap<>();
        Context c = app;
        if (c == null) return out;
        try (InputStream in = c.getAssets().open(DIR + "/index.json")) {
            JSONArray a = new JSONArray(new String(in.readAllBytes(), "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String title = o.optString("titleId").toUpperCase(Locale.ROOT), file = o.optString("file"), pkg = o.optString("package"), ver = o.optString("version");
                String sha = o.optString("sha256");
                // the same checks the catalog makes on a release asset's name: the version names a folder, the package a folder too
                if (!title.matches("[0-9A-F]{16}") || !pkg.matches("[A-Za-z0-9]+") || !ver.matches("[0-9A-Za-z.\\-]{1,32}") || ver.contains("..")
                        || !sha.matches("[0-9a-fA-F]{64}") || file.contains("/")) continue;
                Catalog.Item it = new Catalog.Item(title, pkg, ver, SCHEME + DIR + "/" + file, "bundled", o.optLong("size"), o.optString("name", title), "",
                        "Built into this app", sha.toLowerCase(Locale.ROOT), null);
                out.computeIfAbsent(title, k -> new ArrayList<>()).add(it);
            }
        } catch (java.io.FileNotFoundException e) {
            // an APK built before this option: no index
        } catch (Exception e) {
            android.util.Log.w(Companion.TAG, "bundled companions: " + e);
        }
        return out;
    }

    /** Copies a bundled package (an "asset:" URL of {@link #items()}) to `to`, as Installer's download does for a release */
    static void copy(String url, File to, long total, Installer.Progress progress) throws IOException {
        Context c = app;
        if (c == null) throw new IOException("bundled companions are not available in this process");
        String path = url.substring(SCHEME.length());
        if (!path.startsWith(DIR + "/") || path.contains("..")) throw new IOException("not a bundled companion: " + url);
        try (InputStream in = c.getAssets().open(path); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536]; long done = 0; int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); done += n; if (progress != null) progress.on("copy", done, total); }
        }
    }
}
