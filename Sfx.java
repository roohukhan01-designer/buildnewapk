package com.roohu.clipforge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Sound effects for a clip, placed where the AI editor asked: impact (reveals, punchlines), whoosh (before punches
 * and cuts), riser (build into a payoff) and pop (small emphasis). Rendered into a mono 32 kHz WAV that is mixed
 * under the clip. All sounds are generated here, so there is nothing to license.
 */
final class Sfx {
    private static final int SR = 32000;
    private static final double TAU = Math.PI * 2;

    static void generate(File out, double dur, JSONArray events) throws IOException {
        int n = (int) Math.ceil(dur * SR) + SR;
        float[] mix = new float[n];
        Random rnd = new Random(3);
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null) continue;
            double t = e.optDouble("t", -1);
            if (t < 0 || t > dur) continue;
            String type = e.optString("type", "impact");
            int s0 = (int) (t * SR);
            if ("whoosh".equals(type)) whoosh(mix, s0, rnd);
            else if ("riser".equals(type)) riser(mix, s0, rnd);
            else if ("pop".equals(type)) pop(mix, s0);
            else impact(mix, s0, rnd);
        }
        float peak = 0.001f;
        for (float f : mix) peak = Math.max(peak, Math.abs(f));
        float g = peak > 0.95f ? 0.95f / peak : 1f;
        try (BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            int dataLen = n * 2;
            byte[] h = new byte[44];
            put(h, 0, "RIFF"); le32(h, 4, 36 + dataLen); put(h, 8, "WAVE"); put(h, 12, "fmt ");
            le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1); le32(h, 24, SR); le32(h, 28, SR * 2); le16(h, 32, 2); le16(h, 34, 16);
            put(h, 36, "data"); le32(h, 40, dataLen);
            os.write(h);
            byte[] b = new byte[2];
            for (int i = 0; i < n; i++) {
                short sv = (short) Math.max(-32768, Math.min(32767, Math.round(mix[i] * g * 32767)));
                b[0] = (byte) (sv & 0xFF); b[1] = (byte) ((sv >> 8) & 0xFF);
                os.write(b);
            }
        }
    }

    /** Cinematic hit: sub drop, a bright crack and a noise tail. */
    private static void impact(float[] m, int s0, Random r) {
        int len = (int) (1.1 * SR);
        for (int i = 0; i < len && s0 + i < m.length; i++) {
            double t = (double) i / SR;
            double v = Math.sin(TAU * (34 + 90 * Math.exp(-t * 16)) * t) * Math.exp(-t * 3.4) * 0.85;
            v += (r.nextDouble() * 2 - 1) * Math.exp(-t * 22) * 0.45;
            v += Math.sin(TAU * 1800 * t) * Math.exp(-t * 90) * 0.12;
            m[s0 + i] += (float) Math.tanh(v * 1.4);
        }
    }

    /** Air sweep: filtered noise that rises and falls, placed so it peaks about 0.25 s after the cue. */
    private static void whoosh(float[] m, int s0, Random r) {
        int len = (int) (0.55 * SR);
        double lp = 0;
        for (int i = 0; i < len && s0 + i < m.length; i++) {
            double t = (double) i / len;
            double env = Math.sin(Math.PI * Math.pow(t, 0.8));
            double a = 0.04 + 0.5 * t;                              // cutoff opens as it sweeps
            lp += a * ((r.nextDouble() * 2 - 1) - lp);
            m[s0 + i] += (float) (lp * env * 0.9);
        }
    }

    /** Build-up: rising noise and tone that ends at the cue time plus 1.2 s. */
    private static void riser(float[] m, int s0, Random r) {
        int len = (int) (1.2 * SR);
        double lp = 0, ph = 0;
        for (int i = 0; i < len && s0 + i < m.length; i++) {
            double t = (double) i / len;
            lp += (0.02 + 0.6 * t) * ((r.nextDouble() * 2 - 1) - lp);
            ph += TAU * (200 + 1600 * t * t) / SR;
            m[s0 + i] += (float) ((lp * 0.8 + Math.sin(ph) * 0.18) * t * t * 0.8);
        }
    }

    /** Short bubble pop for small emphasis. */
    private static void pop(float[] m, int s0) {
        int len = (int) (0.12 * SR);
        for (int i = 0; i < len && s0 + i < m.length; i++) {
            double t = (double) i / SR;
            m[s0 + i] += (float) (Math.sin(TAU * (900 - 5000 * t) * t) * Math.exp(-t * 38) * 0.55);
        }
    }

    private static void put(byte[] b, int o, String s) { for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i); }
    private static void le32(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24); }
    private static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
}
