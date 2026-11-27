package com.roohu.clipforge;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * YouTube upload over the official APIs. Sign-in is Google's OAuth 2.0 loopback flow with PKCE: the phone's browser shows
 * Google's own page, the app never sees the password. Only the refresh token is kept, in SecureStore (Keystore AES-GCM).
 * Upload is the resumable protocol, sent in 4 MB chunks straight from the clip's content URI.
 */
final class YouTubeClient {
    interface Opener { void open(Uri url); }
    interface Progress { void pct(int p); }

    private static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/youtube.upload";
    private static final int CHUNK = 16 * 256 * 1024;

    private static final class Resp { int code; String body = ""; String range; }

    static boolean connected(Context ctx) { return !SecureStore.get(ctx, "yt_refresh").isEmpty(); }
    static void disconnect(Context ctx) { SecureStore.set(ctx, "yt_refresh", ""); }

    /** Opens Google sign-in in the browser and waits (up to 3 minutes) for the answer on a one-time local page. */
    static void connect(Context ctx, Opener opener) throws Exception {
        String id = SecureStore.get(ctx, "yt_id").trim(), secret = SecureStore.get(ctx, "yt_secret").trim();
        if (id.isEmpty() || secret.isEmpty()) throw new Exception("Paste your YouTube Client ID and Client secret in Settings first.");
        String verifier = rand(32), challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))), state = rand(16);
        try (ServerSocket ss = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            ss.setSoTimeout(180000);
            String redirect = "http://127.0.0.1:" + ss.getLocalPort();
            Uri u = Uri.parse(AUTH).buildUpon().appendQueryParameter("client_id", id).appendQueryParameter("redirect_uri", redirect)
                    .appendQueryParameter("response_type", "code").appendQueryParameter("scope", SCOPE)
                    .appendQueryParameter("code_challenge", challenge).appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("state", state).appendQueryParameter("access_type", "offline")
                    .appendQueryParameter("prompt", "consent").build();
            opener.open(u);
            String code = waitForCode(ss, state);
            JSONObject t = formPost(TOKEN, "client_id=" + enc(id) + "&client_secret=" + enc(secret) + "&code=" + enc(code)
                    + "&code_verifier=" + enc(verifier) + "&grant_type=authorization_code&redirect_uri=" + enc(redirect));
            String rt = t.optString("refresh_token", "");
            if (rt.isEmpty()) throw new Exception("Google did not return a refresh token. Remove ClipForge from your Google account's third-party access and connect again.");
            SecureStore.set(ctx, "yt_refresh", rt);
        }
    }

    private static String waitForCode(ServerSocket ss, String state) throws Exception {
        while (true) {
            Socket s;
            try { s = ss.accept(); } catch (SocketTimeoutException e) { throw new Exception("Sign-in timed out. Tap Connect YouTube to try again."); }
            try (Socket sock = s) {
                sock.setSoTimeout(5000);
                BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                String line = r.readLine(), code = null, err = null, st = null;
                if (line != null && line.startsWith("GET ")) {
                    String[] parts = line.split(" ");
                    if (parts.length > 1) {
                        Uri u = Uri.parse("http://127.0.0.1" + parts[1]);
                        code = u.getQueryParameter("code"); err = u.getQueryParameter("error"); st = u.getQueryParameter("state");
                    }
                }
                boolean ok = code != null && state.equals(st);
                if (code == null && err == null) { respond(sock.getOutputStream(), 404, "Not found"); continue; }
                respond(sock.getOutputStream(), 200, ok ? "Connected. You can return to ClipForge now." : err != null ? "Sign-in was cancelled." : "Invalid sign-in answer.");
                if (err != null) throw new Exception("Google sign-in was cancelled or refused (" + err + ").");
                if (ok) return code;
                throw new Exception("The sign-in answer did not match this request. Try again.");
            } catch (SocketTimeoutException ignored) { }
        }
    }

    private static void respond(OutputStream o, int code, String msg) throws IOException {
        byte[] b = ("<html><head><meta name=viewport content='width=device-width,initial-scale=1'></head><body style='font-family:sans-serif;text-align:center;padding:48px'><h2>"
                + msg + "</h2></body></html>").getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        o.write(head.getBytes(StandardCharsets.US_ASCII)); o.write(b); o.flush();
    }

    private static String accessToken(Context ctx) throws Exception {
        String rt = SecureStore.get(ctx, "yt_refresh");
        if (rt.isEmpty()) throw new Exception("Connect YouTube in Settings first.");
        try {
            return formPost(TOKEN, "client_id=" + enc(SecureStore.get(ctx, "yt_id").trim()) + "&client_secret=" + enc(SecureStore.get(ctx, "yt_secret").trim())
                    + "&refresh_token=" + enc(rt) + "&grant_type=refresh_token").getString("access_token");
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("invalid_grant")) { disconnect(ctx); throw new Exception("YouTube access expired. Tap Connect YouTube again."); }
            throw e;
        }
    }

    /** Uploads one clip; returns the new video id. */
    static String upload(Context ctx, Uri uri, String title, String desc, JSONArray tags, String privacy, Progress progress) throws Exception {
        String token = accessToken(ctx);
        long total = -1;
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) total = c.getLong(0);
        }
        if (total <= 0) throw new Exception("Could not read the clip file. It may have been deleted from the Gallery.");
        String pv = "public".equals(privacy) || "unlisted".equals(privacy) ? privacy : "private";
        JSONArray tg = new JSONArray();
        if (tags != null) for (int i = 0; i < tags.length() && tg.length() < 12; i++) {
            String t = clean(tags.optString(i), 30).replace("#", "").replace(" ", "");
            if (!t.isEmpty()) tg.put(t);
        }
        JSONObject meta = new JSONObject()
                .put("snippet", new JSONObject().put("title", clean(title, 100).isEmpty() ? "Short" : clean(title, 100))
                        .put("description", clean(desc, 4800)).put("tags", tg).put("categoryId", "22"))
                .put("status", new JSONObject().put("privacyStatus", pv).put("selfDeclaredMadeForKids", false));
        byte[] mb = meta.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection h = (HttpURLConnection) new URL("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status").openConnection();
        h.setRequestMethod("POST"); h.setConnectTimeout(20000); h.setReadTimeout(30000); h.setDoOutput(true);
        h.setRequestProperty("Authorization", "Bearer " + token);
        h.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        h.setRequestProperty("X-Upload-Content-Length", String.valueOf(total));
        h.setRequestProperty("X-Upload-Content-Type", "video/mp4");
        h.setFixedLengthStreamingMode(mb.length);
        try (OutputStream o = h.getOutputStream()) { o.write(mb); }
        int code = h.getResponseCode();
        if (code != 200) { Resp e = new Resp(); e.code = code; e.body = read(h.getErrorStream()); throw new Exception(apiError(e)); }
        String session = h.getHeaderField("Location");
        if (session == null || session.isEmpty()) throw new Exception("YouTube did not start the upload. Try again.");

        long off = 0; byte[] buf = new byte[CHUNK]; String result = null;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open the clip file.");
            progress.pct(0);
            while (off < total) {
                int want = (int) Math.min(CHUNK, total - off), n = 0;
                while (n < want) { int r = in.read(buf, n, want - n); if (r < 0) break; n += r; }
                if (n < want) throw new IOException("Could not read the whole clip file.");
                Resp r = null; IOException last = null;
                for (int attempt = 0; attempt < 3 && r == null; attempt++) {
                    try {
                        Resp t = put(session, token, buf, n, "bytes " + off + "-" + (off + n - 1) + "/" + total);
                        if (t.code >= 500) throw new IOException("YouTube server error " + t.code);
                        r = t;
                    } catch (IOException e) {
                        last = e;
                        try {
                            Resp q = put(session, token, buf, 0, "bytes */" + total);
                            if (q.code == 308 && received(q.range) >= off + n) { r = q; }
                            else if (q.code == 200 || q.code == 201) r = q;
                        } catch (IOException ignored) { }
                        if (r == null) Thread.sleep(1500L * (attempt + 1));
                    }
                }
                if (r == null) throw new IOException("Upload interrupted: " + (last == null ? "no answer" : last.getMessage()));
                if (r.code == 200 || r.code == 201) { result = r.body; off = total; }
                else if (r.code == 308) {
                    if (received(r.range) < off + n) throw new IOException("YouTube accepted only part of a chunk. Try the upload again.");
                    off += n;
                } else throw new IOException(apiError(r));
                progress.pct((int) (off * 100 / total));
            }
        }
        if (result == null) throw new IOException("YouTube did not confirm the upload.");
        return new JSONObject(result).getString("id");
    }

    private static Resp put(String url, String token, byte[] buf, int n, String contentRange) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod("PUT"); h.setConnectTimeout(20000); h.setReadTimeout(90000); h.setDoOutput(true);
        h.setInstanceFollowRedirects(false);
        h.setRequestProperty("Authorization", "Bearer " + token);
        h.setRequestProperty("Content-Range", contentRange);
        h.setRequestProperty("Content-Type", "video/mp4");
        h.setFixedLengthStreamingMode(n);
        try (OutputStream o = h.getOutputStream()) { if (n > 0) o.write(buf, 0, n); }
        Resp r = new Resp();
        r.code = h.getResponseCode();
        r.range = h.getHeaderField("Range");
        r.body = read(r.code >= 400 ? h.getErrorStream() : h.getInputStream());
        return r;
    }

    private static long received(String range) {
        if (range == null) return 0;
        int i = range.lastIndexOf('-');
        try { return Long.parseLong(range.substring(i + 1).trim()) + 1; } catch (Exception e) { return 0; }
    }

    private static String apiError(Resp r) {
        String m = "";
        try { m = new JSONObject(r.body).getJSONObject("error").optString("message", ""); } catch (Exception ignored) { }
        if (r.code == 403 && r.body.contains("quota")) m = "Daily YouTube upload quota used up. Try again tomorrow.";
        return "YouTube refused the upload (" + r.code + ")" + (m.isEmpty() ? "." : ": " + m);
    }

    private static JSONObject formPost(String url, String body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod("POST"); h.setConnectTimeout(20000); h.setReadTimeout(30000); h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        h.setFixedLengthStreamingMode(b.length);
        try (OutputStream o = h.getOutputStream()) { o.write(b); }
        int code = h.getResponseCode();
        String txt = read(code >= 400 ? h.getErrorStream() : h.getInputStream());
        JSONObject j;
        try { j = new JSONObject(txt); } catch (Exception e) { j = new JSONObject(); }
        if (code >= 400) throw new Exception(j.optString("error", "HTTP " + code) + (j.has("error_description") ? ": " + j.optString("error_description") : ""));
        return j;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
            while ((n = i.read(b)) > 0 && o.size() < 200000) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        s = s.replace("<", "").replace(">", "").replaceAll("[\\p{Cntrl}&&[^\n]]", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }
    private static String b64(byte[] b) { return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static String rand(int bytes) { byte[] b = new byte[bytes]; new SecureRandom().nextBytes(b); return b64(b); }
}
