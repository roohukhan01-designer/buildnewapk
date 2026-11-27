package com.roohu.clipforge;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.provider.OpenableColumns;
import android.view.WindowManager;
import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.PermissionRequest;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;
import androidx.media3.common.util.UnstableApi;

public class MainActivity extends ComponentActivity {
    private static final String MAIN_URL = "https://appassets.androidplatform.net/assets/Roohu-ClipForge.html";
    private static final long MIN_SPLASH_MS = 900;

    private WebView web;
    private View splash, glow, logo, title, tag, loader;
    private ValueCallback<Uri[]> chooserCb;
    private boolean splashGone = false;
    private long t0;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Animator> loops = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService netWorker = Executors.newSingleThreadExecutor();
    private final AtomicInteger busy = new AtomicInteger();
    private Uri videoUri;
    private Uri camUri;
    private volatile String pickTarget = "main";
    private final Map<String, Uri> audioUris = new HashMap<>();
    private String chooserAccept = "";
    private ClipExporter exporter;
    private static final String APP_HOST = "appassets.androidplatform.net";
    /** True only while the page shown is this app's own page. Everything sensitive in the bridge checks it. */
    private volatile boolean trusted = false;

    private static boolean isAppUrl(String url) {
        try { Uri u = Uri.parse(url); return "https".equals(u.getScheme()) && APP_HOST.equals(u.getHost()); }
        catch (Exception e) { return false; }
    }

    /** Only clips this app saved to the Gallery (MediaStore) or its own Movies folders may be opened, shared, uploaded or deleted by the page. */
    private boolean isOwnClip(String s) {
        try {
            if (s == null || s.length() > 2000) return false;
            Uri u = Uri.parse(s);
            if ("content".equals(u.getScheme())) return "media".equals(u.getAuthority());
            if ("file".equals(u.getScheme()) && u.getPath() != null) {
                File f = new File(u.getPath()).getCanonicalFile();
                File a = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "RoohuClipForge").getCanonicalFile();
                File dd = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                String p = f.getPath();
                return p.startsWith(a.getPath() + File.separator) || (dd != null && p.startsWith(dd.getCanonicalPath() + File.separator));
            }
        } catch (Exception ignored) { }
        return false;
    }

    private final ActivityResultLauncher<Intent> chooser = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (chooserCb == null) return;
                Uri[] res = WebChromeClient.FileChooserParams.parseResult(r.getResultCode(), r.getData());
                if (res != null && res.length > 0) {
                    if (chooserAccept.contains("video")) {
                        // Keep the SAF grant when the provider supports it. Export also makes a
                        // private, readable copy so Media3 never depends on a temporary picker URI.
                        Intent resultData = r.getData();
                        if (resultData != null && (resultData.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                            try { getContentResolver().takePersistableUriPermission(res[0],
                                    resultData.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); }
                            catch (SecurityException ignored) { }
                        }
                        final boolean camPick = "cam".equals(pickTarget);
                        if (camPick) camUri = res[0]; else videoUri = res[0];
                        netWorker.execute(() -> MediaCache.clear(MainActivity.this, camPick ? "camera" : "video"));
                    } else if (chooserAccept.contains("audio")) {
                        for (Uri u : res) copyToLib(u);
                    }
                }
                pickTarget = "main";
                chooserCb.onReceiveValue(res);
                chooserCb = null;
            });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        scanLib();
        setContentView(R.layout.activity_main);
        t0 = SystemClock.elapsedRealtime();
        web = findViewById(R.id.web);
        splash = findViewById(R.id.splash);
        glow = findViewById(R.id.glow);
        logo = findViewById(R.id.logo);
        title = findViewById(R.id.title);
        tag = findViewById(R.id.tag);
        loader = findViewById(R.id.loader);
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
        startSplash();
        setupWeb();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                web.evaluateJavascript("(window.cfBack&&cfBack())?'1':'0'", v -> {
                    if ("\"1\"".equals(v)) return;
                    if (web.canGoBack()) { web.goBack(); return; }
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                });
            }
        });
        web.loadUrl(MAIN_URL);
        ui.postDelayed(this::hideSplash, 8000); // failsafe: never trap the user behind the splash
    }

    private void setupWeb() {
        final WebViewAssetLoader assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/music/", new WebViewAssetLoader.InternalStoragePathHandler(this, musicDir())).build();
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setBackgroundColor(0xFF0A1A1F);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return assets.shouldInterceptRequest(r.getUrl());
            }
            @Override public void onPageStarted(WebView v, String u, android.graphics.Bitmap fav) { trusted = isAppUrl(u); }
            @Override public void onPageFinished(WebView v, String u) {
                long left = Math.max(0, MIN_SPLASH_MS - (SystemClock.elapsedRealtime() - t0));
                ui.postDelayed(MainActivity.this::hideSplash, left);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (isAppUrl(u.toString())) return false;
                String sc = u.getScheme();
                if ("https".equals(sc) || "http".equals(sc)) {   // other schemes (intent:, file:, javascript:) are never launched
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                }
                return true;
            }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                runOnUiThread(MainActivity.this::recreate);
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooserCb != null) chooserCb.onReceiveValue(null);
                chooserCb = cb;
                StringBuilder acc = new StringBuilder();
                for (String a : p.getAcceptTypes()) acc.append(a).append(',');
                chooserAccept = acc.toString();
                Intent i = p.createIntent();
                for (String a : p.getAcceptTypes()) {
                    if (a != null && a.startsWith(".")) { i.setType("*/*"); break; }
                }
                try { chooser.launch(i); }
                catch (Exception e) { chooserCb = null; cb.onReceiveValue(null); toast("No file picker is available on this device."); }
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest r) { r.deny(); }
        });
    }

    private void startSplash() {
        logo.setAlpha(0f); logo.setScaleX(0.8f); logo.setScaleY(0.8f);
        title.setAlpha(0f); title.setTranslationY(24f); tag.setAlpha(0f); loader.setAlpha(0f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(600).setInterpolator(new DecelerateInterpolator()).start();
        title.animate().alpha(1f).translationY(0f).setStartDelay(250).setDuration(500).start();
        tag.animate().alpha(1f).setStartDelay(450).setDuration(500).start();
        loader.animate().alpha(1f).setStartDelay(600).setDuration(400).start();
        ObjectAnimator g = ObjectAnimator.ofPropertyValuesHolder(glow,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.35f, 0.95f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.15f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.15f));
        g.setDuration(1400); g.setRepeatMode(ValueAnimator.REVERSE); g.setRepeatCount(ValueAnimator.INFINITE);
        g.setInterpolator(new AccelerateDecelerateInterpolator()); g.start(); loops.add(g);
    }

    private void hideSplash() {
        if (splashGone) return;
        splashGone = true;
        splash.animate().alpha(0f).setDuration(350).withEndAction(() -> {
            splash.setVisibility(View.GONE);
            for (Animator a : loops) a.cancel();
            loops.clear();
        }).start();
    }

    private void toast(String m) { runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }

    /** Receives exported clips from the page in chunks and writes them to Downloads/RoohuClipForge. */
    /** Songs the user added live in the app's own storage so they stay after the app closes. */
    private File musicDir() { File d = new File(getFilesDir(), "music"); d.mkdirs(); return d; }

    /**
     * Media3's Transformer needs a stable, directly readable source while rendering.
     * WebView file-picker URIs can be temporary or provider-backed; make one cached
     * private copy for content:// sources before handing them to the asset loader.
     */
    private Uri localizeMediaUri(Uri uri, String label) throws IOException {
        return MediaCache.localize(this, uri, label);
    }

    private void copyToLib(Uri u) {
        String name = displayName(u).replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("^\\.+", "").trim();
        if (name.isEmpty()) name = "song_" + (System.currentTimeMillis() % 100000) + ".mp3";
        if (name.length() > 120) name = name.substring(name.length() - 120);
        File dst = new File(musicDir(), name);
        try (InputStream in = getContentResolver().openInputStream(u); OutputStream os = new FileOutputStream(dst)) {
            if (in == null) return;
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            audioUris.put(name, Uri.fromFile(dst));
        } catch (Exception ignored) { }
    }

    private void scanLib() {
        File[] fs = musicDir().listFiles();
        if (fs != null) for (File f : fs) audioUris.put(f.getName(), Uri.fromFile(f));
    }

    private String displayName(Uri u) {
        String n = null;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignored) { }
        return n == null ? String.valueOf(u.getLastPathSegment()) : n;
    }

    private void js(final String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private void keepAwake(boolean on) {
        runOnUiThread(() -> {
            if (on) { busy.incrementAndGet(); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
            else if (busy.decrementAndGet() <= 0) { busy.set(0); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
        });
    }

    private void reply(int id, JSONObject o) { js("cfNative.onResult(" + id + "," + JSONObject.quote(o.toString()) + ")"); }

    private void fail(int id, String msg) {
        try { reply(id, new JSONObject().put("ok", false).put("error", msg)); } catch (Exception ignored) { }
    }

    /** Failure with a readable explanation: what failed and where, never just the bare exception name. */
    private void fail(int id, Throwable t) { fail(id, Diag.describe(t)); }

    private class Bridge {
        /** Native features available (offline captions, hardware export, gallery). */
        private boolean secretName(String n) { return "groq".equals(n) || "gemini".equals(n) || "openrouter".equals(n) || "yt_id".equals(n) || "yt_secret".equals(n) || "ytKey".equals(n); }
        @JavascriptInterface public String getSecret(String name) { return trusted && secretName(name) ? SecureStore.get(MainActivity.this, name) : ""; }
        @JavascriptInterface public boolean setSecret(String name, String value) { return trusted && secretName(name) && SecureStore.set(MainActivity.this, name, value); }
        /** Optional Gemini call: the page sends the prompt and schema; the key never leaves the encrypted store. */
        @JavascriptInterface public void gemini(final String req) {
            if (!trusted) return;
            final JSONObject q;
            try { q = new JSONObject(req); } catch (Exception e) { return; }
            final int id = q.optInt("id");
            worker.execute(() -> {
                try {
                    // Which AI answers: "gemini" (default), "openrouter" (free models), or "both" (Gemini first, OpenRouter when Gemini is out).
                    String prov = q.optString("prov", "gemini");
                    String gKey = SecureStore.get(MainActivity.this, "gemini"), oKey = SecureStore.get(MainActivity.this, "openrouter");
                    String sys = q.optString("system"), usr = q.optString("user"), pick = q.optString("orModel", "auto");
                    JSONObject schema = q.getJSONObject("schema");
                    JSONArray imgs = q.optJSONArray("images");
                    String text;
                    if ("openrouter".equals(prov)) {
                        if (oKey.isEmpty()) throw new Exception("Paste your OpenRouter key in Settings first.");
                        text = OpenRouterClient.generateJson(MainActivity.this, oKey, pick, sys, usr, schema, imgs);
                    } else if ("both".equals(prov)) {
                        if (gKey.isEmpty() && oKey.isEmpty()) throw new Exception("Paste a Gemini or OpenRouter key in Settings first.");
                        if (gKey.isEmpty()) {
                            text = OpenRouterClient.generateJson(MainActivity.this, oKey, pick, sys, usr, schema, imgs);
                        } else {
                            try {
                                text = GeminiClient.generateJson(MainActivity.this, gKey, sys, usr, schema, imgs);
                            } catch (Exception ge) {
                                boolean limit = ge instanceof Net.HttpFail && (((Net.HttpFail) ge).code == 429 || ((Net.HttpFail) ge).code >= 500);
                                if (!limit || oKey.isEmpty()) throw ge;
                                try {
                                    text = OpenRouterClient.generateJson(MainActivity.this, oKey, pick, sys, usr, schema, imgs);
                                } catch (Exception oe) {
                                    throw new Exception("Both AIs are unavailable. Gemini: " + ge.getMessage() + " OpenRouter: " + oe.getMessage());
                                }
                            }
                        }
                    } else {
                        if (gKey.isEmpty()) throw new Exception("Paste your Gemini key in Settings first.");
                        text = GeminiClient.generateJson(MainActivity.this, gKey, sys, usr, schema, imgs);
                    }
                    reply(id, new JSONObject().put("ok", true).put("text", text));
                } catch (Throwable t) {
                    fail(id, t);
                }
            });
        }
        /** Settings > OpenRouter: the free models this key can use. */
        @JavascriptInterface public void orModels(final String req) {
            if (!trusted) return;
            final JSONObject q;
            try { q = new JSONObject(req); } catch (Exception e) { return; }
            final int id = q.optInt("id");
            worker.execute(() -> {
                try {
                    String key = SecureStore.get(MainActivity.this, "openrouter");
                    reply(id, new JSONObject().put("ok", true).put("models", OpenRouterClient.freeModelsJson(MainActivity.this, key, true)));
                } catch (Throwable t) {
                    fail(id, t);
                }
            });
        }
        /** Music Finder: songs on YouTube through the official API (list + preview only, no downloading). */
        @JavascriptInterface public void musicSearch(final String req) {
            if (!trusted) return;
            final JSONObject q;
            try { q = new JSONObject(req); } catch (Exception e) { return; }
            final int id = q.optInt("id");
            netWorker.execute(() -> {
                try {
                    String key = SecureStore.get(MainActivity.this, "ytKey");
                    if (key.isEmpty()) throw new Exception("Paste your free YouTube API key in the Music section first.");
                    reply(id, new JSONObject().put("ok", true).put("items", MusicSearch.search(key, q.optString("q"), q.optInt("max", 8), q.optBoolean("cc", false))));
                } catch (Throwable t) { fail(id, t); }
            });
        }
        /** YouTube: sign in through Google in the browser (refresh token stays in SecureStore), then upload finished clips. */
        @JavascriptInterface public String ytStatus() { return YouTubeClient.connected(MainActivity.this) ? "1" : "0"; }
        @JavascriptInterface public void ytDisconnect() { YouTubeClient.disconnect(MainActivity.this); }
        @JavascriptInterface public void ytConnect(final String req) {
            if (!trusted) return;
            final JSONObject q;
            try { q = new JSONObject(req); } catch (Exception e) { return; }
            final int id = q.optInt("id");
            netWorker.execute(() -> {
                try {
                    YouTubeClient.connect(MainActivity.this, u -> runOnUiThread(() -> startActivity(new Intent(Intent.ACTION_VIEW, u))));
                    reply(id, new JSONObject().put("ok", true));
                } catch (Throwable t) { fail(id, t); }
            });
        }
        @JavascriptInterface public void ytUpload(final String req) {
            if (!trusted) return;
            final JSONObject q;
            try { q = new JSONObject(req); } catch (Exception e) { return; }
            final int id = q.optInt("id");
            netWorker.execute(() -> {
                keepAwake(true);
                try {
                    if (!isOwnClip(q.getString("uri"))) throw new Exception("Only clips made by ClipForge can be uploaded.");
                    String vid = YouTubeClient.upload(MainActivity.this, Uri.parse(q.getString("uri")), q.optString("title"), q.optString("desc"),
                            q.optJSONArray("tags"), q.optString("privacy"), p -> js("cfNative.onProgress(" + id + "," + p + ")"));
                    reply(id, new JSONObject().put("ok", true).put("videoId", vid));
                } catch (Throwable t) { fail(id, t); }
                finally { keepAwake(false); }
            });
        }
        @JavascriptInterface public void setPickTarget(String t) { pickTarget = "cam".equals(t) ? "cam" : "main"; }
        @JavascriptInterface public boolean hasCam() { return camUri != null; }
        @JavascriptInterface public void clearCam() { camUri = null; }
        @JavascriptInterface public String musicLib() {
            JSONArray a = new JSONArray();
            File[] fs = musicDir().listFiles();
            if (fs != null) { java.util.Arrays.sort(fs); for (File f : fs) a.put(f.getName()); }
            return a.toString();
        }
        @JavascriptInterface public void musicRemove(String name) {
            String n = new File(name == null ? "" : name).getName();
            if (!n.isEmpty()) { new File(musicDir(), n).delete(); audioUris.remove(n); }
        }
        @JavascriptInterface public String nativeInfo() { return "{\"ok\":true,\"video\":" + (videoUri != null) + "}"; }

        /** Offline speech-to-text with per-word timing for one clip. Result arrives through cfNative.onResult. */
        @JavascriptInterface public void transcribe(final String req) {
            final int id;
            final JSONObject q;
            try { q = new JSONObject(req); id = q.getInt("id"); } catch (Exception e) { return; }
            final Uri src = videoUri;
            if (src == null) { fail(id, "Pick the video again so the app can read its audio."); return; }
            keepAwake(true);
            worker.execute(() -> {
                try {
                    JSONObject r = Transcriber.run(MainActivity.this, src, q.getDouble("start"), q.getDouble("end"),
                            q.optString("lang", "auto"), q.optString("key", ""), p -> js("cfNative.onProgress(" + id + "," + p + ")"), q.optBoolean("energy"));
                    r.put("ok", true);
                    reply(id, r);
                } catch (Throwable e) {
                    fail(id, e);
                } finally { keepAwake(false); }
            });
        }

        /** Renders one clip with the hardware encoder and saves it to Movies/RoohuClipForge (Gallery). */
        @JavascriptInterface @UnstableApi public void exportClip(final String specJson) {
            final int id;
            final JSONObject spec;
            try { spec = new JSONObject(specJson); id = spec.getInt("id"); } catch (Exception e) { return; }
            final Uri src = videoUri;
            if (src == null) { fail(id, "Pick the video again so the app can read it."); return; }
            keepAwake(true);
            worker.execute(() -> {
                Uri music = null;
                try {
                    if (("react".equals(spec.optString("look", "normal")) || "neon".equals(spec.optString("look", "normal"))) && !spec.has("punches")) {
                        double[] pk = Energy.punches(MainActivity.this, src, spec.getDouble("s"), spec.getDouble("e"));
                        JSONArray pa = new JSONArray();
                        for (double v : pk) pa.put(v);
                        spec.put("punches", pa);
                    }
                } catch (Throwable ignored) { }
                try {
                    JSONObject m = spec.optJSONObject("music");
                    if (m != null) {
                        String type = m.optString("type", "none");
                        if ("file".equals(type)) music = audioUris.get(m.optString("name"));
                        else if ("synth".equals(type)) {
                            File f = new File(getCacheDir(), "music2_" + m.optString("mood", "upbeat") + ".wav");
                            int secs = (int) Math.ceil(spec.getDouble("e") - spec.getDouble("s")) + 2;
                            if (!f.exists() || f.length() < secs * 64000L) MusicSynth.generate(f, m.optString("mood", "upbeat"), secs + 8);
                            music = Uri.fromFile(f);
                        }
                    }
                } catch (Throwable e) { music = null; }
                final Uri musicF = music;
                final Uri cameraSrc = camUri;
                try {
                    // Resolve picker-backed media into app-private files before Media3 opens it.
                    final Uri exportSrc = localizeMediaUri(src, "video");
                    final Uri exportCam = cameraSrc == null ? null : localizeMediaUri(cameraSrc, "camera");
                    runOnUiThread(() -> {
                        exporter = new ClipExporter();
                        exporter.start(MainActivity.this, exportSrc, spec, musicF, exportCam, new ClipExporter.Listener() {
                            @Override public void progress(int pct) { js("cfNative.onProgress(" + id + "," + pct + ")"); }
                            @Override public void done(final File file) {
                                worker.execute(() -> {
                                    try {
                                        Uri u = ClipExporter.publish(MainActivity.this, file, safeName(spec.optString("fname", spec.optString("title", "clip"))) + "-" + System.currentTimeMillis() % 100000);
                                        reply(id, new JSONObject().put("ok", true).put("uri", u.toString()));
                                    } catch (Throwable e) { fail(id, e); }
                                    finally { keepAwake(false); }
                                });
                            }
                            @Override public void error(String message) { fail(id, message); keepAwake(false); }
                        });
                    });
                } catch (Throwable e) {
                    fail(id, e);
                    keepAwake(false);
                }
            });
        }

        @JavascriptInterface public void cancelExport() {
            runOnUiThread(() -> { if (exporter != null) exporter.cancel(); });
        }

        /** Small JPEG frames of the video at the given times (seconds), as base64, so the AI can look at the picture. */
        @JavascriptInterface public void frames(final String req) {
            final JSONObject q;
            try { q = new JSONObject(req); } catch (Exception e) { return; }
            final int id = q.optInt("id");
            worker.execute(() -> {
                MediaMetadataRetriever r = null;
                try {
                    if (videoUri == null) throw new Exception("Pick the video again so the app can read it.");
                    r = MediaCache.retriever(MainActivity.this, videoUri);
                    JSONArray ts = q.getJSONArray("times"), out = new JSONArray();
                    for (int i = 0; i < ts.length(); i++) {
                        Bitmap b = r.getFrameAtTime((long) (ts.getDouble(i) * 1_000_000), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (b == null) continue;
                        int w = 448, h = Math.max(1, Math.round(b.getHeight() * 448f / b.getWidth()));
                        Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        s.compress(Bitmap.CompressFormat.JPEG, 72, bo);
                        out.put(android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP));
                    }
                    reply(id, new JSONObject().put("ok", true).put("frames", out));
                } catch (Throwable t) {
                    fail(id, t);
                } finally { try { if (r != null) r.release(); } catch (Exception ignored) { } }
            });
        }

        /** Face tracking (M24): where the main face is during the clip. Runs fully on the phone, nothing is uploaded and no Gemini limit is used. */
        @JavascriptInterface public void faceTrack(final String req) {
            final JSONObject q;
            try { q = new JSONObject(req); } catch (Exception e) { return; }
            final int id = q.optInt("id");
            worker.execute(() -> {
                try {
                    if (videoUri == null) throw new Exception("Pick the video again so the app can read it.");
                    JSONArray raw = FaceTrack.detect(MainActivity.this, videoUri, q.getDouble("start"), q.getDouble("end"), q.optDouble("step", 1.0));
                    reply(id, new JSONObject().put("ok", true).put("track", raw));
                } catch (Throwable t) {
                    fail(id, t);
                }
            });
        }

        @JavascriptInterface public String thumb(String uri) {
            if (!trusted || !isOwnClip(uri)) return "";
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(MainActivity.this, Uri.parse(uri));
                Bitmap b = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (b == null) return "";
                Bitmap s = Bitmap.createScaledBitmap(b, 216, 384, true);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                s.compress(Bitmap.CompressFormat.JPEG, 70, bo);
                return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) { return ""; } finally { try { r.release(); } catch (Exception ignored) { } }
        }

        @JavascriptInterface public void openVideo(String uri) {
            if (!trusted || !isOwnClip(uri)) { toast("That clip can no longer be opened."); return; }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
            } catch (Exception e) { toast("No video player found."); }
        }

        @JavascriptInterface public void shareVideo(String uri) {
            if (!trusted || !isOwnClip(uri)) { toast("That clip can no longer be shared."); return; }
            try {
                Intent i = new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Share clip"));
            } catch (Exception e) { toast("Could not open the share sheet."); }
        }

        @JavascriptInterface public boolean deleteVideo(String uri) {
            if (!trusted || !isOwnClip(uri)) return false;
            try {
                Uri u = Uri.parse(uri);
                if ("file".equals(u.getScheme())) return new File(u.getPath()).delete();
                return getContentResolver().delete(u, null, null) > 0;
            } catch (Exception e) { return false; }
        }

        private OutputStream os; private Uri uri; private File file; private String shown = "";

        @JavascriptInterface public synchronized boolean begin(String fn, String mime) {
            if (!trusted) return false;
            closeQuiet(true);
            try {
                String name = fn == null ? "clip" : fn.replaceAll("[^A-Za-z0-9._ -]", "_");
                if (name.isEmpty()) name = "clip";
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RoohuClipForge");
                    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return false;
                    os = getContentResolver().openOutputStream(uri);
                    shown = "Downloads/RoohuClipForge/" + name;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                    if (dir == null) return false;
                    file = new File(dir, name);
                    os = new FileOutputStream(file);
                    shown = file.getAbsolutePath();
                }
                return os != null;
            } catch (Exception e) { closeQuiet(true); return false; }
        }
        @JavascriptInterface public synchronized boolean chunk(String b64) {
            try { os.write(Base64.decode(b64, Base64.DEFAULT)); return true; }
            catch (Exception e) { closeQuiet(true); return false; }
        }
        @JavascriptInterface public synchronized boolean end() {
            try {
                os.flush(); os.close(); os = null;
                if (uri != null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(uri, v, null, null);
                }
                toast("Saved to " + shown);
                uri = null; file = null;
                return true;
            } catch (Exception e) { closeQuiet(true); return false; }
        }
        @JavascriptInterface public synchronized void abort() { closeQuiet(true); }

        private void closeQuiet(boolean deletePartial) {
            try { if (os != null) os.close(); } catch (Exception ignored) { }
            os = null;
            try {
                if (deletePartial) {
                    if (uri != null) getContentResolver().delete(uri, null, null);
                    if (file != null) file.delete();
                }
            } catch (Exception ignored) { }
            uri = null; file = null;
        }
    }

    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        for (Animator a : loops) a.cancel();
        if (web != null) {
            ViewGroup p = (ViewGroup) web.getParent();
            if (p != null) p.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Last line of defence for file names (the page already sanitises): no paths, reserved characters or empty names. */
    static String safeName(String n) {
        String r = n == null ? "" : n.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").replaceAll("^\\.+|\\.+$", "").trim();
        if (r.length() > 80) r = r.substring(0, 80).trim();
        return r.isEmpty() ? "clip" : r;
    }
}
