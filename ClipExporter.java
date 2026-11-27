package com.roohu.clipforge;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;

import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.Crop;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.OverlaySettings;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.RgbMatrix;
import androidx.media3.effect.SpeedChangeEffect;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.google.common.collect.ImmutableList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders one clip with the phone's hardware video encoder: trim, smart 9:16 crop, colour enhance,
 * zoom punch / beat pulse, fades, animated captions, name credit, progress bar and music.
 * Output is a constant 30 fps H.264/AAC MP4 with a proper index, so it plays smoothly in the Gallery.
 */
@UnstableApi
final class ClipExporter {
    interface Listener {
        void progress(int pct);
        void done(File file);
        void error(String message);
    }

    private Transformer transformer;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean finished;
    private Listener listener;   // kept so cancel() can tell the page; otherwise the page waits forever and Export stays disabled

    /** Explicit container MIME helps Media3 load picker-copied file URIs reliably. */
    private static MediaItem.Builder mediaItemBuilder(Context ctx, Uri uri) {
        MediaItem.Builder b = new MediaItem.Builder().setUri(uri);
        String mime = null;
        try { mime = ctx.getContentResolver().getType(uri); } catch (Exception ignored) { }
        if (mime == null || mime.isEmpty()) {
            String path = uri == null ? "" : uri.getLastPathSegment();
            String ext = "";
            if (path != null) {
                int dot = path.lastIndexOf('.');
                if (dot >= 0) ext = path.substring(dot + 1).toLowerCase(java.util.Locale.US);
            }
            if ("mp4".equals(ext) || "m4v".equals(ext) || "media".equals(ext) || ext.isEmpty()) mime = "video/mp4";
            else if ("mov".equals(ext) || "qt".equals(ext)) mime = "video/quicktime";
            else if ("webm".equals(ext)) mime = "video/webm";
            else if ("mkv".equals(ext)) mime = "video/x-matroska";
            else if ("3gp".equals(ext)) mime = "video/3gpp";
            else if ("mp3".equals(ext)) mime = "audio/mpeg";
            else if ("m4a".equals(ext) || "aac".equals(ext)) mime = "audio/mp4";
            else if ("wav".equals(ext)) mime = "audio/wav";
            else if ("ogg".equals(ext) || "opus".equals(ext)) mime = "audio/ogg";
            else if ("flac".equals(ext)) mime = "audio/flac";
        }
        if (mime != null && !mime.isEmpty()) b.setMimeType(mime);
        return b;
    }

    /** Must be called on the main thread. musicUri may be null. */
    void start(final Context ctx, Uri src, JSONObject spec, Uri musicUri, Uri camUri, final Listener l) {
        listener = l;
        Fonts.init(ctx);
        try {
            final double s = spec.getDouble("s"), e = spec.getDouble("e"), srcDur = e - s;   // srcDur = length in the video; dur (below) = length of the finished clip
            final int sw = spec.optInt("w", 0), sh = spec.optInt("h", 0);
            final int W, H;
            if (sw >= 64 && sh >= 64 && sw <= 4320 && sh <= 4320) { W = sw & ~1; H = sh & ~1; }   // any aspect ratio, validated again here
            else { W = spec.optInt("res", 720) >= 1080 ? 1080 : 720; H = Math.round(W * 16f / 9f / 2f) * 2; }
            final int ow = Math.min(W, H);   // overlay text is sized from the short side so it fits every aspect ratio
            int fpsReq = spec.optInt("fps", 30);
            final int fps = fpsReq == 24 || fpsReq == 60 ? fpsReq : 30;
            final String mood = spec.optString("mood", "upbeat");
            final boolean react = "react".equals(spec.optString("look", "normal")) && H > W;   // the framed look is designed for portrait
            final boolean neon = "neon".equals(spec.optString("look", "normal"));
            final List<double[]> segs = camUri != null && spec.optBoolean("cut", false) && srcDur >= 3
                    ? cutPlan(ctx, camUri, srcDur, spec.optDouble("camA", 2), spec.optDouble("camB", 3)) : null;
            final boolean cut = segs != null && segs.size() > 1;
            // Speed ramps (M24). Not combined with face cam cutaways. All times below the ramp are moved into finished-clip time.
            final SpeedRamp ramp = cut ? null : SpeedRamp.parse(spec.optJSONArray("ramps"), srcDur);
            final double dur = ramp != null ? ramp.outDuration() : srcDur;
            double[] punchIn = react || neon ? readPunches(spec.optJSONArray("punches")) : new double[0];
            if (ramp != null) punchIn = ramp.mapTimes(punchIn);
            final double[] pk = withCuts(punchIn, cut ? segs : null);
            final double[][] track = spec.optBoolean("follow", true) ? readTrack(spec.optJSONArray("track")) : null;   // face tracking (M24)

            int vw = 0, vh = 0, rot = 0;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(ctx, src);
                vw = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                vh = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                String ro = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                rot = ro == null ? 0 : Integer.parseInt(ro);
            } finally { r.release(); }
            if (rot == 90 || rot == 270) { int t = vw; vw = vh; vh = t; }

            final boolean fill = react || !"fit".equals(spec.optString("mode", "fill"));
            final double cropX = spec.optDouble("x", 0);
            List<Effect> fx = new ArrayList<>();    // look: colour, punch, overlays, fades

            // 1) smart reframe: follows the face when a track exists (M24), else the single crop position chosen during analysis
            List<Effect> pre = reframe(vw, vh, W, H, fill, cropX, track, 0);   // per-shot reframe

            // 2) Cinematic Color Studio: restrained, adjustable grade presets.
            final float gradeMix = Math.max(0f, Math.min(1f, (float) spec.optDouble("gradeStrength", 100) / 100f));
            final String grade = spec.optString("grade", "cinematic");
            float gradeContrast = 0f, gradeBrightness = 0f, gradeSaturation = 0f;
            if ("teal".equals(grade)) { gradeContrast = 0.20f; gradeBrightness = 0.005f; gradeSaturation = 28f; }
            else if ("warm".equals(grade)) { gradeContrast = 0.12f; gradeBrightness = 0.025f; gradeSaturation = 20f; }
            else if ("cool".equals(grade)) { gradeContrast = 0.17f; gradeBrightness = -0.005f; gradeSaturation = 16f; }
            else if ("vintage".equals(grade)) { gradeContrast = -0.05f; gradeBrightness = 0.025f; gradeSaturation = -22f; }
            else if ("mono".equals(grade)) { gradeContrast = 0.16f; gradeSaturation = -100f; }
            else if ("clean".equals(grade)) { gradeContrast = 0.04f; gradeBrightness = 0.005f; gradeSaturation = 4f; }
            else { gradeContrast = 0.15f; gradeBrightness = 0.01f; gradeSaturation = 16f; }
            if (gradeMix > 0f) {
                if (gradeContrast != 0f) fx.add(new Contrast(gradeContrast * gradeMix));
                if (gradeBrightness != 0f) fx.add(new Brightness(gradeBrightness * gradeMix));
                if (gradeSaturation != 0f) fx.add(new HslAdjustment.Builder().adjustSaturation(gradeSaturation * gradeMix).build());
                if ("warm".equals(grade)) fx.add((RgbMatrix) (pts, hdr) -> new float[]{1f + .06f * gradeMix,0,0,0, 0,1f + .01f * gradeMix,0,0, 0,0,1f - .08f * gradeMix,0, 0,0,0,1});
                else if ("cool".equals(grade) || "teal".equals(grade)) fx.add((RgbMatrix) (pts, hdr) -> new float[]{1f - .05f * gradeMix,0,0,0, 0,1f,0,0, 0,0,1f + .07f * gradeMix,0, 0,0,0,1});
            }
            // Older clients without a grade field keep their original auto-enhance look.
            if (spec.optBoolean("enh", true) && !spec.has("grade")) {
                fx.add(new Contrast(neon ? 0.22f : 0.12f));
                fx.add(new Brightness(0.02f));
                fx.add(new HslAdjustment.Builder().adjustSaturation(neon ? 38f : 18f).build());
            }

            // 3) zoom punch-in and beat pulse
            final boolean punch = "punch".equals(spec.optString("trans", "fade"));
            final boolean beat = spec.optBoolean("beat", false);
            final double bpm = "intense".equals(mood) ? 128 : "upbeat".equals(mood) ? 108 : 78;
            if (punch || beat || pk.length > 0) {
                fx.add((MatrixTransformation) pts -> {
                    double t = pts / 1e6, z = 1, dx = 0, dy = 0;
                    if (punch && t < 0.6) z += 0.14 * (1 - t / 0.6);
                    if (beat) z += 0.035 * Math.pow(Math.max(0, Math.cos(2 * Math.PI * t * bpm / 60.0)), 8);
                    double d = sinceLast(pk, t);
                    if (d >= 0) {
                        double k = Math.exp(-d / 0.16);
                        z += 0.17 * k;
                        dx = 0.012 * k * Math.sin(d * 90);
                        dy = 0.010 * k * Math.cos(d * 77);
                    }
                    Matrix m = new Matrix();
                    m.setScale((float) z, (float) z);
                    m.postTranslate((float) dx, (float) dy);
                    return m;
                });
            }
            // light flash on every punch
            if (pk.length > 0) {
                fx.add((RgbMatrix) (pts, hdr) -> {
                    double d = sinceLast(pk, pts / 1e6);
                    float k = (float) (d >= 0 ? 1 + 0.40 * Math.exp(-d / 0.09) : 1);
                    return new float[]{k, 0, 0, 0, 0, k, 0, 0, 0, 0, k, 0, 0, 0, 0, 1};
                });
            }

            // 4) overlays
            List<TextureOverlay> ov = new ArrayList<>();
            if (spec.optBoolean("enh", true)) {
                ov.add(BitmapOverlay.createStaticBitmapOverlay(Painter.vignette(W, H)));
            }
            if (react) {
                final float tf = 0.15f, bf = 0.20f;
                ov.add(BitmapOverlay.createStaticBitmapOverlay(Painter.reactFrame(W, H, tf, bf)));
                ov.add(new PulseOverlay(Painter.reactGlow(W, H, tf, bf), bpm, pk));
                String hook = spec.optString("hook", spec.optString("title", ""));
                if (!hook.trim().isEmpty()) ov.add(new HookOverlay(ow, H * tf * 0.92f, tf, hook));
            }
            if (neon) {
                String[] nl = Painter.neonLines(spec.optString("hook", spec.optString("title", "")));
                if (nl[0].length() + nl[1].length() > 0) ov.add(new NeonTypeOverlay(ow, nl));
            }
            JSONArray cj = ramp != null ? ramp.mapCues(spec.optJSONArray("cues"), s) : spec.optJSONArray("cues");
            if (spec.optBoolean("capOn", true) && cj != null && cj.length() > 0) {
                ov.add(new CaptionOverlay(ow, s, parseCues(cj), spec.optString("capStyle", "pop"),
                        Painter.face(spec.optString("font", "")), spec.optBoolean("rtl", false),
                        "top".equals(spec.optString("capPos", "bottom")) ? 0.78f : "center".equals(spec.optString("capPos", "bottom")) ? 0f : -0.26f,
                        Math.max(0.7f, Math.min(1.3f, (float) spec.optDouble("capSize", 100) / 100f))));
            }
            ov.add(new NameOverlay(ow, (float) spec.optDouble("wmSize", 100), (float) spec.optDouble("wmOp", 90) / 100f,
                    spec.optString("wmPos", "mc"), react ? 1f - 0.20f : 0f));
            if (spec.optBoolean("prog", true)) ov.add(new ProgressOverlay(W, H, dur));
            if (spec.optBoolean("fxq", true)) fx.add(new FxEffect(pk));
            fx.add(new OverlayEffect(ImmutableList.copyOf(ov)));

            // 5) fade in / fade out
            if ("fade".equals(spec.optString("trans", "fade"))) {
                final double D = dur;
                fx.add((RgbMatrix) (pts, hdr) -> {
                    double t = pts / 1e6;
                    double a = Math.max(0, Math.max(1 - t / 0.4, 1 - (D - t) / 0.4));
                    float k = (float) (1 - Math.min(1, a));
                    return new float[]{k, 0, 0, 0, 0, k, 0, 0, 0, 0, k, 0, 0, 0, 0, 1};
                });
            }

            MediaItem mi = mediaItemBuilder(ctx, src)
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs((long) (s * 1000)).setEndPositionMs((long) (e * 1000)).build())
                    .build();
            List<EditedMediaItemSequence> seqs = new ArrayList<>();
            if (cut) {
                // Face cam cutaways: the picture alternates between the main video and the face cam, the sound stays continuous from the main video.
                List<Effect> camPre = new ArrayList<>();
                int[] cd = sizeOf(ctx, camUri);
                if (fill && cd != null) {
                    Effect cr = cropFor(cd[0], cd[1], 0);
                    if (cr != null) camPre.add(cr);
                }
                camPre.add(Presentation.createForWidthAndHeight(W, H,
                        fill ? Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP : Presentation.LAYOUT_SCALE_TO_FIT));
                List<EditedMediaItem> shots = new ArrayList<>();
                for (double[] g : segs) {
                    boolean isCam = g[2] > 0;
                    double a = isCam ? g[3] : s + g[0];
                    double b = a + (g[1] - g[0]);
                    MediaItem gi = mediaItemBuilder(ctx, isCam ? camUri : src)
                            .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs((long) (a * 1000)).setEndPositionMs((long) (b * 1000)).build())
                            .build();
                    shots.add(new EditedMediaItem.Builder(gi)
                            .setRemoveAudio(true)
                            .setEffects(new Effects(ImmutableList.<AudioProcessor>of(), ImmutableList.copyOf(isCam ? camPre : reframe(vw, vh, W, H, fill, cropX, track, g[0]))))
                            .setFrameRate(fps)
                            .build());
                }
                seqs.add(new EditedMediaItemSequence(ImmutableList.copyOf(shots)));
                seqs.add(new EditedMediaItemSequence(ImmutableList.of(new EditedMediaItem.Builder(mi).setRemoveVideo(true).setEffects(new Effects(ImmutableList.<AudioProcessor>of(new GainProcessor((float) Math.max(0, Math.min(1.5, spec.optDouble("srcVol", 100) / 100.0)), null, dur, false), spec.optBoolean("voiceEnhance", false) ? new VoiceClarityProcessor() : null), ImmutableList.<Effect>of())).build())));
            } else if (ramp != null) {
                // Speed ramps: the clip is cut into segments, each with its own speed. Picture speed (SpeedChangeEffect) and sound
                // speed (SonicAudioProcessor, keeps the voice pitch) change together. Overlays and the look run on the finished timeline.
                List<EditedMediaItem> parts = new ArrayList<>();
                for (int i = 0; i < ramp.count(); i++) {
                    MediaItem gi = mediaItemBuilder(ctx, src)
                            .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs((long) ((s + ramp.from[i]) * 1000)).setEndPositionMs((long) ((s + ramp.to[i]) * 1000)).build())
                            .build();
                    List<Effect> ve = new ArrayList<>(reframe(vw, vh, W, H, fill, cropX, track, ramp.from[i]));
                    List<AudioProcessor> aeList = new ArrayList<>(); aeList.add(new GainProcessor((float) Math.max(0, Math.min(1.5, spec.optDouble("srcVol", 100) / 100.0)), null, ramp.to[i] - ramp.from[i], false)); if (spec.optBoolean("voiceEnhance", false)) aeList.add(new VoiceClarityProcessor());
                    if (Math.abs(ramp.speed[i] - 1) > 0.01) {
                        ve.add(new SpeedChangeEffect((float) ramp.speed[i]));
                        SonicAudioProcessor so = new SonicAudioProcessor();
                        so.setSpeed((float) ramp.speed[i]);
                        aeList.add(so);
                    }
                    parts.add(new EditedMediaItem.Builder(gi)
                            .setEffects(new Effects(ImmutableList.copyOf(aeList), ImmutableList.copyOf(ve)))
                            .setFrameRate(fps)
                            .build());
                }
                seqs.add(new EditedMediaItemSequence(ImmutableList.copyOf(parts)));
            } else {
                List<Effect> all = new ArrayList<>(pre);
                all.addAll(fx);
                EditedMediaItem video = new EditedMediaItem.Builder(mi)
                        .setEffects(new Effects(ImmutableList.copyOf(sourceAudioProcessors(spec, dur)), ImmutableList.copyOf(all)))
                        .setFrameRate(fps)
                        .build();
                seqs.add(new EditedMediaItemSequence(ImmutableList.of(video)));
            }
            if (musicUri != null) {
                MediaItem mm = mediaItemBuilder(ctx, musicUri)
                        .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(0).setEndPositionMs((long) (dur * 1000)).build())
                        .build();
                float vol = (float) spec.optDouble("vol", 35) / 100f * 0.8f;
                // Voiceover handling: speech spans from the captions' word times, else from the clip's own audio.
                double[] voice = null;
                if (spec.optBoolean("duck", false)) {
                    if (cj != null && cj.length() > 0) voice = speechRanges(cj, s, dur);
                    if (voice == null || voice.length == 0) {
                        voice = Energy.speech(ctx, src, s, s + srcDur);
                        if (voice != null && ramp != null) voice = ramp.mapTimes(voice);
                    }
                }
                EditedMediaItem music = new EditedMediaItem.Builder(mm)
                        .setRemoveVideo(true)
                        .setEffects(new Effects(ImmutableList.<AudioProcessor>of(new GainProcessor(vol, voice, dur, (float) Math.max(0, Math.min(3, spec.optDouble("musicFadeIn", 0.5))), (float) Math.max(0, Math.min(5, spec.optDouble("musicFadeOut", 1.6))))), ImmutableList.<Effect>of()))
                        .build();
                seqs.add(new EditedMediaItemSequence(ImmutableList.of(music)));
            }
            // AI-placed sound effects (impact / whoosh / riser / pop), generated on the spot and mixed in
            JSONArray sfx = ramp != null ? mapSfx(spec.optJSONArray("sfx"), ramp) : spec.optJSONArray("sfx");
            if (sfx != null && sfx.length() > 0) {
                try {
                    File sf = new File(ctx.getCacheDir(), "sfx_" + Integer.toHexString(sfx.toString().hashCode()) + "_" + (int) (dur * 10) + ".wav");
                    if (!sf.exists()) Sfx.generate(sf, dur, sfx);
                    MediaItem sm = mediaItemBuilder(ctx, Uri.fromFile(sf))
                            .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(0).setEndPositionMs((long) (dur * 1000)).build()).build();
                    EditedMediaItem sfxItem = new EditedMediaItem.Builder(sm)
                            .setRemoveVideo(true)
                            .setEffects(new Effects(ImmutableList.<AudioProcessor>of(new GainProcessor(0.8f, null, dur, false)), ImmutableList.<Effect>of()))
                            .build();
                    seqs.add(new EditedMediaItemSequence(ImmutableList.of(sfxItem)));
                } catch (Exception ignored) { }
            }
            Composition.Builder cbuild = new Composition.Builder(seqs);
            if (cut || ramp != null) cbuild.setEffects(new Effects(ImmutableList.<AudioProcessor>of(), ImmutableList.copyOf(fx)));
            Composition composition = cbuild.build();

            int bitrate = Math.max(500_000, Math.min(50_000_000, spec.optInt("bps", spec.optInt("br", 6) * 1_000_000)));
            transformer = new Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(new DefaultEncoderFactory.Builder(ctx)
                            .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                            .build())
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(Composition c, ExportResult r) { finish(null, l); }
                        @Override public void onError(Composition c, ExportResult r, ExportException ex) {
                            finish(ex.getMessage() == null ? ex.toString() : ex.getMessage(), l);
                        }
                    })
                    .build();
            final File out = new File(ctx.getCacheDir(), "clip_" + System.currentTimeMillis() + ".mp4");
            outFile = out;
            transformer.start(composition, out.getAbsolutePath());
            poll(l);
        } catch (Throwable t) {
            finish(t.getMessage() == null ? t.toString() : t.getMessage(), l);
        }
    }

    private File outFile;

    /** Reframe for one shot. Follows the face track when there is one (a time-moving pan), else the single crop position. offset = where this shot starts inside the clip, in video seconds. */
    private static List<Effect> reframe(int vw, int vh, int W, int H, boolean fill, double cropX, final double[][] track, final double offset) {
        List<Effect> r = new ArrayList<>();
        final double f = Math.min(1.0, ((double) W / H) / ((double) vw / vh));   // visible share of the video width
        if (fill && track != null && track.length >= 2 && f < 0.999) {
            final double limit = 1.0 - f;
            r.add((MatrixTransformation) pts -> {
                double face = trackAt(track, offset + pts / 1e6);
                double centre = Math.max(-limit, Math.min(limit, face));
                Matrix m = new Matrix();
                m.setTranslate((float) -centre, 0f);   // moves the picture so the face sits in the middle; Presentation then crops the centre
                return m;
            });
        } else if (fill) {
            Effect cr = cropFor(vw, vh, cropX);
            if (cr != null) r.add(cr);
        }
        r.add(Presentation.createForWidthAndHeight(W, H,
                fill ? Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP : Presentation.LAYOUT_SCALE_TO_FIT));
        return r;
    }

    /** [[seconds, x], ...] sorted by time, or null when there are fewer than two good points. */
    private static double[][] readTrack(JSONArray a) {
        if (a == null || a.length() < 2) return null;
        List<double[]> l = new ArrayList<>();
        for (int i = 0; i < a.length() && i < 200; i++) {
            JSONArray p = a.optJSONArray(i);
            if (p == null || p.length() < 2) continue;
            double t = p.optDouble(0, Double.NaN), x = p.optDouble(1, Double.NaN);
            if (Double.isNaN(t) || Double.isNaN(x) || t < 0) continue;
            l.add(new double[]{t, Math.max(-1, Math.min(1, x))});
        }
        if (l.size() < 2) return null;
        java.util.Collections.sort(l, (p, q) -> Double.compare(p[0], q[0]));
        return l.toArray(new double[0][]);
    }

    /** Camera position at time t: straight line between points, held before the first and after the last. */
    static double trackAt(double[][] tr, double t) {
        if (t <= tr[0][0]) return tr[0][1];
        if (t >= tr[tr.length - 1][0]) return tr[tr.length - 1][1];
        int lo = 0, hi = tr.length - 1;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (tr[mid][0] <= t) lo = mid; else hi = mid; }
        double k = (t - tr[lo][0]) / Math.max(1e-6, tr[hi][0] - tr[lo][0]);
        return tr[lo][1] + (tr[hi][1] - tr[lo][1]) * k;
    }

    /** Sound-effect times are seconds in the clip; with speed ramps they move to where the moment lands in the finished clip. */
    private static JSONArray mapSfx(JSONArray a, SpeedRamp ramp) throws Exception {
        if (a == null) return null;
        JSONArray r = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            r.put(new JSONObject().put("t", ramp.out(o.optDouble("t", 0))).put("type", o.optString("type", "pop")));
        }
        return r;
    }

    private static Effect cropFor(int vw, int vh, double cropX) {
        float aspect = (float) vw / (float) vh;
        if (aspect > 9f / 16f) {
            float f = (9f / 16f) / aspect;
            float center = (float) Math.max(-1, Math.min(1, cropX)) * (1f - f);
            return new Crop(center - f, center + f, -1f, 1f);
        }
        return null;
    }

    /** {width, height} after rotation, or null when the file cannot be read. */
    private static int[] sizeOf(Context ctx, Uri u) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, u);
            int w = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String ro = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rot = ro == null ? 0 : Integer.parseInt(ro);
            return rot == 90 || rot == 270 ? new int[]{h, w} : new int[]{w, h};
        } catch (Exception e) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    /** Plan of alternating shots: {start, end, 1 = face cam / 0 = main video, face cam position}. Times are seconds from the clip start. Null when the face cam cannot be read. */
    private static List<double[]> cutPlan(Context ctx, Uri cam, double dur, double camLen, double mainLen) {
        double camDur;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, cam);
            camDur = Double.parseDouble(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0;
        } catch (Exception e) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        if (camDur < 1.5) return null;
        camLen = Math.max(0.8, Math.min(5, camLen));
        mainLen = Math.max(1.2, Math.min(8, mainLen));
        camLen = Math.min(camLen, (camDur - 0.05) / 1.3);
        List<double[]> out = new ArrayList<>();
        double t = 0, pos = 0;
        boolean isCam = true;   // the clip opens on the face cam
        while (t < dur - 0.05) {
            double len = isCam ? (out.isEmpty() ? camLen * 1.3 : camLen) : mainLen;
            double end = Math.min(dur, t + len);
            if (dur - end < 0.7) end = dur;
            if (isCam) {
                double l = Math.min(end - t, camDur - 0.05);
                end = t + l;
                if (pos + l > camDur - 0.05) pos = 0;
                out.add(new double[]{t, end, 1, pos});
                pos += l;
            } else {
                out.add(new double[]{t, end, 0, 0});
            }
            t = end;
            isCam = !isCam;
        }
        return out;
    }

    /** Adds the start time of every shot (except the first) to the punch moments, so each cut gets a zoom + flash. */
    private static double[] withCuts(double[] pk, List<double[]> segs) {
        if (segs == null || segs.size() < 2) return pk;
        double[] r = java.util.Arrays.copyOf(pk, pk.length + segs.size() - 1);
        for (int i = 1; i < segs.size(); i++) r[pk.length + i - 1] = segs.get(i)[0];
        java.util.Arrays.sort(r);
        return r;
    }

    /** Seconds since the latest punch at or before t, or -1 when none happened in the last second. */
    static double sinceLast(double[] pk, double t) {
        double best = -1;
        for (double p : pk) { if (p <= t) best = p; else break; }
        return best < 0 || t - best > 1.0 ? -1 : t - best;
    }

    private static double[] readPunches(JSONArray a) {
        if (a == null) return new double[0];
        double[] r = new double[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = a.optDouble(i, 0);
        java.util.Arrays.sort(r);
        return r;
    }

    private void finish(String err, Listener l) {
        if (finished) return;
        finished = true;
        h.removeCallbacksAndMessages(null);
        if (err == null && outFile != null && outFile.length() > 0) l.done(outFile);
        else l.error(err == null ? "The encoder produced an empty file." : err);
    }

    private void poll(final Listener l) {
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (finished || transformer == null) return;
                ProgressHolder ph = new ProgressHolder();
                if (transformer.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) l.progress(ph.progress);
                h.postDelayed(this, 350);
            }
        }, 350);
    }

    void cancel() {
        boolean wasRunning = !finished;
        finished = true;
        h.removeCallbacksAndMessages(null);
        if (transformer != null) { try { transformer.cancel(); } catch (Exception ignored) { } }
        if (outFile != null) outFile.delete();
        if (wasRunning && listener != null) listener.error("Export cancelled.");
    }

    private static List<Painter.Cue> parseCues(JSONArray a) throws Exception {
        List<Painter.Cue> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Painter.Cue c = new Painter.Cue(o.getDouble("a"), o.getDouble("b"));
            JSONArray w = o.getJSONArray("w");
            for (int k = 0; k < w.length(); k++) {
                JSONArray x = w.getJSONArray(k);
                c.words.add(new Painter.W(x.getString(0), x.getDouble(1), x.getDouble(2)));
            }
            if (!c.words.isEmpty()) out.add(c);
        }
        return out;
    }

    // ------------------------------------------------------------------ overlays

    /** Wide divider bloom whose brightness kicks on every punch (or on the beat when there are none). */
    private static final class PulseOverlay extends BitmapOverlay {
        private final Bitmap bmp; private final double bpm; private final double[] pk;
        PulseOverlay(Bitmap bmp, double bpm, double[] pk) { this.bmp = bmp; this.bpm = bpm; this.pk = pk; }

        @Override public Bitmap getBitmap(long presentationTimeUs) { return bmp; }

        @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
            double t = presentationTimeUs / 1e6, d = sinceLast(pk, t);
            double env = d >= 0 ? Math.exp(-d / 0.25) : Math.pow(Math.max(0, Math.cos(2 * Math.PI * t * bpm / 60.0)), 8);
            return new OverlaySettings.Builder().setAlphaScale((float) (0.35 + 0.65 * env)).build();
        }
    }

    /** The 3D hook title in the top bar: pops in with an overshoot, holds, then fades. */
    private static final class HookOverlay extends BitmapOverlay {
        private final Bitmap bmp, empty = Painter.transparent(); private final float cy;
        HookOverlay(int W, float maxH, float topFrac, String text) {
            bmp = Painter.hookTitle(W, maxH, text); cy = 1f - topFrac;
        }

        @Override public Bitmap getBitmap(long presentationTimeUs) { return presentationTimeUs / 1e6 < 4.2 ? bmp : empty; }

        @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
            double t = presentationTimeUs / 1e6;
            float sc = 1f;
            if (t < 0.45) {
                double p = t / 0.45, c1 = 1.70158, c3 = c1 + 1, q = p - 1;
                sc = (float) (0.55 + 0.45 * (1 + c3 * q * q * q + c1 * q * q));
            }
            float a = (float) (t < 0.2 ? t / 0.2 : t > 3.6 ? Math.max(0, 1 - (t - 3.6) / 0.5) : 1);
            return new OverlaySettings.Builder()
                    .setBackgroundFrameAnchor(0f, cy).setOverlayFrameAnchor(0f, 0f)
                    .setScale(sc, sc).setAlphaScale(a).build();
        }
    }

    /** Neon story title: types in letter by letter (yellow first line, glowing green second line), holds, then fades. */
    private static final class NeonTypeOverlay extends BitmapOverlay {
        private static final double CPS = 16.0, START = 0.25, END = 4.6;
        private final int W; private final String l1, l2;
        private final Bitmap empty = Painter.transparent();
        private final LruCache<Integer, Bitmap> cache = new LruCache<>(80);

        NeonTypeOverlay(int W, String[] lines) { this.W = W; this.l1 = lines[0]; this.l2 = lines[1]; }

        @Override public Bitmap getBitmap(long presentationTimeUs) {
            double t = presentationTimeUs / 1e6;
            int total = l1.length() + l2.length();
            if (t < START || t > END || total == 0) return empty;
            int n = (int) Math.min(total, Math.floor((t - START) * CPS) + 1);
            Bitmap b = cache.get(n);
            if (b == null) {
                b = Painter.neonTitle(W, l1, l2, Math.min(n, l1.length()), Math.max(0, n - l1.length()));
                cache.put(n, b);
            }
            return b;
        }

        @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
            double t = presentationTimeUs / 1e6;
            float a = (float) (t > END - 0.4 ? Math.max(0, (END - t) / 0.4) : 1);
            return new OverlaySettings.Builder()
                    .setBackgroundFrameAnchor(0f, -0.20f).setOverlayFrameAnchor(0f, 0f)
                    .setAlphaScale(a).build();
        }
    }

    /** 3D word-by-word captions: each page springs in, the spoken word pops, tilts and glows. */
    private static final class CaptionOverlay extends BitmapOverlay {
        private final int W; private final double clipStart; private final List<Painter.Cue> cues;
        private final String style; private final Typeface tf; private final boolean rtl;
        private final LruCache<Long, Bitmap> cache = new LruCache<>(24);
        private final Bitmap empty = Painter.transparent();

        private final float anchorY, sizeScale;

        CaptionOverlay(int W, double clipStart, List<Painter.Cue> cues, String style, Typeface tf, boolean rtl, float anchorY, float sizeScale) {
            this.W = W; this.clipStart = clipStart; this.cues = cues; this.style = style; this.tf = tf; this.rtl = rtl;
            this.anchorY = anchorY; this.sizeScale = sizeScale;
        }

        private int cueAt(double t) {
            for (int i = 0; i < cues.size(); i++) {
                Painter.Cue c = cues.get(i);
                if (t >= c.a - 0.02 && t <= c.b + 0.25) return i;
            }
            return -1;
        }

        @Override public Bitmap getBitmap(long presentationTimeUs) {
            double t = presentationTimeUs / 1e6 + clipStart;
            int ci = cueAt(t);
            if (ci < 0) return empty;
            Painter.Cue c = cues.get(ci);
            int act = 0;
            for (int k = 0; k < c.words.size(); k++) if (t >= c.words.get(k).a) act = k;
            double pop = (t - c.words.get(act).a) / 0.20;
            int step = (int) Math.max(0, Math.min(8, Math.round(pop * 8)));
            long key = ((long) ci << 16) | ((long) act << 4) | step;
            Bitmap b = cache.get(key);
            if (b == null) {
                List<String> ws = new ArrayList<>();
                for (Painter.W w : c.words) ws.add(w.w);
                b = Painter.caption(W, ws, act, step / 8f, style, tf, rtl);
                cache.put(key, b);
            }
            return b;
        }

        @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
            double t = presentationTimeUs / 1e6 + clipStart;
            int ci = cueAt(t);
            float sc = 1f, a = 1f;
            if (ci >= 0) {
                double age = t - cues.get(ci).a;
                if (age < 0.2) {
                    double p = Math.max(0, age) / 0.2, c1 = 1.70158, c3 = c1 + 1, q = p - 1;
                    sc = (float) (0.62 + 0.38 * (1 + c3 * q * q * q + c1 * q * q));
                    a = (float) Math.min(1, age / 0.08);
                }
            }
            return new OverlaySettings.Builder()
                    .setBackgroundFrameAnchor(0f, anchorY).setOverlayFrameAnchor(0f, 1f)
                    .setScale(sc * sizeScale, sc * sizeScale).setAlphaScale(a).build();
        }
    }

    /** The editor credit ROOHU X KHAN: one clean glowing line that changes colour every ~1.6 s like the reference edit. */
    private static final class NameOverlay extends BitmapOverlay {
        private static final double PHASE_SECONDS = 1.6;
        private final Bitmap[] frames = new Bitmap[Painter.CREDIT_PHASES];
        private final float sizePct, opacity; private final int W;
        private final float ax, ay, barTop;   // barTop > 0 => centred inside the reaction look's bottom bar
        private final boolean mid;            // "mc": centred in the lower middle of the frame (reference position)

        NameOverlay(int W, float sizePct, float opacity, String pos, float barTop) {
            this.W = W; this.barTop = barTop; this.opacity = Math.max(0.5f, opacity);
            this.sizePct = barTop > 0 ? sizePct * 1.1f : sizePct;
            this.mid = "mc".equals(pos);
            ax = pos.length() > 1 && pos.charAt(1) == 'l' ? -1f : 1f;
            ay = pos.length() > 0 && pos.charAt(0) == 't' ? 1f : -1f;
        }

        @Override public Bitmap getBitmap(long presentationTimeUs) {
            double t = presentationTimeUs / 1e6;
            int idx = (int) (t / PHASE_SECONDS) % Painter.CREDIT_PHASES;
            if (frames[idx] == null) frames[idx] = Painter.nameCredit(W, sizePct, idx);
            return frames[idx];
        }

        @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
            double t = presentationTimeUs / 1e6;
            float in = (float) Math.min(1, t / 0.5);
            float bob = (float) (Math.sin(t * 2.2) * 0.004);
            OverlaySettings.Builder b = new OverlaySettings.Builder().setAlphaScale(opacity * in);
            if (barTop > 0) {
                b.setBackgroundFrameAnchor(0f, -barTop + bob).setOverlayFrameAnchor(0f, 0f);
            } else if (mid) {
                b.setBackgroundFrameAnchor(0f, -0.42f + bob).setOverlayFrameAnchor(0f, 0f);
            } else {
                b.setBackgroundFrameAnchor(ax * 0.95f, ay * (ay > 0 ? 0.92f : 0.90f) + bob).setOverlayFrameAnchor(ax, ay);
            }
            return b.build();
        }
    }

    private static final class ProgressOverlay extends BitmapOverlay {
        private final int W, H; private final double dur;
        private final Bitmap[] cache = new Bitmap[51];
        private final OverlaySettings settings = new OverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, 0.985f).setOverlayFrameAnchor(0f, 1f).build();

        ProgressOverlay(int W, int H, double dur) { this.W = W; this.H = H; this.dur = dur; }

        @Override public Bitmap getBitmap(long presentationTimeUs) {
            int step = (int) Math.max(0, Math.min(50, Math.round(presentationTimeUs / 1e6 / dur * 50)));
            if (cache[step] == null) cache[step] = Painter.progress(W, H, step / 50f);
            return cache[step];
        }

        @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) { return settings; }
    }

    /** Speech spans (pairs of start,end seconds from the clip start) built from the caption word timings; gaps under 0.45 s are bridged. */
    static double[] speechRanges(JSONArray cj, double clipStart, double dur) {
        List<double[]> iv = new ArrayList<>();
        for (int i = 0; i < cj.length(); i++) {
            JSONObject c = cj.optJSONObject(i);
            if (c == null) continue;
            JSONArray w = c.optJSONArray("w");
            boolean any = false;
            if (w != null) for (int k = 0; k < w.length(); k++) {
                JSONArray x = w.optJSONArray(k);
                if (x == null || x.length() < 3) continue;
                double a = x.optDouble(1, -1), b = x.optDouble(2, -1);
                if (a >= 0 && b > a) { iv.add(new double[]{a - clipStart, b - clipStart}); any = true; }
            }
            if (!any) {
                double a = c.optDouble("a", -1), b = c.optDouble("b", -1);
                if (a >= 0 && b > a) iv.add(new double[]{a - clipStart, b - clipStart});
            }
        }
        java.util.Collections.sort(iv, (p, q) -> Double.compare(p[0], q[0]));
        List<double[]> m = new ArrayList<>();
        for (double[] v : iv) {
            double a = Math.max(0, v[0] - 0.12), b = Math.min(dur, v[1] + 0.05);
            if (b <= a) continue;
            double[] last = m.isEmpty() ? null : m.get(m.size() - 1);
            if (last != null && a - last[1] < 0.45) last[1] = Math.max(last[1], b);
            else m.add(new double[]{a, b});
        }
        double[] out = new double[m.size() * 2];
        for (int i = 0; i < m.size(); i++) { out[2 * i] = m.get(i)[0]; out[2 * i + 1] = m.get(i)[1]; }
        return out;
    }

    /** Light voice-clarity filter: trims low rumble and gently controls peaks. Not a denoiser. */
    static final class VoiceClarityProcessor extends BaseAudioProcessor {
        private int channels = 2, sampleRate = 44100;
        private float[] prevX = new float[2], prevY = new float[2];
        private float hpAlpha = 0.98f;
        @Override protected AudioFormat onConfigure(AudioFormat in) throws UnhandledAudioFormatException {
            if (in.encoding != C.ENCODING_PCM_16BIT) throw new UnhandledAudioFormatException(in);
            channels = Math.max(1, in.channelCount);
            sampleRate = Math.max(8000, in.sampleRate);
            prevX = new float[channels]; prevY = new float[channels];
            hpAlpha = (float) Math.exp(-2.0 * Math.PI * 95.0 / sampleRate);
            return in;
        }
        @Override public void queueInput(ByteBuffer input) {
            int n = input.remaining();
            ByteBuffer output = replaceOutputBuffer(n);
            output.order(ByteOrder.nativeOrder());
            ByteOrder previousOrder = input.order();
            input.order(ByteOrder.nativeOrder());
            int ch = 0;
            while (input.remaining() >= 2) {
                float x = input.getShort() / 32768f;
                float hp = x - prevX[ch] + hpAlpha * prevY[ch];
                prevX[ch] = x; prevY[ch] = hp;
                float y = x + 0.55f * (hp - x);
                float a = Math.abs(y);
                if (a > 0.62f) y = Math.copySign(0.62f + (a - 0.62f) * 0.48f, y);
                y = Math.max(-0.98f, Math.min(0.98f, y));
                output.putShort((short) Math.round(y * 32767f));
                ch = (ch + 1) % channels;
            }
            input.order(previousOrder);
            output.flip();
        }
    }

    private static List<AudioProcessor> sourceAudioProcessors(JSONObject spec, double duration) {
        List<AudioProcessor> processors = new ArrayList<>();
        processors.add(new GainProcessor((float) Math.max(0, Math.min(1.5,
                spec.optDouble("srcVol", 100) / 100.0)), null, duration, false));
        if (spec.optBoolean("voiceEnhance", false)) processors.add(new VoiceClarityProcessor());
        return processors;
    }

    /** PCM16 volume for the music bed. With speech spans it also ducks the music while someone talks (fast attack, slow release). */
    static final class GainProcessor extends BaseAudioProcessor {
        private static final float DUCK = 0.22f;          // music level while someone talks (fraction of the normal level)
        private static final double FADE_IN = 0.5, FADE_OUT = 1.6;
        private final float gain;
        private final double[] speech;
        private final double total;
        private int ch = 2, sr = 44100, idx = 0;
        private long frame = 0;
        private float env = 1f, atk = 0.01f, rel = 0.001f;

        /** speech == null: unknown (no ducking). empty array: the clip has no voice, so the music plays a bit louder. */
        private final boolean fades;
        private float fadeInSeconds, fadeOutSeconds;

        GainProcessor(float gain, double[] speech, double total) { this(gain, speech, total, 0.5f, 1.6f); }

        GainProcessor(float gain, double[] speech, double total, float fadeInSeconds, float fadeOutSeconds) {
            this(gain, speech, total, true);
            this.fadeInSeconds = Math.max(0f, fadeInSeconds);
            this.fadeOutSeconds = Math.max(0f, fadeOutSeconds);
        }

        GainProcessor(float gain, double[] speech, double total, boolean fades) {
            this.fades = fades;
            this.fadeInSeconds = 0.5f;
            this.fadeOutSeconds = 1.6f;
            boolean none = speech != null && speech.length == 0;
            this.gain = none ? Math.min(1f, gain * 1.5f) : gain;
            this.speech = speech == null || speech.length == 0 ? null : speech;
            this.total = total;
        }

        @Override protected AudioFormat onConfigure(AudioFormat in) throws UnhandledAudioFormatException {
            if (in.encoding != C.ENCODING_PCM_16BIT) throw new UnhandledAudioFormatException(in);
            ch = Math.max(1, in.channelCount);
            sr = Math.max(8000, in.sampleRate);
            atk = (float) (1 - Math.exp(-1.0 / (0.10 * sr)));
            rel = (float) (1 - Math.exp(-1.0 / (0.55 * sr)));
            frame = 0; idx = 0; env = 1f;
            return in;
        }

        @Override public void queueInput(ByteBuffer input) {
            int n = input.remaining();
            ByteBuffer out = replaceOutputBuffer(n);
            out.order(ByteOrder.nativeOrder());
            ByteOrder keep = input.order();
            input.order(ByteOrder.nativeOrder());
            while (input.remaining() >= 2 * ch) {
                float target = 1f;
                if (speech != null) {
                    double t = frame / (double) sr;
                    while (idx < speech.length && speech[idx + 1] < t) idx += 2;
                    if (idx < speech.length && t >= speech[idx]) target = DUCK;
                }
                env += (target - env) * (target < env ? atk : rel);
                double tt = frame / (double) sr;
                float fade = 1f;
                if (fades) {
                    double in = fadeInSeconds <= 0f ? 1.0 : tt / fadeInSeconds;
                    double fadeOutRatio = fadeOutSeconds <= 0f ? 1.0 : Math.max(0, total - tt) / fadeOutSeconds;
                    fade = (float) Math.max(0.0, Math.min(1.0, Math.min(in, fadeOutRatio)));
                }
                float g = gain * env * fade;
                for (int c = 0; c < ch; c++) {
                    int v = Math.round(input.getShort() * g);
                    out.putShort((short) Math.max(-32768, Math.min(32767, v)));
                }
                frame++;
            }
            while (input.hasRemaining()) out.put(input.get());
            input.order(keep);
            out.flip();
        }
    }

    // ------------------------------------------------------------------ saving to the Gallery

    /** Copies the rendered file into Movies/RoohuClipForge so it shows up in the Gallery. Returns its content/file URI. */
    static Uri publish(Context ctx, File file, String displayName) throws IOException {
        String name = displayName.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (name.isEmpty()) name = "clip";
        if (!name.toLowerCase().endsWith(".mp4")) name += ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RoohuClipForge");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = ctx.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Could not create the video in the Gallery.");
            try (InputStream in = new FileInputStream(file); OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IOException("Could not open the Gallery file.");
                copy(in, os);
            } catch (IOException ex) {
                ctx.getContentResolver().delete(uri, null, null);
                throw ex;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, done, null, null);
            file.delete();
            return uri;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "RoohuClipForge");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create the Movies/RoohuClipForge folder (storage permission needed).");
            File dst = new File(dir, name);
            try (InputStream in = new FileInputStream(file); OutputStream os = new FileOutputStream(dst)) { copy(in, os); }
            file.delete();
            MediaScannerConnection.scanFile(ctx, new String[]{dst.getAbsolutePath()}, new String[]{"video/mp4"}, null);
            return Uri.fromFile(dst);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }
}
