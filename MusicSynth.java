package com.roohu.clipforge;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Generates an original edit-style beat (dark phonk / trap) so every clip gets modern music with zero setup and
 * no copyright risk: gliding distorted 808, hard kick, clap, rolling hats with fill rolls, a cowbell lead, a dark
 * pad with a sidechain pump, a short echo and an impact hit at the start. Mono 16-bit WAV, 32 kHz.
 */
final class MusicSynth {
    private static final int SR = 32000;
    private static final double TAU = Math.PI * 2;

    /** Cowbell lead patterns, one per bar, in 16th-note steps: semitone offset from the bar root, or -99 for a rest. */
    private static final int[][] LEAD = {
        {0, -99, -99, 0, -99, 3, -99, -99, 0, -99, -99, 0, -99, 5, -99, 3},
        {0, -99, -99, 0, -99, 3, -99, -99, 7, -99, 5, -99, 3, -99, 0, -99},
        {0, -99, -99, 0, -99, 3, -99, -99, 0, -99, -99, 0, -99, 5, -99, 7},
        {7, -99, 5, -99, 3, -99, 0, -99, 3, -99, -99, 5, -99, -99, 3, -99},
    };

    static void generate(File out, String mood, int seconds) throws IOException {
        if ("funk".equals(mood) || "emotional".equals(mood)) {
            int nn = SR * Math.max(4, seconds);
            finish(out, "funk".equals(mood) ? funk(nn) : emotional(nn), nn);
            return;
        }
        boolean calm = "calm".equals(mood), intense = "intense".equals(mood);
        double bpm = intense ? 142 : calm ? 92 : 130;
        double step = 60.0 / bpm / 4.0;                       // one 16th note in seconds
        int n = SR * Math.max(4, seconds);
        float[] mix = new float[n];
        Random rnd = new Random(7);
        int[] roots = {41, 41, 44, 39};                       // F2, F2, G#2, D#2 (dark minor loop), midi
        int stepsPerBar = 16;

        // echo + small room (feedback delays) for the lead and pad bus
        int d1 = (int) (step * 3 * SR), d2 = (int) (step * 2 * SR);
        float[] e1 = new float[d1], e2 = new float[d2];
        int p1 = 0, p2 = 0;

        double hpPrev = 0, hpOut = 0, bpLow = 0, bpBand = 0;
        double bassPhase = 0, leadPh1 = 0, leadPh2 = 0;
        double duck = 1.0;

        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double sp = t / step;
            long stepIdx = (long) sp;
            double inStep = (sp - stepIdx) * step;            // seconds since the 16th started
            int bar = (int) (stepIdx / stepsPerBar);
            int s16 = (int) (stepIdx % stepsPerBar);
            int root = roots[bar % 4];
            int pat = bar % 4;
            boolean fillBar = (bar % 4 == 3) && s16 >= 12;     // hat roll at the end of every 4th bar
            double beatPos = (s16 % 4) * step + inStep;         // seconds since the beat started
            boolean onBeat = s16 % 4 == 0;
            double v = 0;

            // ---- kick (beat 1, the "and" of 2, and a pickup) : punchy with a click
            boolean kick = s16 == 0 || s16 == 7 || s16 == 10 || (intense && s16 == 14) || (!calm && s16 == 8 && bar % 2 == 1);
            if (calm) kick = s16 == 0 || s16 == 10;
            double kickEnv = 0;
            if (kick) {
                double f = 48 + 120 * Math.exp(-inStep * 38);
                kickEnv = Math.exp(-inStep * 11);
                v += Math.sin(TAU * f * inStep) * kickEnv * 0.95;
                v += (rnd.nextDouble() * 2 - 1) * Math.exp(-inStep * 140) * 0.25;
            }

            // ---- clap / snare on beat 3 (half-time feel), layered noise bursts
            if (s16 == 8) {
                double nz = rnd.nextDouble() * 2 - 1;
                double burst = Math.exp(-inStep * 22) + 0.6 * Math.exp(-Math.max(0, inStep - 0.012) * 40);
                bpLow += 0.45 * (nz - bpLow);                      // crude band-limit
                v += (nz - bpLow * 0.6) * burst * 0.42;
                v += Math.sin(TAU * 185 * inStep) * Math.exp(-inStep * 24) * 0.18;
            }

            // ---- hats: 16ths, accent on off-beats, rolls on fills; open hat every 2nd bar
            double nz2 = rnd.nextDouble() * 2 - 1;
            hpOut = nz2 - hpPrev; hpPrev = nz2;
            double hatEnv = Math.exp(-inStep * (s16 % 2 == 1 ? 85 : 110));
            double hatGain = (s16 % 4 == 2) ? 0.17 : (s16 % 2 == 1 ? 0.12 : 0.08);
            if (calm && s16 % 2 == 0) hatGain *= 0.5;
            if (fillBar) {                                         // 32nd-note roll
                double sub = (inStep % (step / 2)) ;
                hatEnv = Math.exp(-sub * 110); hatGain = 0.16;
            }
            if (!calm && s16 == 14 && bar % 2 == 1) hatEnv = Math.exp(-inStep * 18);   // open hat
            v += hpOut * hatEnv * hatGain;

            // ---- 808: gliding sub with soft distortion; plays under the kick and answers it
            double bassNoteT = beatPos;
            boolean bassOn = s16 == 0 || s16 == 3 || s16 == 7 || s16 == 10 || s16 == 12 || (intense && s16 == 14);
            if (calm) bassOn = s16 == 0 || s16 == 10;
            double bassEnv = 0, bf = 0;
            if (bassOn) {
                int off = (s16 == 3 || s16 == 12) ? 12 : (s16 == 10 ? 7 : 0);
                bf = 440.0 * Math.pow(2, (root + off - 69) / 12.0);
                double glide = 1 + 0.9 * Math.exp(-inStep * 45);
                bassEnv = Math.exp(-inStep * (s16 == 0 ? 2.2 : 4.5)) * Math.min(1.0, inStep * 400);
                bassPhase += TAU * bf * glide / SR;
                double b = Math.sin(bassPhase) + 0.35 * Math.sin(bassPhase * 2);
                v += Math.tanh(b * 2.1 * bassEnv) * 0.55;          // warm saturation, audible on small phone speakers
            } else {
                bassPhase = 0;
            }

            // ---- bus for lead + pad (goes into the echo)
            double bus = 0;

            // cowbell lead: two detuned square-ish partials through a soft clip; pitch from the bar's pattern
            int semi = LEAD[pat][s16];
            if (semi != -99 && !calm) {
                double lf = 440.0 * Math.pow(2, (root + 24 + semi - 69) / 12.0);
                double env = Math.exp(-inStep * 16);
                leadPh1 += TAU * lf * 1.5 / SR;
                leadPh2 += TAU * lf * 2.2 / SR;
                double cb = Math.signum(Math.sin(leadPh1)) * 0.5 + Math.signum(Math.sin(leadPh2)) * 0.5;
                bus += cb * env * 0.11;
            }
            if (calm) {                                            // soft bell instead of cowbell for calm clips
                int cs = LEAD[pat][s16];
                if (cs != -99) {
                    double lf = 440.0 * Math.pow(2, (root + 24 + cs - 69) / 12.0);
                    bus += Math.sin(TAU * lf * t) * Math.exp(-inStep * 5) * 0.09;
                }
            }

            // dark pad: root + minor third + fifth, slow tremolo, fades per bar
            double pf = 440.0 * Math.pow(2, (root + 12 - 69) / 12.0);
            double pad = Math.sin(TAU * pf * t) + Math.sin(TAU * pf * 1.1892 * t) + Math.sin(TAU * pf * 1.4983 * t + 0.4);
            bus += pad * 0.035 * (0.7 + 0.3 * Math.sin(TAU * 0.25 * t));

            // echo
            float eo1 = e1[p1], eo2 = e2[p2];
            e1[p1] = (float) (bus + eo1 * 0.42);
            e2[p2] = (float) (bus * 0.8 + eo2 * 0.38);
            p1 = (p1 + 1) % d1; p2 = (p2 + 1) % d2;
            double wet = eo1 * 0.45 + eo2 * 0.35;

            // sidechain: bus ducks hard on the kick and recovers
            double targetDuck = kick ? 0.35 : 1.0;
            duck += (targetDuck - duck) * (kick && inStep < 0.01 ? 0.4 : 0.0009);
            v += (bus + wet) * duck;

            // ---- intro impact: sub drop + noise sweep in the first 0.6 s so the clip opens with a hit
            if (t < 0.7) {
                double ie = Math.exp(-t * 5.5);
                v += Math.sin(TAU * (38 + 60 * Math.exp(-t * 14)) * t) * ie * 0.7;
                v += (rnd.nextDouble() * 2 - 1) * Math.exp(-t * 9) * 0.25;
            }

            mix[i] = (float) Math.tanh(v * 1.25);                   // glue + loudness
        }

        finish(out, mix, n);
    }

    private static void finish(File out, float[] mix, int n) throws IOException {
        float peak = 0.001f;
        for (float f : mix) peak = Math.max(peak, Math.abs(f));
        float g = 0.92f / peak;
        int fadeIn = SR / 100, fadeOut = SR / 2;                  // snappy start, half-second tail
        try (BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            writeHeader(os, n);
            byte[] b = new byte[2];
            for (int i = 0; i < n; i++) {
                float s = mix[i] * g;
                if (i < fadeIn) s *= (float) i / fadeIn;
                if (i > n - fadeOut) s *= (float) (n - i) / fadeOut;
                short sv = (short) Math.max(-32768, Math.min(32767, Math.round(s * 32767)));
                b[0] = (byte) (sv & 0xFF); b[1] = (byte) ((sv >> 8) & 0xFF);
                os.write(b);
            }
        }
    }

    /** Brazilian funk (baile funk) at 130 BPM: 3-3-2 tamborzao kicks with a long 808 tail, drum hits, agogo bell lead, shaker and a stab. */
    private static float[] funk(int n) {
        float[] mix = new float[n];
        Random rnd = new Random(11);
        double bpm = 130, step = 60.0 / bpm / 4.0;
        int[] roots = {38, 38, 41, 36};                       // D2 D2 F2 C2
        int[] kicks = {0, 3, 6, 8, 11, 14};
        int[] drum = {2, 5, 7, 10, 13, 15};                   // atabaque/tom hits between the kicks
        int[][] bell = {{0, 7, 12, 7, 10, 7, 12, 15}, {0, 7, 12, 7, 15, 12, 10, 7}};
        int d1 = (int) (step * 3 * SR);
        float[] e1 = new float[d1];
        int p1 = 0;
        double bassPh = 0, hpPrev = 0, duck = 1;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR, sp = t / step;
            long idx = (long) sp;
            double ins = (sp - idx) * step;
            int bar = (int) (idx / 16), s16 = (int) (idx % 16), root = roots[bar % 4];
            double v = 0;
            boolean isKick = false;
            for (int k : kicks) if (k == s16) isKick = true;
            if (isKick) {
                v += Math.sin(TAU * (46 + 130 * Math.exp(-ins * 42)) * ins) * Math.exp(-ins * 8) * 0.7;
                v += (rnd.nextDouble() * 2 - 1) * Math.exp(-ins * 160) * 0.2;
                double bf = 440.0 * Math.pow(2, (root + (s16 == 8 || s16 == 11 ? 7 : 0) - 69) / 12.0);
                bassPh += TAU * bf * (1 + 0.5 * Math.exp(-ins * 40)) / SR;
                v += Math.tanh(Math.sin(bassPh) * 2.4 * Math.exp(-ins * 5.5)) * 0.38;
            } else bassPh = 0;
            for (int d : drum) if (d == s16) {
                v += Math.sin(TAU * (210 - 60 * Math.min(1, ins * 14)) * ins) * Math.exp(-ins * 26) * 0.32;
                v += (rnd.nextDouble() * 2 - 1) * Math.exp(-ins * 55) * 0.12;
            }
            if (s16 == 4 || s16 == 12) {                       // clap
                double nz = rnd.nextDouble() * 2 - 1;
                v += nz * (Math.exp(-ins * 24) + 0.5 * Math.exp(-Math.max(0, ins - 0.011) * 40)) * 0.32;
            }
            double nz2 = rnd.nextDouble() * 2 - 1, hp = nz2 - hpPrev; hpPrev = nz2;   // shaker
            v += hp * Math.exp(-ins * 70) * (s16 % 2 == 1 ? 0.11 : 0.06);
            double bus = 0;
            int bs = bell[bar % 2][s16 / 2];
            if (s16 % 2 == 0) {                                // agogo bell on the 8ths
                double bfq = 440.0 * Math.pow(2, (root + 36 + bs - 69) / 12.0);
                double env = Math.exp(-ins * 14);
                bus += (Math.sin(TAU * bfq * ins) + 0.6 * Math.sin(TAU * bfq * 2.76 * ins)) * env * 0.17;
            }
            if (s16 == 0 && bar % 2 == 0) {                    // dark stab on the downbeat
                double pf = 440.0 * Math.pow(2, (root + 24 - 69) / 12.0);
                double env = Math.exp(-ins * 6);
                bus += (Math.signum(Math.sin(TAU * pf * ins)) + Math.signum(Math.sin(TAU * pf * 1.1892 * ins))) * env * 0.04;
            }
            float eo = e1[p1];
            e1[p1] = (float) (bus + eo * 0.4);
            p1 = (p1 + 1) % d1;
            duck += ((isKick ? 0.45 : 1.0) - duck) * (isKick && ins < 0.01 ? 0.4 : 0.0012);
            v += (bus + eo * 0.4) * duck;
            if (t < 0.5) v += Math.sin(TAU * (40 + 50 * Math.exp(-t * 14)) * t) * Math.exp(-t * 6) * 0.6;   // opening hit
            mix[i] = (float) Math.tanh(v * 1.2);
        }
        return mix;
    }

    /** Emotional cinematic bed at 72 BPM: soft piano arpeggio over a slow pad in A minor (Am F C G), light kick and a long echo. */
    private static float[] emotional(int n) {
        float[] mix = new float[n];
        Random rnd = new Random(5);
        double bpm = 72, step = 60.0 / bpm / 4.0;
        int[] roots = {45, 41, 48, 43};                       // A2 F2 C3 G2
        int[][] chord = {{0, 7, 12, 15}, {0, 7, 12, 16}, {0, 7, 12, 16}, {0, 7, 12, 16}};
        int[] arp = {0, 1, 2, 3, 2, 1, 2, 1};
        int d1 = (int) (step * 6 * SR), d2 = (int) (step * 4 * SR);
        float[] e1 = new float[d1], e2 = new float[d2];
        int p1 = 0, p2 = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR, sp = t / step;
            long idx = (long) sp;
            double ins = (sp - idx) * step;
            int bar = (int) (idx / 16), s16 = (int) (idx % 16), ci = bar % 4, root = roots[ci];
            double v = 0, bus = 0;
            if (s16 % 2 == 0) {                                // piano note every 8th
                int note = root + 24 + chord[ci][arp[(s16 / 2) % arp.length] % 4];
                double f = 440.0 * Math.pow(2, (note - 69) / 12.0), env = Math.exp(-ins * 3.2) * Math.min(1, ins * 600);
                bus += (Math.sin(TAU * f * ins) + 0.35 * Math.sin(TAU * 2 * f * ins) * Math.exp(-ins * 4) + 0.12 * Math.sin(TAU * 3 * f * ins)) * env * 0.16;
            }
            double pf = 440.0 * Math.pow(2, (root + 12 - 69) / 12.0), bt = (bar % 4) * 16 * step + s16 * step + ins;
            double swell = 0.55 + 0.45 * Math.sin(Math.PI * Math.min(1, bt / (16 * step)));
            for (int h = 0; h < 4; h++) {                      // string pad: detuned stacked tones
                double fr = pf * (h == 0 ? 1 : h == 1 ? 1.4983 : h == 2 ? 2 : 2.5198 * (ci == 0 ? 0.9439 : 1));
                bus += (Math.sin(TAU * fr * t) + Math.sin(TAU * fr * 1.004 * t)) * 0.022 * swell;
            }
            if (s16 == 0) {                                    // soft kick + sub
                v += Math.sin(TAU * (45 + 60 * Math.exp(-ins * 30)) * ins) * Math.exp(-ins * 9) * 0.55;
                double bf = 440.0 * Math.pow(2, (root - 69) / 12.0);
                v += Math.sin(TAU * bf * ins) * Math.exp(-ins * 2.2) * 0.35;
            }
            if (s16 == 8) v += (rnd.nextDouble() * 2 - 1) * Math.exp(-ins * 30) * 0.06;   // brushed tick
            float a = e1[p1], b2 = e2[p2];
            e1[p1] = (float) (bus + a * 0.55);
            e2[p2] = (float) (bus * 0.7 + b2 * 0.5);
            p1 = (p1 + 1) % d1; p2 = (p2 + 1) % d2;
            v += bus + a * 0.5 + b2 * 0.35;
            mix[i] = (float) Math.tanh(v * 1.15);
        }
        return mix;
    }

    private static void writeHeader(BufferedOutputStream os, int samples) throws IOException {
        int dataLen = samples * 2;
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); le32(h, 4, 36 + dataLen); put(h, 8, "WAVE"); put(h, 12, "fmt ");
        le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1); le32(h, 24, SR); le32(h, 28, SR * 2); le16(h, 32, 2); le16(h, 34, 16);
        put(h, 36, "data"); le32(h, 40, dataLen);
        os.write(h);
    }

    private static void put(byte[] b, int o, String s) { for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i); }
    private static void le32(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24); }
    private static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
}
