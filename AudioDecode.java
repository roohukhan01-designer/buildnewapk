package com.roohu.clipforge;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The one place that turns a video's audio track into PCM. Used by captions (Pcm16k) and loudness (Energy).
 * It never trusts a single way of reading: if the first try fails (for example the decoder or the file reader answers
 * "IllegalStateException"), it retries with the phone's software decoder, then with a private copy of the video, and
 * only then gives up with a message that says what happened. It also cannot hang: a decoder that stops answering
 * for 20 seconds is treated as a failure.
 */
final class AudioDecode {
    interface Sink {
        /** Called before every new attempt: drop everything received so far. */
        void reset();
        /** Interleaved 16-bit samples. count = number of shorts. */
        void pcm(short[] data, int count, int channels, int sampleRate, long ptsUs);
    }

    interface Prog { void on(int pct); }

    /** The video has no audio at all: retrying cannot help. */
    static final class NoAudio extends IOException {
        NoAudio() { super("This video has no audio track."); }
    }

    private AudioDecode() { }

    static void run(Context ctx, Uri uri, double startS, double endS, Sink sink, Prog prog) throws IOException {
        List<String> tried = new ArrayList<>();
        Throwable last = null;
        Uri[] sources = new Uri[]{uri, null};
        for (int s = 0; s < 2; s++) {
            Uri src = sources[s];
            if (s == 1) {
                if ("file".equalsIgnoreCase(uri.getScheme())) break;   // already a plain file: a copy would not change anything
                try { src = MediaCache.localize(ctx, uri, "video"); }
                catch (IOException e) { tried.add("private copy: " + Diag.describe(e)); last = e; break; }
            }
            for (int mode = 0; mode < 2; mode++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled.");
                try {
                    sink.reset();
                    attempt(ctx, src, startS, endS, sink, prog, mode == 1);
                    return;
                } catch (NoAudio e) {
                    throw e;
                } catch (InterruptedIOException e) {
                    throw e;
                } catch (Throwable e) {
                    last = e;
                    tried.add((s == 0 ? "direct" : "copy") + (mode == 0 ? "/standard decoder" : "/software decoder") + ": " + Diag.describe(e));
                }
            }
        }
        throw new IOException("Could not read the audio of this video. " + tried.get(tried.size() - 1)
                + (tried.size() > 1 ? " (tried " + tried.size() + " ways)" : ""), last);
    }

    private static MediaCodec createDecoder(String mime, boolean software) throws IOException {
        if (software) {
            MediaCodecInfo[] all = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (MediaCodecInfo ci : all) {
                if (ci.isEncoder()) continue;
                String n = ci.getName();
                if (!(n.startsWith("c2.android.") || n.startsWith("OMX.google."))) continue;
                for (String t : ci.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return MediaCodec.createByCodecName(n);
            }
        }
        return MediaCodec.createDecoderByType(mime);
    }

    private static void attempt(Context ctx, Uri uri, double startS, double endS, Sink sink, Prog prog, boolean software) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        boolean started = false;
        try {
            ex.setDataSource(ctx, uri, null);
            int ai = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { ai = i; break; }
            }
            if (ai < 0) throw new NoAudio();
            ex.selectTrack(ai);
            MediaFormat f = ex.getTrackFormat(ai);
            String mime = f.getString(MediaFormat.KEY_MIME);
            long startUs = (long) (startS * 1e6), endUs = (long) (endS * 1e6);
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            int sr = f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int ch = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            if (!f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) || f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) < (1 << 18))
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 18);
            dec = createDecoder(mime, software);
            dec.configure(f, null, null, 0);
            dec.start();
            started = true;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false, floatPcm = false;
            long lastLife = SystemClock.elapsedRealtime();
            int lastPct = -1;
            short[] tmp = new short[1 << 14];
            while (!outDone) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled.");
                if (SystemClock.elapsedRealtime() - lastLife > 20000) throw new IOException("The audio decoder stopped answering.");
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int n = ib == null ? -1 : ex.readSampleData(ib, 0);
                        long st = n < 0 ? -1 : ex.getSampleTime();
                        if (n < 0 || st > endUs) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, n, st, 0);
                            ex.advance();
                        }
                        lastLife = SystemClock.elapsedRealtime();
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                    lastLife = SystemClock.elapsedRealtime();
                } else if (oi >= 0) {
                    lastLife = SystemClock.elapsedRealtime();
                    if (info.size > 0) {
                        ByteBuffer ob = dec.getOutputBuffer(oi);
                        if (ob != null) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            ob.order(ByteOrder.nativeOrder());
                            int count;
                            short[] pcm;
                            if (floatPcm) {
                                count = info.size / 4;
                                pcm = count <= tmp.length ? tmp : new short[count];
                                java.nio.FloatBuffer fb = ob.asFloatBuffer();
                                for (int k = 0; k < count; k++) {
                                    float v = fb.get() * 32767f;
                                    pcm[k] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
                                }
                            } else {
                                count = info.size / 2;
                                pcm = count <= tmp.length ? tmp : new short[count];
                                ob.asShortBuffer().get(pcm, 0, count);
                            }
                            sink.pcm(pcm, count, Math.max(1, ch), Math.max(8000, sr), info.presentationTimeUs);
                        }
                        if (prog != null) {
                            int pct = (int) Math.max(0, Math.min(99, (info.presentationTimeUs - startUs) * 100.0 / Math.max(1, endUs - startUs)));
                            if (pct != lastPct) { lastPct = pct; prog.on(pct); }
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    dec.releaseOutputBuffer(oi, false);
                }
            }
        } finally {
            if (dec != null) {
                try { if (started) dec.stop(); } catch (Exception ignored) { }
                try { dec.release(); } catch (Exception ignored) { }
            }
            try { ex.release(); } catch (Exception ignored) { }
        }
    }
}
