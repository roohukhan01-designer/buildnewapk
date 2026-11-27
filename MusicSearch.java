package com.roohu.clipforge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds songs on YouTube through the official Data API (any music, or only Creative Commons when asked).
 * Nothing is downloaded or ripped: the app lists and previews, and the song is added on YouTube itself.
 */
final class MusicSearch {
    private static final String API = "https://www.googleapis.com/youtube/v3/";

    static JSONArray search(String key, String query, int max, boolean ccOnly) throws Exception {
        String s = get(API + "search?part=snippet&type=video&videoCategoryId=10" + (ccOnly ? "&videoLicense=creativeCommon" : "")
                + "&videoDuration=short&safeSearch=moderate&maxResults=" + Math.max(1, Math.min(15, max))
                + "&q=" + URLEncoder.encode(query, "UTF-8") + "&key=" + URLEncoder.encode(key, "UTF-8"));
        JSONArray items = new JSONObject(s).optJSONArray("items");
        JSONArray out = new JSONArray();
        if (items == null || items.length() == 0) return out;
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            String id = it.getJSONObject("id").optString("videoId");
            if (id.isEmpty()) continue;
            if (ids.length() > 0) ids.append(',');
            ids.append(id);
            JSONObject sn = it.getJSONObject("snippet");
            JSONObject th = sn.optJSONObject("thumbnails");
            JSONObject t = th == null ? null : (th.optJSONObject("medium") != null ? th.optJSONObject("medium") : th.optJSONObject("default"));
            out.put(new JSONObject().put("id", id).put("title", sn.optString("title")).put("channel", sn.optString("channelTitle"))
                    .put("thumb", t == null ? "" : t.optString("url")).put("dur", 0));
        }
        if (ids.length() == 0) return out;
        String v = get(API + "videos?part=contentDetails&id=" + ids + "&key=" + URLEncoder.encode(key, "UTF-8"));
        JSONArray vi = new JSONObject(v).optJSONArray("items");
        if (vi != null) {
            for (int i = 0; i < vi.length(); i++) {
                JSONObject o = vi.getJSONObject(i);
                int d = seconds(o.getJSONObject("contentDetails").optString("duration"));
                for (int k = 0; k < out.length(); k++)
                    if (out.getJSONObject(k).optString("id").equals(o.optString("id"))) out.getJSONObject(k).put("dur", d);
            }
        }
        return out;
    }

    private static int seconds(String iso) {
        Matcher m = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matcher(iso == null ? "" : iso);
        if (!m.matches()) return 0;
        int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
        int mi = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        int s = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return h * 3600 + mi * 60 + s;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(20000);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); in.close(); }
        String body = bo.toString("UTF-8");
        if (code >= 400) {
            String msg = "YouTube search failed (" + code + ").";
            try { msg = new JSONObject(body).getJSONObject("error").optString("message", msg); } catch (Exception ignored) { }
            throw new Exception(msg);
        }
        return body;
    }
}
