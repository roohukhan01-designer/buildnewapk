package com.roohu.clipforge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Optional OpenRouter helper (free models, OpenAI-compatible API). Same job as GeminiClient: the page sends a prompt and a JSON schema,
 * this returns the model's JSON text. Only the caption text (and, for models that can see, 4 small pictures) is sent, never the video or audio.
 * The key lives in SecureStore and goes in the Authorization header, never in a URL.
 *
 * Free limits (OpenRouter docs): every model id ending in ":free" shares 20 requests a minute and 50 a day (1,000 a day after the account has
 * bought credits). So this client never retries a limit answer, tries at most 3 models per request, and caches every answer on the phone.
 */
final class OpenRouterClient {
    private static final String BASE = "https://openrouter.ai/api/v1/";
    private static final String PREFS = "openrouter";
    private static final long LIST_TTL_MS = 6L * 3600 * 1000;
    private static final int MAX_INPUT_CHARS = 60000;
    private static final int MAX_MODELS_TRIED = 3;

    /** One free model as shown in Settings. */
    static final class Model {
        final String id, name; final boolean vision; final long context;
        Model(String id, String name, boolean vision, long context) { this.id = id; this.name = name; this.vision = vision; this.context = context; }
    }

    /** Free models for the Settings list, best guess first (can see pictures, then long context). Cached for 6 hours. Call off the UI thread. */
    static JSONArray freeModelsJson(Context ctx, String key, boolean refresh) throws Exception {
        JSONArray out = new JSONArray();
        for (Model m : freeModels(ctx, key, refresh)) out.put(new JSONObject().put("id", m.id).put("name", m.name).put("vision", m.vision));
        return out;
    }

    private static synchronized List<Model> freeModels(Context ctx, String key, boolean refresh) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = sp.getString("list", null);
        String raw = null;
        if (!refresh && saved != null && System.currentTimeMillis() - sp.getLong("at", 0) < LIST_TTL_MS) raw = saved;
        if (raw == null) {
            raw = http("GET", BASE + "models", key, null);
            sp.edit().putString("list", raw).putLong("at", System.currentTimeMillis()).apply();
        }
        JSONArray data = new JSONObject(raw).optJSONArray("data");
        List<Model> list = new ArrayList<>();
        if (data != null) for (int i = 0; i < data.length(); i++) {
            JSONObject m = data.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id", "");
            JSONObject pr = m.optJSONObject("pricing");
            boolean free = id.endsWith(":free") || (pr != null && isZero(pr.optString("prompt", "x")) && isZero(pr.optString("completion", "x")));
            if (!free || id.isEmpty()) continue;
            JSONObject ar = m.optJSONObject("architecture");
            JSONArray in = ar == null ? null : ar.optJSONArray("input_modalities");
            JSONArray outm = ar == null ? null : ar.optJSONArray("output_modalities");
            boolean vision = false, textOut = outm == null, textIn = in == null;
            if (in != null) for (int k = 0; k < in.length(); k++) { if ("image".equals(in.optString(k))) vision = true; if ("text".equals(in.optString(k))) textIn = true; }
            if (outm != null) for (int k = 0; k < outm.length(); k++) if ("text".equals(outm.optString(k))) textOut = true;
            if (!textIn || !textOut) continue;
            if (id.matches(".*(embed|moderation|guard|whisper|tts|image-preview).*")) continue;
            list.add(new Model(id, m.optString("name", id), vision, m.optLong("context_length", 0)));
        }
        Collections.sort(list, (a, b) -> {
            if (a.vision != b.vision) return a.vision ? -1 : 1;
            return Long.compare(b.context, a.context);
        });
        return list;
    }

    private static boolean isZero(String s) {
        try { return Double.parseDouble(s.trim()) == 0.0; } catch (Exception e) { return false; }
    }

    /**
     * Asks for JSON that matches `schema` and returns the JSON text. pick = "auto" (try the best free models in turn) or one model id.
     * Call off the UI thread.
     */
    static String generateJson(Context ctx, String key, String pick, String system, String user, JSONObject schema, JSONArray images) throws Exception {
        if (user.length() > MAX_INPUT_CHARS) user = user.substring(0, MAX_INPUT_CHARS);
        final boolean wantArray = "array".equals(schema.optString("type"));
        final boolean haveImages = images != null && images.length() > 0;

        List<Model> order = new ArrayList<>();
        if (pick != null && !pick.isEmpty() && !"auto".equals(pick)) {
            boolean vis = false;
            try { for (Model m : freeModels(ctx, key, false)) if (m.id.equals(pick)) vis = m.vision; } catch (Exception ignored) { }
            order.add(new Model(pick, pick, vis, 0));
        } else {
            List<Model> all = freeModels(ctx, key, false);
            for (Model m : all) { if (!haveImages || m.vision) order.add(m); if (order.size() >= MAX_MODELS_TRIED) break; }
            if (order.isEmpty()) for (Model m : all) { order.add(m); if (order.size() >= MAX_MODELS_TRIED) break; }
        }
        if (order.isEmpty()) throw new IOException("OpenRouter lists no free models for this key right now.");

        String sys = system + "\n\nReply with JSON only: no markdown, no explanation, no text before or after. The JSON must match this JSON schema:\n" + schema;
        File cf = cacheFile(ctx, sys + "|" + user + "|" + (haveImages ? images.toString().hashCode() : 0) + "|" + pick);
        if (cf.isFile()) {
            try { return new String(java.nio.file.Files.readAllBytes(cf.toPath()), StandardCharsets.UTF_8); } catch (Exception ignored) { cf.delete(); }
        }

        Exception last = null;
        int tried = 0;
        for (Model m : order) {
            if (tried++ >= MAX_MODELS_TRIED) break;
            try {
                JSONArray content = new JSONArray();
                String u = user;
                if (haveImages && m.vision) {
                    for (int i = 0; i < images.length(); i++)
                        content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + images.getString(i))));
                } else if (haveImages) {
                    u += "\n\n(No pictures are available for this request. Where the schema asks about the pictures, return an empty array.)";
                }
                content.put(new JSONObject().put("type", "text").put("text", u));
                JSONObject body = new JSONObject();
                body.put("model", m.id);
                body.put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", sys))
                        .put(new JSONObject().put("role", "user").put("content", content)));
                body.put("temperature", 0.4);
                body.put("max_tokens", 4096);
                String raw = Net.retry(() -> http("POST", BASE + "chat/completions", key, body.toString()), 1);   // no retry: a limit answer must not eat the daily quota
                String text = extractAnswer(raw, wantArray);
                try {
                    File tmp = new File(cf.getPath() + ".tmp");
                    java.nio.file.Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
                    if (!tmp.renameTo(cf)) tmp.delete();
                    File[] all = cf.getParentFile().listFiles();
                    if (all != null && all.length > 40) {
                        java.util.Arrays.sort(all, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
                        for (int i = 0; i < all.length - 40; i++) all[i].delete();
                    }
                } catch (Exception ignored) { }
                return text;
            } catch (Net.HttpFail f) {
                if (f.code == 401 || f.code == 402 || f.code == 403) throw f;           // key problems: another model will not help
                if (f.code == 429 && f.getMessage() != null && f.getMessage().contains("daily limit")) throw f;   // shared by all free models
                last = f;
            } catch (IOException ex) {
                last = ex;                                                              // empty or unreadable answer: try the next model
            }
        }
        throw last != null ? last : new IOException("OpenRouter returned no answer.");
    }

    /** Pulls the JSON out of the model's reply: drops <think> blocks and code fences, checks it parses and has the right shape. */
    private static String extractAnswer(String raw, boolean wantArray) throws Exception {
        JSONObject j = new JSONObject(raw);
        JSONObject err = j.optJSONObject("error");
        if (err != null) throw new Net.HttpFail(err.optInt("code", 502), "OpenRouter: " + err.optString("message", "request failed"), 0);
        JSONArray ch = j.optJSONArray("choices");
        if (ch == null || ch.length() == 0) throw new IOException("OpenRouter returned no answer.");
        JSONObject msg = ch.getJSONObject(0).optJSONObject("message");
        String t = msg == null ? "" : msg.optString("content", "");
        if (t == null || t.trim().isEmpty() || "null".equals(t)) throw new IOException("OpenRouter returned an empty answer.");
        t = t.replaceAll("(?s)<think>.*?</think>", "").replace("```json", "").replace("```", "").trim();
        int s = -1;
        for (int i = 0; i < t.length(); i++) { char c = t.charAt(i); if (c == '[' || c == '{') { s = i; break; } }
        if (s < 0) throw new IOException("The model did not answer with JSON.");
        Object v;
        try { v = new JSONTokener(t.substring(s)).nextValue(); } catch (Exception e) { throw new IOException("The model's JSON was cut off or broken."); }
        if (wantArray) {
            if (v instanceof JSONObject) {   // some models wrap the list: {"clips":[...]}
                JSONObject o = (JSONObject) v;
                JSONArray found = null; int n = 0;
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) { Object x = o.opt(it.next()); if (x instanceof JSONArray) { found = (JSONArray) x; n++; } }
                if (n == 1) v = found;
            }
            if (!(v instanceof JSONArray)) throw new IOException("The model answered with the wrong JSON shape.");
        } else if (!(v instanceof JSONObject)) {
            throw new IOException("The model answered with the wrong JSON shape.");
        }
        return v.toString();
    }

    private static String http(String method, String url, String key, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        if (key != null && !key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Title", "Roohu ClipForge");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code >= 400) {
            String msg;
            try { msg = new JSONObject(resp).getJSONObject("error").optString("message", ""); } catch (Exception e) { msg = ""; }
            if (code == 401 || code == 403) throw new Net.HttpFail(code, "The OpenRouter key was not accepted. Check it in Settings.", 0);
            if (code == 402) throw new Net.HttpFail(code, "OpenRouter says this key has no credits for that model. Pick a model that ends in :free.", 0);
            if (code == 429) {
                String low = (resp + " " + msg).toLowerCase();
                boolean daily = low.contains("per-day") || low.contains("per day") || low.contains("free-models-per-day");
                throw new Net.HttpFail(code, daily
                        ? "The OpenRouter free daily limit is used up (50 free requests a day, 1,000 after buying credits). It resets daily. Try again later, or switch the AI provider to Gemini in Settings."
                        : "The free OpenRouter model is busy or at its per-minute limit. Wait a minute and try again.", 0);
            }
            throw new Net.HttpFail(code, "OpenRouter request failed (" + code + ")" + (msg.isEmpty() ? "." : ": " + msg.substring(0, Math.min(160, msg.length()))), 0);
        }
        return resp;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    private static File cacheFile(Context ctx, String material) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(material.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
        File dir = new File(ctx.getCacheDir(), "openrouter");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, sb + ".json");
    }
}
