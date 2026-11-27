package com.roohu.clipforge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.media.FaceDetector;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Finds where the main face is during a clip, fully on the phone: no internet, no Gemini, no extra dependency
 * (it uses the Android framework's own android.media.FaceDetector). Returns raw samples [[seconds from clip start, x or null], ...]
 * with x from -1 (left edge) to 1 (right edge). The page smooths them (shared/core/motion.js buildTrack).
 * android.media.FaceDetector finds upright, mostly frontal faces. When it finds none, the page keeps the single AI-chosen crop.
 */
final class FaceTrack {
    private static final int DETECT_W = 320;      // must be even for FaceDetector
    private static final int MAX_SAMPLES = 40;

    /** Call off the UI thread. start/end are seconds in the video, step is the gap between pictures. */
    static JSONArray detect(Context ctx, Uri video, double start, double end, double step) throws Exception {
        double dur = Math.max(0, end - start);
        if (dur < 1) return new JSONArray();
        step = Math.max(0.5, step);
        int count = (int) Math.min(MAX_SAMPLES, Math.floor(dur / step) + 1);
        if (count < 3) count = 3;
        double gap = dur / (count - 1);

        JSONArray out = new JSONArray();
        MediaMetadataRetriever r = MediaCache.retriever(ctx, video);
        try {
            FaceDetector.Face[] faces = new FaceDetector.Face[3];
            double prev = Double.NaN;
            for (int i = 0; i < count; i++) {
                double rel = Math.min(dur, i * gap);
                Bitmap full = r.getFrameAtTime((long) ((start + rel) * 1_000_000), MediaMetadataRetriever.OPTION_CLOSEST);
                Double x = null;
                if (full != null) {
                    int h = Math.max(2, Math.round(full.getHeight() * (float) DETECT_W / full.getWidth()));
                    Bitmap small = Bitmap.createScaledBitmap(full, DETECT_W, h, true);
                    if (small != full) full.recycle();
                    Bitmap rgb = small.copy(Bitmap.Config.RGB_565, false);   // FaceDetector needs RGB_565
                    small.recycle();
                    if (rgb != null) {
                        int found = new FaceDetector(rgb.getWidth(), rgb.getHeight(), faces.length).findFaces(rgb, faces);
                        double best = -1;
                        for (int k = 0; k < found; k++) {
                            FaceDetector.Face f = faces[k];
                            if (f == null || f.confidence() < FaceDetector.Face.CONFIDENCE_THRESHOLD) continue;
                            PointF mid = new PointF();
                            f.getMidPoint(mid);
                            double nx = Math.max(-1, Math.min(1, mid.x / rgb.getWidth() * 2 - 1));
                            // bigger and surer faces win; a face close to the last one gets a small bonus so the camera does not jump between people
                            double score = f.confidence() * f.eyesDistance();
                            if (!Double.isNaN(prev)) score *= 1 + 0.5 * Math.max(0, 1 - Math.abs(nx - prev));
                            if (score > best) { best = score; x = nx; }
                        }
                        rgb.recycle();
                    }
                }
                if (x != null) prev = x;
                out.put(new JSONArray().put(Math.round(rel * 1000) / 1000.0).put(x == null ? JSONObject.NULL : (Object) x));
            }
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        return out;
    }
}
