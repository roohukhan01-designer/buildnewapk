package com.roohu.clipforge;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;

/** Decodes a clip's audio to 16 kHz mono PCM16 once, so every speech engine (and the loudness curve) can reuse it. */
final class Pcm16k {
    final byte[] data;
    final double base;   // source-video time (s) of the first sample
    private Pcm16k(byte[] d, double b) { data = d; base = b; }

    static Pcm16k decode(Context ctx, Uri uri, double startS, double endS, Transcriber.Prog prog) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        final double[] base = {-1};
        AudioDecode.run(ctx, uri, startS, endS, new AudioDecode.Sink() {
            double nextOut, acc;
            long idx; int cnt;
            double step = 0;
            int stepSr = 0;

            @Override public void reset() { out.reset(); base[0] = -1; nextOut = 0; acc = 0; idx = 0; cnt = 0; step = 0; stepSr = 0; }

            @Override public void pcm(short[] pcm, int count, int ch, int sr, long ptsUs) {
                if (base[0] < 0) base[0] = ptsUs / 1e6;
                if (sr != stepSr) { stepSr = sr; step = sr / 16000.0; if (nextOut < step) nextOut = idx + step; }
                for (int p = 0; p + ch <= count; p += ch) {
                    int s = 0;
                    for (int c = 0; c < ch; c++) s += pcm[p + c];
                    acc += s / (double) ch; cnt++; idx++;
                    if (idx >= nextOut) {
                        short o = (short) (acc / Math.max(1, cnt));
                        acc = 0; cnt = 0; nextOut += step;
                        out.write(o & 0xFF);
                        out.write((o >> 8) & 0xFF);
                    }
                }
            }
        }, p -> { if (prog != null) prog.on(p); });
        return new Pcm16k(out.toByteArray(), Math.max(0, base[0]));
    }
}
