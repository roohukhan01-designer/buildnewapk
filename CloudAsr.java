package com.roohu.clipforge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Free cloud speech recognition (Groq-hosted Whisper): detects the spoken language by itself and times every word. */
final class CloudAsr {
    static final class Result {
        String lang = "";          // e.g. "urdu"
        boolean rtl;
        final List<Painter.W> words = new ArrayList<>();
    }

    /** One request with up to 4 attempts (exponential backoff on 429, 5xx and network drops). */
    static Result groq(final byte[] pcm16k, final String key, final String isoHint, final double offsetS) throws Exception {
        return Net.retry(() -> groqOnce(pcm16k, key, isoHint, offsetS), 4);
    }

    private static Result groqOnce(byte[] pcm16k, String key, String isoHint, double offsetS) throws Exception {
        byte[] wav = wav(pcm16k);
        String boundary = "----RoohuClipForge" + System.nanoTime();
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setChunkedStreamingMode(0);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (DataOutputStream o = new DataOutputStream(c.getOutputStream())) {
            field(o, boundary, "model", "whisper-large-v3");
            field(o, boundary, "response_format", "verbose_json");
            field(o, boundary, "timestamp_granularities[]", "word");
            field(o, boundary, "temperature", "0");
            if (isoHint != null && isoHint.length() == 2) field(o, boundary, "language", isoHint);
            o.writeBytes("--" + boundary + "\r\n");
            o.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\n");
            o.writeBytes("Content-Type: audio/wav\r\n\r\n");
            o.write(wav);
            o.writeBytes("\r\n--" + boundary + "--\r\n");
        }
        int code = c.getResponseCode();
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        long retryAfter = 0;
        try { String ra = c.getHeaderField("Retry-After"); if (ra != null) retryAfter = (long) (Double.parseDouble(ra.trim()) * 1000); } catch (Exception ignored) { }
        if (code == 401 || code == 403) throw new Net.HttpFail(code, "The Groq key was not accepted. Check it in Settings.", 0);
        if (code == 429) throw new Net.HttpFail(code, "The free Groq limit is used up for now. Try again in a few minutes.", retryAfter);
        if (code >= 400) throw new Net.HttpFail(code, "Cloud captions failed (" + code + ").", retryAfter);
        JSONObject j = new JSONObject(body);
        Result r = new Result();
        r.lang = j.optString("language", "").toLowerCase();
        r.rtl = isRtl(r.lang);
        JSONArray ws = j.optJSONArray("words");
        if (ws != null) {
            for (int i = 0; i < ws.length(); i++) {
                JSONObject w = ws.getJSONObject(i);
                String t = w.optString("word", "").trim();
                if (t.isEmpty()) continue;
                r.words.add(new Painter.W(t, offsetS + w.getDouble("start"), offsetS + w.getDouble("end")));
            }
        }
        return r;
    }

    static boolean isRtl(String lang) {
        switch (lang) {
            case "urdu": case "arabic": case "persian": case "farsi": case "hebrew": case "pashto":
            case "sindhi": case "yiddish": case "uighur": case "uyghur": case "ur": case "ar": case "fa": case "he": case "ps":
                return true;
            default: return false;
        }
    }

    private static void field(DataOutputStream o, String b, String name, String val) throws IOException {
        o.writeBytes("--" + b + "\r\n");
        o.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        o.write(val.getBytes(StandardCharsets.UTF_8));
        o.writeBytes("\r\n");
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

    private static byte[] wav(byte[] pcm) {
        int rate = 16000, len = pcm.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream(len + 44);
        try {
            o.write("RIFF".getBytes(StandardCharsets.US_ASCII)); le32(o, 36 + len);
            o.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)); le32(o, 16);
            le16(o, 1); le16(o, 1); le32(o, rate); le32(o, rate * 2); le16(o, 2); le16(o, 16);
            o.write("data".getBytes(StandardCharsets.US_ASCII)); le32(o, len);
            o.write(pcm);
        } catch (IOException ignored) { }
        return o.toByteArray();
    }

    private static void le32(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24); }
    private static void le16(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); }
}
