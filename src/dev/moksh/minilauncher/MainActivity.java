package dev.moksh.minilauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Minimal Apple-TV-style launcher. One Activity, one custom View that draws everything,
 * no libraries. Icons are decoded once at tile size; wallpaper (optional) is RGB_565.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "pins";

    static final class Tile {
        final String key;
        final String label;
        final Intent intent;
        Bitmap bmp;
        BitmapShader shader;
        boolean pinned;
        long last;          // when it was last used (ms since epoch); 0 = never seen

        Tile(String key, String label, Intent intent) {
            this.key = key;
            this.label = label;
            this.intent = intent;
        }
    }

    static final int[][] PRESETS = {
            {0xFF2B2F3A, 0xFF0B0C10}, {0xFF1E3A5F, 0xFF070B14},
            {0xFF5A3A2A, 0xFF120A08}, {0xFF1F4A3C, 0xFF06100C}};
    static final String[] PRESET_NAMES = {"Slate", "Ocean", "Ember", "Forest", "Art"};
    // control center: 0 network, 1 display, 2 theme, 3 clock, 4 wallpaper, 5 home,
    // 6 bluetooth, 7 reload apps, 8 volume, 9 mute  -> row of each item
    static final int[] CTL_ROW = {0, 0, 1, 1, 2, 2, 2, 2, 3, 3};
    static final int CTL_N = 10;

    private HomeView view;
    private final Map<String, Tile> cache = new HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        view = new HomeView(this);
        setContentView(view);
        view.requestFocus();
        registerReceiver(screenOff, new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    /** TV switched off / went to sleep: the next time home shows, pick a new painting. */
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("repick", true).commit();
        }
    };

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        view.resetToHome();
    }

    @Override
    protected void onStart() {
        super.onStart();
        reload();
        view.refreshArt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(screenOff);
    }

    @Override
    public void onBackPressed() {
        view.handleBack();
        // never exit: a home screen has nowhere to go back to
    }

    private void reload() {
        final PackageManager pm = getPackageManager();
        final SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        final Set<String> pins = sp.getStringSet("p", new HashSet<String>());
        Map<String, Tile> found = new HashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found, pins);
        collect(pm, Intent.CATEGORY_LAUNCHER, found, pins);
        found.remove(getPackageName());

        // last-used time: what Android's usage stats say (if usage access was granted),
        // or when this launcher last opened the app, whichever is more recent
        SharedPreferences used = getSharedPreferences("last", MODE_PRIVATE);
        Map<String, Long> usage = systemUsage();
        for (Tile t : found.values()) {
            Long u = usage.get(t.key);
            t.last = Math.max(used.getLong(t.key, 0L), u == null ? 0L : u);
        }

        List<Tile> list = new ArrayList<>(found.values());
        Collections.sort(list, new Comparator<Tile>() {
            @Override
            public int compare(Tile a, Tile b) {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                if (a.last != b.last) return a.last > b.last ? -1 : 1;   // most recent first
                return a.label.compareToIgnoreCase(b.label);             // never used: A-Z
            }
        });
        cache.clear();
        cache.putAll(found);
        view.setTiles(list);
    }

    private void collect(PackageManager pm, String category, Map<String, Tile> out, Set<String> pins) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
        for (ResolveInfo ri : ris) {
            ActivityInfo ai = ri.activityInfo;
            if (out.containsKey(ai.packageName)) continue;
            Tile t = cache.get(ai.packageName);
            if (t == null) {
                Intent li = new Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setComponent(new ComponentName(ai.packageName, ai.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                t = new Tile(ai.packageName, String.valueOf(ri.loadLabel(pm)), li);
            }
            t.pinned = pins.contains(ai.packageName);
            out.put(ai.packageName, t);
        }
    }

    private void togglePin(Tile t) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> pins = new HashSet<>(sp.getStringSet("p", new HashSet<String>()));
        if (!pins.add(t.key)) pins.remove(t.key);
        sp.edit().putStringSet("p", pins).apply();
        reload();
    }

    private void launch(Tile t) {
        try {
            startActivity(t.intent);
            getSharedPreferences("last", MODE_PRIVATE).edit()
                    .putLong(t.key, System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Last-used times from Android itself; empty unless "usage access" was granted to this app. */
    private Map<String, Long> systemUsage() {
        Map<String, Long> out = new HashMap<>();
        try {
            android.app.AppOpsManager ops = (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), getPackageName());
            if (mode != android.app.AppOpsManager.MODE_ALLOWED) return out;
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            Map<String, android.app.usage.UsageStats> m = usm.queryAndAggregateUsageStats(now - 30L * 86400000L, now);
            if (m != null) for (Map.Entry<String, android.app.usage.UsageStats> e : m.entrySet()) {
                out.put(e.getKey(), e.getValue().getLastTimeUsed());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---------------------------------------------------------------- view

    final class HomeView extends View {
        private static final int COLS = 6;
        private static final int VISIBLE_ROWS = 3;

        private List<Tile> tiles = new ArrayList<>();
        private int sel;
        private float p;        // 0 = home (clock + first row), 1 = browsing
        private float sr;       // animated first visible row while browsing
        private Bitmap wall;
        private Shader dim;
        private int dimW, dimH;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final Matrix mx = new Matrix();
        private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault());
        private final Date now = new Date();
        private boolean loading;

        // control center / header state
        private static final int Z_GRID = 0, Z_HEADER = 1, Z_PANEL = 2;
        private final SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        private final android.media.AudioManager audio =
                (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        private int zone = Z_GRID;
        private int hdrSel;
        private int pSel;
        private float panelP;
        private boolean dark = cfg.getBoolean("dark", true);
        private boolean h24 = cfg.getBoolean("h24", true);
        private int wp = cfg.getInt("wp", 4);

        HomeView(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
            applyClockFormat();
        }

        private void applyClockFormat() {
            timeFmt.applyPattern(h24 ? "HH:mm" : "h:mm");
        }

        private boolean hasPhoto() {
            File dir = getExternalFilesDir(null);
            return dir != null && new File(dir, "wallpaper.jpg").exists();
        }

        private int fg() { return dark ? Color.WHITE : 0xFF14181F; }

        boolean isBrowsing() {
            return sel >= COLS || p > 0.5f;
        }

        void resetToHome() {
            sel = 0;
            zone = Z_GRID;
            invalidate();
        }

        void handleBack() {
            if (zone == Z_PANEL) zone = Z_HEADER;
            else if (zone == Z_HEADER) zone = Z_GRID;
            else if (isBrowsing()) sel = 0;
            invalidate();
        }

        private void startSettings(String action) {
            try {
                startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                android.util.Log.i("ML", "opened " + action);
            } catch (Exception e) {
                android.util.Log.i("ML", "no handler for " + action + ", falling back");
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
            }
        }

        private void activate(int i) {
            android.util.Log.i("ML", "activate " + i);
            switch (i) {
                case 0: startSettings(Settings.ACTION_WIFI_SETTINGS); break;
                case 1: startSettings(Settings.ACTION_DISPLAY_SETTINGS); break;
                case 2:
                    dark = !dark;
                    cfg.edit().putBoolean("dark", dark).apply();
                    dim = null;
                    invalidateBackdrop();
                    break;
                case 3:
                    h24 = !h24;
                    cfg.edit().putBoolean("h24", h24).apply();
                    applyClockFormat();
                    break;
                case 4:   // next random painting
                    if (wp != 4) {
                        wp = 4;
                        cfg.edit().putInt("wp", 4).apply();
                        dim = null;
                        invalidateBackdrop();
                    }
                    startArtFetch();
                    break;
                case 5:
                    sel = 0;
                    zone = Z_GRID;
                    break;
                case 6:
                    try {   // TCL's "Remotes and Accessories" page holds the Bluetooth toggle and pairing
                        startActivity(new Intent().setComponent(new ComponentName("com.android.tv.settings",
                                "com.tcl.settings.accessories.AccessoriesActivity"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        android.util.Log.i("ML", "opened accessories");
                    } catch (Exception e) {
                        startSettings(Settings.ACTION_BLUETOOTH_SETTINGS);
                    }
                    break;
                case 7: reload(); break;
                case 9:
                    audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                            android.media.AudioManager.ADJUST_TOGGLE_MUTE, 0);
                    break;
                default: break;
            }
        }

        private int volPct() {
            int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            return audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / Math.max(1, max);
        }

        private boolean muted() {
            return android.os.Build.VERSION.SDK_INT >= 23
                    ? audio.isStreamMute(android.media.AudioManager.STREAM_MUSIC)
                    : volPct() == 0;
        }

        private void adjustVolume(int d) {
            int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            int v = Math.max(0, Math.min(max, audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) + d));
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0);
        }

        private String subtitle(int i) {
            switch (i) {
                case 0: return "Wi-Fi";
                case 1: return "Settings";
                case 2: return dark ? "Dark" : "Light";
                default: return h24 ? "24-hour" : "12-hour";
            }
        }

        void setTiles(List<Tile> list) {
            String keep = sel < tiles.size() ? tiles.get(sel).key : null;
            tiles = list;
            sel = 0;
            if (keep != null) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).key.equals(keep)) { sel = i; break; }
                }
            }
            loadBitmaps();
            invalidate();
        }

        // ---- art wallpaper: one random public-domain painting per "TV on" session
        private static final String ART_API = "https://openaccess-api.clevelandart.org/api/artworks/"
                + "?cc0=1&has_image=1&type=Painting&q=landscape&fields=title,creators,creation_date,images";
        private final File artFile = new File(getCacheDir(), "art.jpg");
        private final File artMeta = new File(getCacheDir(), "art.txt");
        private final Paint wallPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Rect src = new android.graphics.Rect();
        private String artTitle, artSub;
        private boolean fetching;

        private int bootCount() {
            try {
                return Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, 0);
            } catch (Exception e) {
                return 0;
            }
        }

        /** Called from onStart: show the cached painting, and fetch a new one only if the TV slept/rebooted. */
        void refreshArt() {
            if (wp != 4) return;
            if (wall == null) loadWallpaper();
            int boot = bootCount();
            if (boot != cfg.getInt("boot", -1)) cfg.edit().putBoolean("repick", true).putInt("boot", boot).apply();
            if (cfg.getBoolean("repick", true) || !artFile.exists()) startArtFetch();
        }

        private void startArtFetch() {
            if (fetching) return;
            fetching = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        fetchArt();
                        cfg.edit().putBoolean("repick", false).apply();
                        StringBuilder sb = new StringBuilder();
                        File[] fs = getCacheDir().listFiles();
                        if (fs != null) for (File f : fs) sb.append(f.getName()).append('=').append(f.length() / 1024).append("KB ");
                        android.util.Log.i("ML", "cache now: " + sb);
                        post(new Runnable() {
                            @Override
                            public void run() {
                                Bitmap old = wall;
                                wall = null;
                                if (old != null) old.recycle();
                                invalidateBackdrop();
                                loadWallpaper();
                            }
                        });
                    } catch (Exception ignored) {
                        // offline: keep whatever is cached
                    } finally {
                        fetching = false;
                    }
                }
            }, "art").start();
        }

        private String httpGet(String u) throws java.io.IOException {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        }

        private void fetchArt() throws Exception {
            int total = new org.json.JSONObject(httpGet(ART_API + "&limit=1")).getJSONObject("info").getInt("total");
            java.util.Random rnd = new java.util.Random();
            for (int attempt = 0; attempt < 6; attempt++) {
                int skip = rnd.nextInt(Math.max(1, total - 20));
                org.json.JSONArray arr = new org.json.JSONObject(
                        httpGet(ART_API + "&limit=20&skip=" + skip)).getJSONArray("data");
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) order.add(i);
                Collections.shuffle(order, rnd);
                for (int i : order) {
                    org.json.JSONObject o = arr.getJSONObject(i);
                    org.json.JSONObject im = o.optJSONObject("images");
                    if (im == null) continue;
                    org.json.JSONObject web = im.optJSONObject("web");
                    org.json.JSONObject print = im.optJSONObject("print");
                    if (web == null || print == null) continue;
                    int ww = Integer.parseInt(print.optString("width", "0"));
                    int wh = Integer.parseInt(print.optString("height", "0"));
                    if (ww < wh * 1.25f || ww < 1400) continue;   // wide, sharp-enough paintings only
                    if (Long.parseLong(print.optString("filesize", "0")) > 4000000L) continue;   // keep downloads small
                    String url = print.getString("url");
                    String artist = "Unknown";
                    org.json.JSONArray cr = o.optJSONArray("creators");
                    if (cr != null && cr.length() > 0) {
                        artist = cr.getJSONObject(0).optString("description", artist);
                        int par = artist.indexOf(" (");
                        if (par > 0) artist = artist.substring(0, par);
                    }
                    String date = o.optString("creation_date", "");
                    download(url, artFile);
                    java.io.FileWriter fw = new java.io.FileWriter(artMeta);
                    fw.write(fixText(o.optString("title", "Untitled")) + "\n" + fixText(artist)
                            + (date.length() > 0 ? ", " + fixText(date) : "") + "\n");
                    fw.close();
                    return;
                }
            }
            throw new java.io.IOException("no landscape found");
        }

        /** The museum's JSON sometimes carries UTF-8 text that was mis-decoded as Windows-1252; undo that. */
        private String fixText(String s) {
            if (s.indexOf(0xE2) < 0 && s.indexOf(0xC3) < 0) return s;
            try {
                String r = new String(s.getBytes("windows-1252"), "UTF-8");
                return r.indexOf(0xFFFD) >= 0 ? s : r;
            } catch (Exception e) {
                return s;
            }
        }

        /** Download to a temp file, then replace the cached painting so only one image is ever stored. */
        private void download(String u, File dest) throws java.io.IOException {
            File tmp = new File(dest.getPath() + ".tmp");
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(30000);
                java.io.InputStream in = c.getInputStream();
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                dest.delete();                       // remove the previous painting
                if (!tmp.renameTo(dest)) throw new java.io.IOException("rename failed");
            } finally {
                if (tmp.exists()) tmp.delete();      // never leave a partial download behind
            }
        }

        void loadWallpaper() {
            if (wall != null || wp != 4) return;
            File f = artFile;
            File dir = getExternalFilesDir(null);
            File local = dir == null ? null : new File(dir, "wallpaper.jpg");
            if (local != null && local.exists()) f = local;   // manual override via adb push
            if (!f.exists()) return;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1;
            while (o.outWidth / s > 2200) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            wall = BitmapFactory.decodeFile(f.getPath(), o);
            domRaw = wall == null ? 0 : dominant(wall);
            artTitle = null;
            artSub = null;
            if (f == artFile && artMeta.exists()) {
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(artMeta));
                    artTitle = br.readLine();
                    artSub = br.readLine();
                    br.close();
                } catch (Exception ignored) {
                }
            }
            invalidate();
        }

        void dropWallpaper() {
            if (wall != null) { wall.recycle(); wall = null; }
        }

        /** Decode icons one at a time on a worker thread, at tile size only. */
        private void loadBitmaps() {
            if (loading || getWidth() == 0) return;
            final int tw = tileW(), th = tileH();
            final List<Tile> work = new ArrayList<>();
            for (Tile t : tiles) if (t.bmp == null) work.add(t);
            if (work.isEmpty()) return;
            loading = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    PackageManager pm = getPackageManager();
                    for (Tile t : work) {
                        Bitmap b = render(pm, t, tw, th);
                        t.bmp = b;
                        t.shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                        postInvalidate();
                    }
                    loading = false;
                }
            }, "icons").start();
        }

        private Bitmap render(PackageManager pm, Tile t, int tw, int th) {
            Bitmap b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Drawable d = null;
            boolean banner = true;
            try {
                ComponentName cn = t.intent.getComponent();
                d = pm.getActivityBanner(cn);
                if (d == null) d = pm.getApplicationBanner(t.key);
                if (d == null) {
                    banner = false;
                    d = pm.getActivityIcon(cn);
                }
            } catch (Exception ignored) {
            }
            if (banner && d != null) {
                int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
                if (iw > 0 && ih > 0) {          // keep the artwork's own proportions: scale to cover, crop the overflow
                    float sc = Math.max(tw / (float) iw, th / (float) ih);
                    int dw = Math.round(iw * sc), dh = Math.round(ih * sc);
                    int l = (tw - dw) / 2, tpp = (th - dh) / 2;
                    d.setBounds(l, tpp, l + dw, tpp + dh);
                } else {
                    d.setBounds(0, 0, tw, th);
                }
                d.draw(c);
            } else {
                c.drawColor(0xFF2A2A2E);
                if (d != null) {
                    int s = (int) (th * 0.55f);
                    int l = (tw - s) / 2, tp = (int) (th * 0.12f);
                    d.setBounds(l, tp, l + s, tp + s);
                    d.draw(c);
                }
                Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
                lp.setColor(Color.WHITE);
                lp.setTextSize(th * 0.11f);
                lp.setTextAlign(Paint.Align.CENTER);
                String s = t.label;
                if (lp.measureText(s) > tw * 0.9f) {
                    int n = lp.breakText(s, true, tw * 0.85f, null);
                    s = s.substring(0, Math.max(1, n - 1)) + "…";
                }
                c.drawText(s, tw / 2f, th * 0.92f, lp);
            }
            return b;
        }


        // ---- geometry
        private int pad() { return (int) (getWidth() * 0.063f) + gap(); }   // tiles sit one gap inside the container, whose edges line up with the clock and painting credit
        private int gap() { return (int) (getWidth() * 0.018f); }
        private int tileW() { return (getWidth() - 2 * pad() - (COLS - 1) * gap()) / COLS; }
        private int tileH() { return tileW() * 6 / 10; }
        private int rowH() { return (int) (tileH() * 1.65f); }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            for (Tile t : tiles) { t.bmp = null; t.shader = null; }
            loadBitmaps();
        }

        // ---- input
        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS) {
                zone = zone == Z_PANEL ? Z_HEADER : Z_PANEL;
                if (zone == Z_PANEL) { sel = 0; hdrSel = 0; }
                invalidate();
                return true;
            }
            if (zone == Z_PANEL) return panelKey(code);
            if (zone == Z_HEADER) return headerKey(code);
            int n = tiles.size();
            int old = sel;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (sel + 1 < n) sel++;
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (sel > 0) sel--;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (sel + COLS < n) sel += COLS;
                    else if (sel / COLS < (n - 1) / COLS) sel = n - 1;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (sel >= COLS) sel -= COLS;
                    else { zone = Z_HEADER; hdrSel = 0; }
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (n > 0 && e.getRepeatCount() == 0) e.startTracking();
                    return true;
                default:
                    return super.onKeyDown(code, e);
            }
            invalidate();
            return true;
        }

        private boolean headerKey(int code) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (hdrSel > 0) hdrSel--; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (hdrSel < 1) hdrSel++; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: zone = Z_GRID; break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (hdrSel == 0) { zone = Z_PANEL; pSel = 0; }
                    else startSettings(Settings.ACTION_SETTINGS);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP: break;
                default: return false;
            }
            invalidate();
            return true;
        }

        private boolean panelKey(int code) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (pSel == 8) adjustVolume(-1);
                    else if (pSel > 0 && CTL_ROW[pSel - 1] == CTL_ROW[pSel]) pSel--;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (pSel == 8 && volPct() < 100) adjustVolume(1);
                    else if (pSel + 1 < CTL_N && CTL_ROW[pSel + 1] == CTL_ROW[pSel]) pSel++;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (CTL_ROW[pSel] == 0) zone = Z_HEADER;
                    else pSel = nearestInRow(CTL_ROW[pSel] - 1, pSel);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (CTL_ROW[pSel] < 3) pSel = nearestInRow(CTL_ROW[pSel] + 1, pSel);
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    activate(pSel);
                    break;
                default: return false;
            }
            invalidate();
            return true;
        }

        @Override
        public boolean onKeyLongPress(int code, KeyEvent e) {
            if (zone == Z_GRID && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                if (sel < tiles.size()) togglePin(tiles.get(sel));
                return true;
            }
            return super.onKeyLongPress(code, e);
        }

        @Override
        public boolean onKeyUp(int code, KeyEvent e) {
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER)
                    && zone == Z_GRID && e.isTracking() && !e.isCanceled() && sel < tiles.size()) {
                launch(tiles.get(sel));
                return true;
            }
            return super.onKeyUp(code, e);
        }

        // ---- drawing
        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;

            // animate toward target state
            float tp = sel >= COLS ? 1f : 0f;
            int rows = (tiles.size() + COLS - 1) / COLS;
            int selRow = sel / COLS;
            float tsr = Math.max(0, Math.min(selRow - (VISIBLE_ROWS - 1), Math.max(0, rows - VISIBLE_ROWS)));
            if (selRow < sr) tsr = selRow;
            boolean moving = false;
            p += (tp - p) * 0.28f;
            sr += (tsr - sr) * 0.28f;
            if (Math.abs(tp - p) > 0.005f || Math.abs(tsr - sr) > 0.005f) moving = true;
            else { p = tp; sr = tsr; }

            int ca = (int) (255 * (1f - p));
            float tpp = zone == Z_PANEL ? 1f : 0f;
            panelP += (tpp - panelP) * 0.3f;
            if (Math.abs(tpp - panelP) < 0.01f) panelP = tpp; else moving = true;
            if (panelP > 0f && snap == null) captureSnap(w, h);
            else if (panelP == 0f) { snap = null; snapShader = null; }

            drawScene(c, w, h);
            if (snap != null && panelP > 0f) {   // depth of field: scene goes soft behind the panel
                snapPaint.setAlpha((int) (255 * panelP));
                rf.set(0, 0, w, h);
                c.drawBitmap(snap, null, rf, snapPaint);
            }
            if (panelP > 0f) drawPanel(c, w, h); else drawHeader(c, w, h, ca);

            if (moving || ca > 0) postInvalidateOnAnimation();
            if (ca > 0 && !moving) postInvalidateDelayed(5000); // clock + RAM refresh
        }

        // ---- frosted-glass backdrop: scene rendered at 1/10 size, then box-blurred
        private Bitmap snap;
        private final Paint snapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Shader sheen, rimD, rimL;

        private void captureSnap(int w, int h) {
            int sw = 192, sh = Math.max(1, 192 * h / w);
            Bitmap b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas sc = new Canvas(b);
            sc.scale(sw / (float) w, sh / (float) h);
            drawScene(sc, w, h);
            int[] px = new int[sw * sh];
            b.getPixels(px, 0, sw, 0, 0, sw, sh);
            int[] tmp = new int[px.length];
            for (int k = 0; k < 2; k++) {
                blurPass(px, tmp, sw, sh, 5, true);
                blurPass(tmp, px, sw, sh, 5, false);
            }
            b.setPixels(px, 0, sw, 0, 0, sw, sh);
            snap = b;
            snapShader = null;
        }

        private void blurPass(int[] src, int[] dst, int w, int h, int r, boolean horiz) {
            int len = horiz ? w : h, lines = horiz ? h : w, div = 2 * r + 1;
            for (int l = 0; l < lines; l++) {
                int base = horiz ? l * w : l, st = horiz ? 1 : w;
                int a = 0, rr = 0, g = 0, bl = 0;
                for (int k = -r; k <= r; k++) {
                    int v = src[base + Math.max(0, Math.min(len - 1, k)) * st];
                    a += v >>> 24; rr += (v >> 16) & 255; g += (v >> 8) & 255; bl += v & 255;
                }
                for (int i = 0; i < len; i++) {
                    dst[base + i * st] = ((a / div) << 24) | ((rr / div) << 16) | ((g / div) << 8) | (bl / div);
                    int ad = src[base + Math.min(len - 1, i + r + 1) * st];
                    int rm = src[base + Math.max(0, i - r) * st];
                    a += (ad >>> 24) - (rm >>> 24);
                    rr += ((ad >> 16) & 255) - ((rm >> 16) & 255);
                    g += ((ad >> 8) & 255) - ((rm >> 8) & 255);
                    bl += (ad & 255) - (rm & 255);
                }
            }
        }

        private void drawScene(Canvas c, int w, int h) {
            drawBackground(c, w, h);

            int tw = tileW(), th = tileH(), gap = gap(), pad = pad(), rh = rowH();
            int tp = (int) (w * 0.063f);   // text margin
            float homeY = h * 0.76f, browseY = h * 0.10f;
            float y0 = homeY + (browseY - homeY) * p;

            // clock
            int ca = (int) (255 * (1f - p));
            if (ca > 0) {
                now.setTime(System.currentTimeMillis());
                text.setStyle(Paint.Style.FILL);
                text.setColor(fg());
                text.setAlpha(ca);
                text.setFakeBoldText(true);
                text.setTextSize(h * 0.085f);
                c.drawText(timeFmt.format(now), tp, h * 0.13f, text);
                text.setFakeBoldText(false);
                text.setTextSize(h * 0.034f);
                c.drawText(dateFmt.format(now), tp + 4, h * 0.13f + h * 0.055f, text);
                text.setTextSize(h * 0.022f);
                text.setAlpha((int) (ca * 0.7f));
                c.drawText(stats(), tp + 4, h * 0.13f + h * 0.055f + h * 0.04f, text);
            }

            // pill behind first row (home state only)
            if (p < 0.95f) {
                float yy = y0 - sr * rh;
                rf.set(pad - gap, yy - gap, w - pad + gap, yy + th + gap);
                glassR(c, rf, rf.height() * 0.30f, false, 1f - p);
            }

            // tiles (selected is drawn last so it sits on top)
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < tiles.size(); i++) {
                    boolean isSel = i == sel;
                    if (isSel != (pass == 1)) continue;
                    int r = i / COLS, col = i % COLS;
                    float x = pad + col * (tw + gap);
                    float y = y0 + (r - sr) * rh;
                    if (y + th < -rh || y > h) continue;
                    // home shows only the first row; the rest fades in as you move down
                    float ra = r == 0 ? 1f : Math.max(0f, (p - 0.1f) / 0.9f);
                    if (ra <= 0.01f) continue;
                    tileAlpha = (int) (255 * ra);
                    drawTile(c, tiles.get(i), x, y, tw, th, isSel);
                }
            }
            tileAlpha = 255;

            // painting credit, bottom-right above the dock
            if (wall != null && artTitle != null && ca > 0) {
                text.setStyle(Paint.Style.FILL);
                text.setTextAlign(Paint.Align.RIGHT);
                text.setColor(Color.WHITE);
                text.setAlpha((int) (ca * 0.75f));
                text.setFakeBoldText(false);
                text.setTextSize(h * 0.019f);
                if (artSub != null) c.drawText(artSub, w - tp, h * 0.675f, text);
                text.setAlpha(ca);
                text.setTextSize(h * 0.024f);
                String t = artTitle;
                int n = text.breakText(t, true, w * 0.45f, null);
                if (n < t.length()) t = t.substring(0, Math.max(1, n - 1)) + "…";
                c.drawText(t, w - tp, h * 0.702f, text);
                text.setTextAlign(Paint.Align.LEFT);
                text.setAlpha(255);
            }
        }

        // ---- control center geometry (shared by drawing and navigation)
        private float plLeft, plTop, plW, plH, plGap;
        private final RectF ir = new RectF();
        private final Path pa = new Path();
        private final Path pb = new Path();

        private void layoutPanel() {
            int w = getWidth(), h = getHeight();
            plW = w * 0.213f;
            plH = h * 0.083f;
            plGap = h * 0.0165f;
            plLeft = w * 0.933f - plW;
            plTop = h * 0.143f - (1f - panelP) * h * 0.04f;
        }

        private void itemRect(int i, RectF r) {
            float d = plH, cw = (plW - plGap) / 2f;
            float y = plTop + CTL_ROW[i] * (plH + plGap);
            float x, wd;
            switch (i) {
                case 0: case 2: x = plLeft; wd = cw; break;
                case 1: case 3: x = plLeft + cw + plGap; wd = cw; break;
                case 4: case 5: case 6: case 7:
                    x = plLeft + (i - 4) * (plW - d) / 3f; wd = d; break;
                case 8: x = plLeft; wd = plW - d - plGap; break;
                default: x = plLeft + plW - d; wd = d; break;
            }
            r.set(x, y, x + wd, y + plH);
        }

        private int nearestInRow(int row, int from) {
            layoutPanel();
            itemRect(from, ir);
            float cx = ir.centerX();
            int best = from;
            float bd = 1e9f;
            for (int i = 0; i < CTL_N; i++) {
                if (CTL_ROW[i] != row) continue;
                itemRect(i, ir);
                float dd = Math.abs(ir.centerX() - cx);
                if (dd < bd) { bd = dd; best = i; }
            }
            return best;
        }

        private int withA(int color, int a) {
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
        }

        // ---- glass: frosted backdrop + soft shadow + bevelled hairline rim ----------------------
        private Bitmap bgBlur;
        private BitmapShader bgShader, snapShader;
        private int bgBlurW;
        private Shader hazeS, rimDk, rimLt, topRimS, bottomShadeS, reflS, floorS;
        private final Matrix gm = new Matrix();
        private final Paint shp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF sr2 = new RectF();
        private android.graphics.BlurMaskFilter blurMf;
        private int blurMfR = -1;

        private void invalidateBackdrop() {
            invalidateBackdrop();
            snapShader = null;
            bgBlur = null;
            bgShader = null;
        }

        /** Blurred copy of just the wallpaper (no UI): what the dock and header glass show through. */
        private void ensureBackdrop(int w, int h) {
            if (bgBlur != null && bgBlurW == w) return;
            int bw = 192, bh = Math.max(1, 192 * h / w);
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas sc = new Canvas(b);
            sc.scale(bw / (float) w, bh / (float) h);
            float keep = p;
            p = 0f;
            drawBackground(sc, w, h);
            p = keep;
            int[] px = new int[bw * bh];
            int[] tmp = new int[px.length];
            b.getPixels(px, 0, bw, 0, 0, bw, bh);
            for (int k = 0; k < 2; k++) {
                blurPass(px, tmp, bw, bh, 4, true);
                blurPass(tmp, px, bw, bh, 4, false);
            }
            b.setPixels(px, 0, bw, 0, 0, bw, bh);
            bgBlur = b;
            bgShader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            bgBlurW = w;
        }

        /** Soft drop shadow under a rounded rect, built from a few growing translucent layers (no blur filter, no cache). */
        private void shadow(Canvas c, RectF r, float rad, int alpha, float mult, float dyFrac) {
            shp.setStyle(Paint.Style.FILL);
            shp.setShader(null);
            shp.setMaskFilter(null);
            float spread = r.height() * 0.16f;
            int steps = 6;
            for (int i = 0; i < steps; i++) {
                float g = spread * (i + 1) / steps;
                float f = 1f - (float) i / steps;               // outer layers fainter
                shp.setColor(Color.argb((int) (alpha * mult * f * f * 0.34f), 0, 0, 0));
                sr2.set(r.left - g, r.top - g * 0.4f, r.right + g, r.bottom + g * 1.4f);
                sr2.offset(0, r.height() * dyFrac);
                c.drawRoundRect(sr2, rad + g, rad + g, shp);
            }
        }
        private void glass(Canvas c, RectF r, boolean focus, float mult) {
            glassR(c, r, r.height() / 2f, focus, mult);
        }

        /**
         * Glass: the blurred wallpaper (or, with the Control Center open, the blurred scene) showing
         * through, a darkening tint, a soft grey haze fading down from the top, a hairline rim that is
         * brightest at the top-left, and a drop shadow.
         */
        private void glassR(Canvas c, RectF r, float rad, boolean focus, float mult) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || mult <= 0f) return;
            ensureBackdrop(w, h);
            Shader bd;
            Bitmap bb;
            if (panelP > 0f && snap != null) {
                if (snapShader == null) snapShader = new BitmapShader(snap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                bd = snapShader;
                bb = snap;
            } else {
                bd = bgShader;
                bb = bgBlur;
            }
            if (hazeS == null) {
                hazeS = new LinearGradient(0, 0, 0, 1, new int[]{0x58FFFFFF, 0x1CFFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.45f, 0.85f}, Shader.TileMode.CLAMP);
                rimDk = new LinearGradient(0, 0, 1, 1, new int[]{0xE0FFFFFF, 0x1EFFFFFF, 0x6EFFFFFF},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
                rimLt = new LinearGradient(0, 0, 1, 1, new int[]{0xCCFFFFFF, 0x30000000, 0x70000000},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            }
            int al = (int) (255 * mult);
            boolean dock = r.width() > r.height() * 4f;   // the big row container gets a deeper, mirror-like treatment

            shadow(c, r, rad, dock ? 0x40 : (focus ? 0x78 : 0x52), mult, dock ? 0.06f : 0.12f);

            // frosted backdrop (the wide dock stays clear: the wallpaper itself shows through)
            fill.setStyle(Paint.Style.FILL);
            if (!dock) {
                gm.reset();
                gm.setScale(w / (float) bb.getWidth(), h / (float) bb.getHeight());
                bd.setLocalMatrix(gm);
                fill.setShader(bd);
                fill.setAlpha(al);
                c.drawRoundRect(r, rad, rad, fill);
            }

            // tint: darkens the backdrop (lightens it when focused)
            fill.setShader(null);
            if (dark) {
                fill.setColor(focus ? Color.argb((int) (0x3C * mult), 255, 255, 255)
                        : (dock ? Color.argb((int) (0x12 * mult), 255, 255, 255) : Color.argb((int) (0x26 * mult), 0, 0, 0)));
            } else {
                fill.setColor(Color.argb((int) ((focus ? 0x70 : 0x38) * mult), 255, 255, 255));
            }
            c.drawRoundRect(r, rad, rad, fill);

            // grey-to-clear haze from the top edge
            gm.reset();
            gm.setScale(1f, r.height());
            gm.postTranslate(0f, r.top);
            hazeS.setLocalMatrix(gm);
            fill.setShader(hazeS);
            fill.setAlpha(dock ? (int) (al * 0.30f) : al);
            c.drawRoundRect(r, rad, rad, fill);

            if (dock) {
                if (reflS == null) {
                    reflS = new LinearGradient(0, 0, 1, 0, new int[]{0x1CFFFFFF, 0x08FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x0AFFFFFF},
                            new float[]{0f, 0.22f, 0.45f, 0.8f, 1f}, Shader.TileMode.CLAMP);
                    floorS = new LinearGradient(0, 0, 0, 1, new int[]{0x00000000, 0x00000000, 0x38000000},
                            new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
                }
                gm.reset();
                gm.setScale(r.width(), 1f);
                gm.postTranslate(r.left, 0f);
                reflS.setLocalMatrix(gm);
                fill.setShader(reflS);
                fill.setAlpha(al);
                c.drawRoundRect(r, rad, rad, fill);     // light sweeping in from the left, like a reflection
                gm.reset();
                gm.setScale(1f, r.height());
                gm.postTranslate(0f, r.top);
                floorS.setLocalMatrix(gm);
                fill.setShader(floorS);
                fill.setAlpha(al);
                if (false) c.drawRoundRect(r, rad, rad, fill);
            }
            // hairline rim, brightest top-left, and a faint inner bevel line just inside it
            Shader rim = dark ? rimDk : rimLt;
            gm.reset();
            gm.setScale(r.width(), r.height());
            gm.postTranslate(r.left, r.top);
            rim.setLocalMatrix(gm);
            fill.setShader(rim);
            fill.setStyle(Paint.Style.STROKE);
            float sw = dock ? 2f : Math.max(1.6f, r.height() * 0.014f);
            fill.setStrokeWidth(sw);
            fill.setAlpha(focus ? al : (int) (al * 0.85f));
            c.drawRoundRect(r, rad, rad, fill);
            if (!dock) {
                fill.setStrokeWidth(Math.max(1f, sw * 0.6f));
                fill.setAlpha((int) (al * 0.28f));
                sr2.set(r);
                sr2.inset(sw * 1.7f, sw * 1.7f);
                c.drawRoundRect(sr2, Math.max(0f, rad - sw * 1.7f), Math.max(0f, rad - sw * 1.7f), fill);
            }

            fill.setShader(null);
            fill.setAlpha(255);
            fill.setStyle(Paint.Style.FILL);
        }

        private void line(Canvas c, float x0, float y0, float x1, float y1) {
            c.drawLine(x0, y0, x1, y1, fill);
        }

        /** Hand-drawn line icons so no bitmap resources are needed. s = icon radius in px. */
        private void icon(Canvas c, int id, float cx, float cy, float s, int color) {
            Paint q = fill;
            q.setShader(null);
            q.setColor(color);
            q.setStyle(Paint.Style.STROKE);
            q.setStrokeWidth(s * 0.13f);
            q.setStrokeCap(Paint.Cap.ROUND);
            q.setStrokeJoin(Paint.Join.ROUND);
            pa.reset();
            switch (id) {
                case 0: { // wifi
                    float by = cy + 0.6f * s;
                    for (int k = 1; k <= 3; k++) {
                        float r = k * 0.42f * s;
                        ir.set(cx - r, by - r, cx + r, by + r);
                        c.drawArc(ir, 225, 90, false, q);
                    }
                    q.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, by, s * 0.09f, q);
                    break;
                }
                case 1: // monitor
                    ir.set(cx - 0.95f * s, cy - 0.75f * s, cx + 0.95f * s, cy + 0.35f * s);
                    c.drawRoundRect(ir, s * 0.12f, s * 0.12f, q);
                    line(c, cx, cy + 0.35f * s, cx, cy + 0.75f * s);
                    line(c, cx - 0.5f * s, cy + 0.78f * s, cx + 0.5f * s, cy + 0.78f * s);
                    break;
                case 2: // moon
                    pa.addCircle(cx, cy, 0.8f * s, Path.Direction.CW);
                    pb.reset();
                    pb.addCircle(cx + 0.5f * s, cy - 0.3f * s, 0.7f * s, Path.Direction.CW);
                    pa.op(pb, Path.Op.DIFFERENCE);
                    q.setStyle(Paint.Style.FILL);
                    c.drawPath(pa, q);
                    break;
                case 3: // stopwatch
                    c.drawCircle(cx, cy + 0.12f * s, 0.75f * s, q);
                    line(c, cx, cy - 0.63f * s, cx, cy - 0.9f * s);
                    line(c, cx - 0.25f * s, cy - 0.92f * s, cx + 0.25f * s, cy - 0.92f * s);
                    line(c, cx, cy + 0.12f * s, cx + 0.3f * s, cy - 0.25f * s);
                    break;
                case 4: // image
                    ir.set(cx - 0.95f * s, cy - 0.75f * s, cx + 0.95f * s, cy + 0.75f * s);
                    c.drawRoundRect(ir, s * 0.15f, s * 0.15f, q);
                    pa.moveTo(cx - 0.65f * s, cy + 0.5f * s);
                    pa.lineTo(cx - 0.15f * s, cy - 0.05f * s);
                    pa.lineTo(cx + 0.15f * s, cy + 0.3f * s);
                    pa.lineTo(cx + 0.35f * s, cy + 0.05f * s);
                    pa.lineTo(cx + 0.7f * s, cy + 0.5f * s);
                    c.drawPath(pa, q);
                    q.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx + 0.45f * s, cy - 0.35f * s, s * 0.12f, q);
                    break;
                case 5: // home
                    pa.moveTo(cx - 0.9f * s, cy + 0.05f * s);
                    pa.lineTo(cx, cy - 0.8f * s);
                    pa.lineTo(cx + 0.9f * s, cy + 0.05f * s);
                    pa.moveTo(cx - 0.6f * s, cy - 0.15f * s);
                    pa.lineTo(cx - 0.6f * s, cy + 0.75f * s);
                    pa.lineTo(cx + 0.6f * s, cy + 0.75f * s);
                    pa.lineTo(cx + 0.6f * s, cy - 0.15f * s);
                    pa.moveTo(cx - 0.2f * s, cy + 0.75f * s);
                    pa.lineTo(cx - 0.2f * s, cy + 0.3f * s);
                    pa.lineTo(cx + 0.2f * s, cy + 0.3f * s);
                    pa.lineTo(cx + 0.2f * s, cy + 0.75f * s);
                    c.drawPath(pa, q);
                    break;
                case 6: // bluetooth
                    pa.moveTo(cx - 0.5f * s, cy - 0.45f * s);
                    pa.lineTo(cx + 0.5f * s, cy + 0.4f * s);
                    pa.lineTo(cx, cy + 0.85f * s);
                    pa.lineTo(cx, cy - 0.85f * s);
                    pa.lineTo(cx + 0.5f * s, cy - 0.4f * s);
                    pa.lineTo(cx - 0.5f * s, cy + 0.45f * s);
                    c.drawPath(pa, q);
                    break;
                case 7: { // refresh
                    float r = 0.7f * s;
                    ir.set(cx - r, cy - r, cx + r, cy + r);
                    c.drawArc(ir, -50, 280, false, q);
                    double th = Math.toRadians(230);
                    float px = cx + (float) Math.cos(th) * r, py = cy + (float) Math.sin(th) * r;
                    float dx = (float) -Math.sin(th), dy = (float) Math.cos(th);
                    pa.moveTo(px + dx * 0.32f * s, py + dy * 0.32f * s);
                    pa.lineTo(px - dy * 0.26f * s, py + dx * 0.26f * s);
                    pa.lineTo(px + dy * 0.26f * s, py - dx * 0.26f * s);
                    pa.close();
                    q.setStyle(Paint.Style.FILL);
                    c.drawPath(pa, q);
                    break;
                }
                case 8: case 9: { // speaker / muted speaker
                    pa.moveTo(cx - 0.9f * s, cy - 0.3f * s);
                    pa.lineTo(cx - 0.45f * s, cy - 0.3f * s);
                    pa.lineTo(cx + 0.05f * s, cy - 0.75f * s);
                    pa.lineTo(cx + 0.05f * s, cy + 0.75f * s);
                    pa.lineTo(cx - 0.45f * s, cy + 0.3f * s);
                    pa.lineTo(cx - 0.9f * s, cy + 0.3f * s);
                    pa.close();
                    c.drawPath(pa, q);
                    if (id == 8) {
                        for (int k = 1; k <= 2; k++) {
                            float r = (0.15f + 0.4f * k) * s;
                            ir.set(cx + 0.05f * s - r, cy - r, cx + 0.05f * s + r, cy + r);
                            c.drawArc(ir, -45, 90, false, q);
                        }
                    } else {
                        line(c, cx + 0.35f * s, cy - 0.35f * s, cx + 0.95f * s, cy + 0.35f * s);
                        line(c, cx + 0.35f * s, cy + 0.35f * s, cx + 0.95f * s, cy - 0.35f * s);
                    }
                    break;
                }
                case 10: // sliders
                    for (int k = -1; k <= 1; k += 2) {
                        float yy = cy + k * 0.38f * s;
                        line(c, cx - 0.8f * s, yy, cx + 0.8f * s, yy);
                    }
                    q.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx - 0.25f * s, cy - 0.38f * s, s * 0.17f, q);
                    c.drawCircle(cx + 0.25f * s, cy + 0.38f * s, s * 0.17f, q);
                    break;
                default: { // gear: ring + eight chunky teeth + hub
                    c.drawCircle(cx, cy, s * 0.52f, q);
                    c.drawCircle(cx, cy, s * 0.2f, q);
                    float keepW = q.getStrokeWidth();
                    q.setStrokeWidth(s * 0.3f);
                    q.setStrokeCap(Paint.Cap.BUTT);
                    for (int k = 0; k < 8; k++) {
                        double a = k * Math.PI / 4;
                        line(c, cx + (float) Math.cos(a) * s * 0.58f, cy + (float) Math.sin(a) * s * 0.58f,
                                cx + (float) Math.cos(a) * s * 0.86f, cy + (float) Math.sin(a) * s * 0.86f);
                    }
                    q.setStrokeWidth(keepW);
                    q.setStrokeCap(Paint.Cap.ROUND);
                    break;
                }
            }
            q.setStyle(Paint.Style.FILL);
        }

        private void drawHeader(Canvas c, int w, int h, int alpha) {
            if (alpha <= 0) return;
            float d = h * 0.054f, gap = d * 0.27f, cy = h * 0.086f;
            for (int i = 0; i < 2; i++) {
                float x = w * 0.933f - d - (1 - i) * (d + gap);
                boolean f = zone == Z_HEADER && hdrSel == i;
                rf.set(x, cy - d / 2, x + d, cy + d / 2);
                glass(c, rf, f, alpha / 255f);
                icon(c, 10 + i, rf.centerX(), cy, d * 0.27f, withA(fg(), alpha));
            }
        }

        private void drawPanel(Canvas c, int w, int h) {
            fill.setShader(null);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.argb((int) (60 * panelP), 0, 0, 0));
            c.drawRect(0, 0, w, h, fill);
            drawHeader(c, w, h, (int) (255 * (1f - p)));

            layoutPanel();
            int a = (int) (255 * panelP);
            int accent = withA(0xFF35B8F0, a);
            int tc = withA(fg(), a);
            for (int i = 0; i < CTL_N; i++) {
                boolean f = zone == Z_PANEL && i == pSel;
                itemRect(i, rf);
                if (f) { float g = plH * 0.04f; rf.inset(-g, -g); }
                glass(c, rf, f, panelP);
                float d = rf.height(), cy = rf.centerY();

                if (i == 8) { // slider fill, clipped to the pill
                    pb.reset();
                    pb.addRoundRect(rf, d / 2f, d / 2f, Path.Direction.CW);
                    c.save();
                    c.clipPath(pb);
                    c.clipRect(rf.left, rf.top, rf.left + rf.width() * volPct() / 100f, rf.bottom);
                    int base = dark ? 255 : 0;
                    fill.setColor(Color.argb((int) (0x40 * panelP), base, base, base));
                    c.drawRect(rf, fill);
                    c.restore();
                }

                if (i < 4 || i == 8) {
                    int ic = i == 2 || i == 3 ? accent : tc;
                    icon(c, i < 2 ? i : (i == 8 ? 8 : i), rf.left + d * 0.5f, cy, d * 0.21f, ic);
                    text.setStyle(Paint.Style.FILL);
                    text.setColor(tc);
                    text.setFakeBoldText(true);
                    text.setTextSize(d * 0.22f);
                    float tx = rf.left + d * 0.95f;
                    if (i == 8) {
                        c.drawText("Volume", tx, cy + d * 0.08f, text);
                        text.setFakeBoldText(false);
                        text.setTextAlign(Paint.Align.RIGHT);
                        c.drawText(muted() ? "Muted" : String.valueOf(volPct()), rf.right - d * 0.45f, cy + d * 0.08f, text);
                        text.setTextAlign(Paint.Align.LEFT);
                    } else {
                        String[] t = {"Network", "Picture", "Theme", "Clock"};
                        c.drawText(t[i], tx, cy - d * 0.04f, text);
                        text.setFakeBoldText(false);
                        text.setTextSize(d * 0.19f);
                        text.setColor(withA(fg(), (int) (a * 0.7f)));
                        c.drawText(subtitle(i), tx, cy + d * 0.24f, text);
                    }
                } else {
                    int id = i == 9 ? (muted() ? 9 : 8) : i;
                    boolean busy = i == 4 && fetching;   // downloading the next painting
                    icon(c, id, rf.centerX(), cy, d * 0.22f, busy ? withA(fg(), (int) (a * 0.35f)) : tc);
                    if (busy) postInvalidateDelayed(250);
                }
            }
            text.setStyle(Paint.Style.FILL);
            text.setFakeBoldText(false);
            text.setColor(withA(fg(), (int) (a * 0.9f)));
            text.setTextSize(plH * 0.2f);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("Mini Launcher", plLeft + plW / 2f,
                    plTop + 4 * plH + 3 * plGap + h * 0.045f, text);
            text.setTextAlign(Paint.Align.LEFT);
        }

        // ---- RAM under the date, from ActivityManager (total minus available; cache counts as available)
        private final android.app.ActivityManager actMgr =
                (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        private final android.app.ActivityManager.MemoryInfo memInfo = new android.app.ActivityManager.MemoryInfo();
        private String statsText = "";
        private long statsAt;

        private String stats() {
            long nowMs = android.os.SystemClock.uptimeMillis();
            if (nowMs - statsAt < 4500 && statsText.length() > 0) return statsText;
            statsAt = nowMs;
            actMgr.getMemoryInfo(memInfo);
            double total = memInfo.totalMem / 1073741824.0;
            double used = (memInfo.totalMem - memInfo.availMem) / 1073741824.0;
            int pct = (int) Math.round(100.0 * (memInfo.totalMem - memInfo.availMem) / memInfo.totalMem);
            statsText = String.format(Locale.US, "RAM %.1f / %.1f GB  %d%%", used, total, pct);
            return statsText;
        }

        private int tileAlpha = 255;
        private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        private void drawTile(Canvas c, Tile t, float x, float y, int tw, int th, boolean sel) {
            sel = sel && zone == Z_GRID;
            float s = sel ? 1.1f : 1f;
            float cx = x + tw / 2f, cy = y + th / 2f;
            float rad = th * 0.20f;
            rf.set(cx - tw * s / 2, cy - th * s / 2, cx + tw * s / 2, cy + th * s / 2);
            float ta = tileAlpha / 255f;

            // the tile floats above the glass: soft shadow, deeper and lower when focused
            // shadows only on the home screen; they fade out as the grid opens and are skipped while it is open
            float sa = ta * Math.max(0f, 1f - p * 2.5f);
            if (sa > 0.02f) shadow(c, rf, rad, sel ? 0x88 : 0x58, sa, sel ? 0.20f : 0.10f);

            if (t.shader != null) {
                mx.reset();
                mx.setScale(s, s);
                mx.postTranslate(rf.left, rf.top);
                t.shader.setLocalMatrix(mx);
                fill.setShader(t.shader);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(Color.WHITE);
            } else {
                fill.setShader(null);
                fill.setColor(0xFF2A2A2E);
            }
            fill.setAlpha(tileAlpha);
            c.drawRoundRect(rf, rad, rad, fill);

            // gentle shading toward the bottom so the tile reads as a rounded slab
            if (bottomShadeS == null) {
                bottomShadeS = new LinearGradient(0, 0, 0, 1, new int[]{0x00000000, 0x00000000, 0x40000000},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
                topRimS = new LinearGradient(0, 0, 0, 1, new int[]{0xB4FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x30FFFFFF},
                        new float[]{0f, 0.28f, 0.8f, 1f}, Shader.TileMode.CLAMP);
            }
            gm.reset();
            gm.setScale(1f, rf.height());
            gm.postTranslate(0f, rf.top);
            bottomShadeS.setLocalMatrix(gm);
            fill.setShader(bottomShadeS);
            fill.setAlpha(tileAlpha);
            c.drawRoundRect(rf, rad, rad, fill);

            // light catching the top edge, and a trace of bounce light at the bottom
            topRimS.setLocalMatrix(gm);
            fill.setShader(topRimS);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(1.6f, th * 0.018f));
            fill.setAlpha(tileAlpha);
            c.drawRoundRect(rf, rad, rad, fill);
            fill.setShader(null);
            fill.setStyle(Paint.Style.FILL);
            fill.setAlpha(255);
            if (sel) {
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(th * 0.032f);
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(rf, rad, rad, fill);
                fill.setStyle(Paint.Style.FILL);
            }
        }

        // ---- grid background: one solid colour taken from the wallpaper (cheap, fully opaque)
        private int domRaw = 0;

        /** Most common colour in the picture: sample on a grid, bucket to 4 bits per channel, average the winning bucket. */
        private int dominant(Bitmap b) {
            int w = b.getWidth(), h = b.getHeight();
            int stepX = Math.max(1, w / 48), stepY = Math.max(1, h / 27);
            int[] cnt = new int[4096];
            long[] sr = new long[4096], sg = new long[4096], sb = new long[4096];
            for (int y = 0; y < h; y += stepY) {
                for (int x = 0; x < w; x += stepX) {
                    int px = b.getPixel(x, y);
                    int r = Color.red(px), g = Color.green(px), bl = Color.blue(px);
                    int k = ((r >> 4) << 8) | ((g >> 4) << 4) | (bl >> 4);
                    cnt[k]++; sr[k] += r; sg[k] += g; sb[k] += bl;
                }
            }
            int best = 0;
            for (int k = 1; k < 4096; k++) if (cnt[k] > cnt[best]) best = k;
            if (cnt[best] == 0) return 0;
            return Color.rgb((int) (sr[best] / cnt[best]), (int) (sg[best] / cnt[best]), (int) (sb[best] / cnt[best]));
        }

        /** Dominant colour pulled toward black (dark theme) or white (light theme) so tiles stand out. */
        private int drawerColor() {
            int base = domRaw != 0 ? domRaw : PRESETS[wp % PRESETS.length][0];
            return dark ? mix(base, 0xFF000000, 0.62f) : mix(base, 0xFFFFFFFF, 0.72f);
        }

        private void drawBackground(Canvas c, int w, int h) {
            if (p >= 0.99f) {   // grid fully open: one opaque fill, no wallpaper, no blending
                c.drawColor(drawerColor());
                return;
            }
            if (wall != null) {   // centre-crop to fill
                int bw = wall.getWidth(), bh = wall.getHeight();
                float sc = Math.max(w / (float) bw, h / (float) bh);
                int sw = (int) (w / sc), sh = (int) (h / sc);
                src.set((bw - sw) / 2, (bh - sh) / 2, (bw - sw) / 2 + sw, (bh - sh) / 2 + sh);
                rf.set(0, 0, w, h);
                c.drawBitmap(wall, src, rf, wallPaint);
            } else {
                if (dim == null || dimW != w || dimH != h) {
                    int[] pr = PRESETS[wp % PRESETS.length];
                    int c0 = pr[0], c1 = pr[1];
                    if (!dark) { c0 = mix(c0, 0xFFFFFFFF, 0.78f); c1 = mix(c1, 0xFFFFFFFF, 0.5f); }
                    dim = new LinearGradient(0, 0, w, h, c0, c1, Shader.TileMode.CLAMP);
                    dimW = w; dimH = h;
                }
                fill.setShader(dim);
                c.drawRect(0, 0, w, h, fill);
                fill.setShader(null);
            }
            // darken as we move into browsing mode, and a little at the bottom for legibility
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(dark ? Color.argb(40, 0, 0, 0) : Color.argb(20, 255, 255, 255));
            c.drawRect(0, 0, w, h, fill);
            if (p > 0.01f) {   // fade the wallpaper into the solid drawer colour as the grid opens
                fill.setColor(withA(drawerColor(), (int) (255 * p)));
                c.drawRect(0, 0, w, h, fill);
            }
        }

        private int mix(int a, int b, float t) {
            int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
            int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
            int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
            return Color.rgb(r, g, bl);
        }
    }
}
