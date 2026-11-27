package com.roohu.clipforge;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Finds the loud, punchy moments in a clip's audio. The reaction look cuts and flashes on these. */
final class Energy {
    private static final double WIN = 0.05;   // 50 ms analysis windows

    /** Clip-relative times (seconds, ascending) of loud onsets. Never throws; falls back to an even rhythm. */
    static double[] punches(Context ctx, Uri uri, double startS, double endS) {
        double dur = Math.max(1, endS - startS);
        double[] rms = null;
        try { rms = decode(ctx, uri, startS, endS); } catch (Throwable ignored) { }
        List<Double> out = rms == null ? new ArrayList<Double>() : pick(rms, dur);
        if (out.size() < 2) {
            out.clear();
            for (double t = 1.5; t < dur - 0.5; t += 3.5) out.add(t);
        }
        Collections.sort(out);
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** Loudest RMS of every whole second between startS and endS (index 0 = startS). Empty array if the audio can't be read. */
    static double[] profile(Context ctx, Uri uri, double startS, double endS) {
        try {
            double[] rms = decode(ctx, uri, startS, endS);
            int bins = (int) Math.ceil(endS - startS), per = (int) Math.round(1.0 / WIN);
            double[] out = new double[Math.max(0, bins)];
            for (int i = 0; i < out.length; i++) {
                double m = 0;
                for (int k = i * per; k < Math.min(rms.length, (i + 1) * per); k++) m = Math.max(m, rms[k]);
                out[i] = m;
            }
            return out;
        } catch (Throwable t) { return new double[0]; }
    }

    /**
     * Voiceover / speech spans of the clip's own audio, clip-relative seconds as [start, end, start, end, ...].
     * Found from loudness: a window counts as voiced when it is well above the clip's noise floor; short blips are
     * dropped and close gaps are merged. Returns an empty array when the clip has no voice and null when the audio
     * could not be read.
     */
    static double[] speech(Context ctx, Uri uri, double startS, double endS) {
        double dur = Math.max(1, endS - startS);
        double[] rms;
        try { rms = decode(ctx, uri, startS, endS); } catch (Throwable t) { return null; }
        if (rms == null || rms.length < 10) return null;
        int n = Math.min(rms.length, (int) Math.ceil(dur / WIN));
        double[] sorted = Arrays.copyOf(rms, n);
        Arrays.sort(sorted);
        double top = sorted[(int) (n * 0.9)], floor = sorted[(int) (n * 0.2)];
        if (top < 0.004) return new double[0];                       // practically silent
        double thr = Math.max(top * 0.30, floor * 2.5);
        List<double[]> runs = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= n; i++) {
            boolean voiced = i < n && rms[i] > thr;
            if (voiced && start < 0) start = i;
            if (!voiced && start >= 0) {
                double a = start * WIN, b = i * WIN;
                if (b - a >= 0.3) {
                    double[] last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
                    if (last != null && a - last[1] < 0.5) last[1] = b; else runs.add(new double[]{a, b});
                }
                start = -1;
            }
        }
        double[] out = new double[runs.size() * 2];
        for (int i = 0; i < runs.size(); i++) {
            out[2 * i] = Math.max(0, runs.get(i)[0] - 0.12);
            out[2 * i + 1] = Math.min(dur, runs.get(i)[1] + 0.05);
        }
        return out;
    }

    private static List<Double> pick(final double[] rms, double dur) {
        int n = rms.length;
        double[] sorted = rms.clone();
        Arrays.sort(sorted);
        double p60 = sorted[(int) (n * 0.6)];
        final double[] flux = new double[n];
        List<Integer> cand = new ArrayList<>();
        for (int i = 4; i < n; i++) {
            int a = Math.max(0, i - 20);
            double mean = 0;
            for (int k = a; k < i; k++) mean += rms[k];
            mean /= Math.max(1, i - a);
            flux[i] = rms[i] - mean;
            boolean peak = true;
            for (int k = Math.max(0, i - 3); k <= Math.min(n - 1, i + 3); k++) if (rms[k] > rms[i]) { peak = false; break; }
            if (peak && flux[i] > 0 && rms[i] > 1.4 * mean + 1e-4 && rms[i] > p60 && i * WIN > 0.4) cand.add(i);
        }
        Collections.sort(cand, (x, y) -> Double.compare(flux[y], flux[x]));
        int max = (int) Math.max(3, Math.min(14, dur / 3.0));
        List<Double> out = new ArrayList<>();
        for (int idx : cand) {
            double t = idx * WIN;
            boolean ok = true;
            for (double o : out) if (Math.abs(o - t) < 1.2) { ok = false; break; }
            if (ok) out.add(t);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** Same result as profile(), but from audio that was already decoded to 16 kHz mono (no second decode of the video). */
    static double[] profileFromPcm(byte[] pcm16kMono, double base, double startS, double endS) {
        int bins = Math.max(0, (int) Math.ceil(endS - startS)), per = (int) Math.round(1.0 / WIN);
        int n = (int) Math.ceil((endS - startS) / WIN) + 2;
        double[] sum = new double[n];
        int[] cnt = new int[n];
        int samples = pcm16kMono.length / 2;
        for (int i = 0; i < samples; i++) {
            double t = base + i / 16000.0 - startS;
            int w = (int) (t / WIN);
            if (w < 0 || w >= n) continue;
            int lo = pcm16kMono[2 * i] & 0xFF, hi = pcm16kMono[2 * i + 1];
            double v = ((hi << 8) | lo) / 32768.0;
            sum[w] += v * v; cnt[w]++;
        }
        double[] out = new double[bins];
        for (int i = 0; i < bins; i++) {
            double m = 0;
            for (int k = i * per; k < Math.min(n, (i + 1) * per); k++) if (cnt[k] > 0) m = Math.max(m, Math.sqrt(sum[k] / cnt[k]));
            out[i] = m;
        }
        return out;
    }

    private static double[] decode(Context ctx, Uri uri, final double startS, final double endS) throws Exception {
        final int n = (int) Math.ceil((endS - startS) / WIN) + 2;
        final double[] sum = new double[n];
        final int[] cnt = new int[n];
        AudioDecode.run(ctx, uri, startS, endS, new AudioDecode.Sink() {
            @Override public void reset() { Arrays.fill(sum, 0); Arrays.fill(cnt, 0); }

            @Override public void pcm(short[] pcm, int count, int ch, int sr, long ptsUs) {
                double t0 = ptsUs / 1e6 - startS;
                for (int p = 0, fr = 0; p + ch <= count; p += ch, fr++) {
                    int w = (int) ((t0 + fr / (double) sr) / WIN);
                    if (w < 0 || w >= n) continue;
                    double s = 0;
                    for (int c = 0; c < ch; c++) s += pcm[p + c];
                    s /= ch * 32768.0;
                    sum[w] += s * s; cnt[w]++;
                }
            }
        }, null);
        double[] rms = new double[n];
        for (int i = 0; i < n; i++) rms[i] = cnt[i] > 0 ? Math.sqrt(sum[i] / cnt[i]) : 0;
        return rms;
    }
}
