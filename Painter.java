package com.roohu.clipforge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/** Draws every overlay (animated captions, the 3D name credit, progress bar, vignette) as bitmaps. */
final class Painter {
    static final int ACCENT = 0xFFFFC83D;
    static final int ORANGE = 0xFFFF8F3F;
    static final int PINK = 0xFFFF3D81;
    static final int CYAN = 0xFF5FE3F0;

    /** One spoken word with its start/end time in seconds (source video time). */
    static final class W {
        final String w; final double a, b;
        W(String w, double a, double b) { this.w = w; this.a = a; this.b = b; }
    }

    /** A caption page: a few words shown together. */
    static final class Cue {
        final double a, b; final List<W> words = new ArrayList<>();
        Cue(double a, double b) { this.a = a; this.b = b; }
    }

    static Typeface face(String font) { return Fonts.get(font); }

    /** True when every letter is Latin/digits/punctuation, so the display font can draw it. */
    static boolean latin(String w) {
        for (int i = 0; i < w.length(); i++) if (w.charAt(i) > 0x024F && !Character.isWhitespace(w.charAt(i))) {
            char ch = w.charAt(i);
            if (ch < 0x2000 || ch > 0x2BFF) return false;
        }
        return true;
    }

    static boolean arabicScript(String w) {
        for (int i = 0; i < w.length(); i++) if (w.charAt(i) >= 0x0600 && w.charAt(i) <= 0x06FF) return true;
        return false;
    }

    /** Typeface for one word: the display font for Latin, a bold script font for everything else. */
    static Typeface faceFor(String word, Typeface base) {
        if (latin(word)) return base;
        if (arabicScript(word) && Fonts.has("Tajawal")) return Fonts.get("Tajawal");
        return Typeface.create("sans-serif", Typeface.BOLD);
    }

    static Bitmap transparent() {
        return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
    }

    /** Palette of a caption style: {inactiveTop, inactiveBottom, inactiveSide, activeTop, activeBottom, activeSide, glow}. */
    private static int[] palette(String style) {
        switch (style) {
            case "fire": return new int[]{0xFFFFFFFF, 0xFFE9E9E9, 0xFF2A0A0A, 0xFFFFC857, 0xFFFF3B1F, 0xFF5B0A12, 0xE6FF5A1F};
            case "ice":  return new int[]{0xFFFFFFFF, 0xFFE3F4FF, 0xFF0B2A44, 0xFFE4FBFF, 0xFF21C1F5, 0xFF0B3A5B, 0xE62EC8FF};
            case "gold": return new int[]{0xFFFFF3B0, 0xFFE8A91C, 0xFF5C3A00, 0xFFFFFFFF, 0xFFFFE680, 0xFF8A5A00, 0xE6FFD24D};
            case "neon": return new int[]{0xFFFFFFFF, 0xFFFFD6F6, 0xFF3A0A3F, 0xFFE9FFFF, 0xFF3DF5FF, 0xFF0A4A55, 0xE600F0FF};
            case "sport": return new int[]{0xFFE8EDF8, 0xFF8E9BB8, 0xFF0A0F1A, 0xFFFFFFFF, 0xFFDDE8FF, 0xFF0A0F1A, 0xE69CC4FF};
            default:     return new int[]{0xFFFFFFFF, 0xFFE8E8E8, 0xFF1B1B1B, 0xFFFFF176, 0xFFFFB400, 0xFF6F4300, 0xE6FFC83D};
        }
    }

    /**
     * Caption bitmap in 3D: stacked extrusion, black outline, gradient face, specular highlight and a glow on the
     * spoken word. Styles: pop, kara, fire, ice, gold, neon, bar, line. `active` is the spoken word, `pop` is 0..1
     * bounce progress. rtl lays the words of each line right-to-left (Urdu, Arabic...).
     */
    static Bitmap caption(int canvasW, List<String> words, int active, float pop, String style, Typeface tf, boolean rtl) {
        boolean sport = "sport".equals(style);
        boolean clean = "clean".equals(style);   // M27: the default look: white words, the spoken word lit yellow, thick outline, small pop
        if (sport) tf = Fonts.get("Michroma");   // wide squared sci-fi letters, like the Speed x Ronaldo edit
        float fs = canvasW * (sport ? 0.050f : clean ? 0.078f : 0.092f);
        final float track = sport ? 0.16f : 0f, skew = sport ? -0.2f : 0f;
        if (sport) { words = new ArrayList<>(words); for (int w = 0; w < words.size(); w++) words.set(w, words.get(w).toUpperCase(java.util.Locale.ROOT)); }
        int[] pal = palette(style);
        boolean bar = "bar".equals(style), kara = "kara".equals(style);
        boolean reveal = "reveal".equals(style) || "hype".equals(style), hype = "hype".equals(style);
        final int[] HYPE = {0xFF39FF88, 0xFFFFE23D, 0xFFFF4FA3, 0xFF4FE3FF, 0xFFFF8F3F};
        final int[] MARK = {0xFF39FF5A, 0xFFFFE23D, 0xFFFF3B30};   // green, yellow, red
        boolean mark = "mark".equals(style);
        Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);
        m.setTextSize(fs); m.setLetterSpacing(track);
        float maxW = canvasW * 0.88f;
        Typeface[] tfs = new Typeface[words.size()];
        float[] ww = new float[words.size()];
        for (int i = 0; i < ww.length; i++) {
            tfs[i] = faceFor(words.get(i), tf);
            m.setTypeface(tfs[i]);
            ww[i] = m.measureText(words.get(i));
        }
        m.setTypeface(tf);
        float space = m.measureText(" ");
        if (space < fs * 0.2f) space = fs * 0.28f;
        List<List<Integer>> lines = new ArrayList<>();
        List<Integer> cur = new ArrayList<>();
        float curW = 0;
        for (int i = 0; i < ww.length; i++) {
            float add = (cur.isEmpty() ? 0 : space) + ww[i];
            if (!cur.isEmpty() && curW + add > maxW) { lines.add(cur); cur = new ArrayList<>(); curW = 0; add = ww[i]; }
            cur.add(i); curW += add;
        }
        if (!cur.isEmpty()) lines.add(cur);
        int depth = 0;   // flat, clean letters: no stacked extrusion (that was the cheap look)
        float lh = fs * 1.38f, padY = fs * 0.55f;
        int h = (int) Math.ceil(lines.size() * lh + padY * 1.6f + depth);
        Bitmap bmp = Bitmap.createBitmap(canvasW, Math.max(h, 8), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xCC000000);

        float y = padY + fs * 0.95f;
        for (List<Integer> line : lines) {
            float lw = 0;
            for (int k = 0; k < line.size(); k++) lw += ww[line.get(k)] + (k > 0 ? space : 0);
            float x = (canvasW - lw) / 2f;
            if (bar) c.drawRoundRect(new RectF(x - fs * 0.4f, y - fs * 0.95f, x + lw + fs * 0.4f, y + fs * 0.38f), fs * 0.3f, fs * 0.3f, bg);
            List<Integer> order = new ArrayList<>(line);
            if (rtl) java.util.Collections.reverse(order);
            for (int i : order) {
                boolean act = i == active;
                boolean lit = kara ? i <= active : act;
                float bounce = act ? (float) Math.sin(Math.min(1f, pop) * Math.PI) : 0f;
                float sc = 1f + (sport ? 0.14f : clean ? 0.07f : hype ? 0.42f : mark ? 0.18f : 0.34f) * bounce;
                float tilt = act && !clean ? (rtl ? 1 : -1) * 4f * bounce : 0f;
                int top = lit ? pal[3] : pal[0], bot = lit ? pal[4] : pal[1], side = lit ? pal[5] : pal[2];
                if (bar) { top = lit ? 0xFFFFD54A : 0xFFFFFFFF; bot = top; }
                if (clean) { top = lit ? 0xFFFFE14D : 0xFFFFFFFF; bot = top; side = 0xFF101010; }
                if (reveal && i > active) { x += ww[i] + space; continue; }
                if (hype && act) { int hc = HYPE[i % HYPE.length]; top = hc; bot = hc; side = 0xFF101010; }
                if (mark && act) { int mc = MARK[i % MARK.length]; top = mc; bot = mc; side = 0xFF101010; }
                float rise = (reveal && act) ? (1f - Math.min(1f, pop * 1.4f)) * fs * 0.32f : 0f;
                c.save();
                c.translate(0, rise);
                c.rotate(tilt, x + ww[i] / 2f, y - fs * 0.3f);
                c.scale(sc, sc, x + ww[i] / 2f, y - fs * 0.3f);
                int glowCol = clean ? 0 : act ? (hype ? (HYPE[i % HYPE.length] & 0x00FFFFFF) | 0xCC000000 : mark ? (MARK[i % MARK.length] & 0x00FFFFFF) | 0xCC000000 : (pal[6] & 0x00FFFFFF) | 0xCC000000) : 0;
                cleanText(c, words.get(i), x, y, tfs[i], fs, top, bot, glowCol, !latin(words.get(i)) && !sport, bar ? 0f : sport ? 0.05f : clean ? 0.17f : 0.10f, track, skew);
                c.restore();
                x += ww[i] + space;
            }
            y += lh;
        }
        return bmp;
    }

    /** Clean modern edit text: soft drop shadow, optional neon glow, thin dark edge, smooth two-tone face. No extrusion. */
    static void cleanText(Canvas c, String s, float x, float y, Typeface tf, float fs, int top, int bottom, int glow,
                          boolean fakeBold, float outline) {
        cleanText(c, s, x, y, tf, fs, top, bottom, glow, fakeBold, outline, 0f, 0f);
    }

    static void cleanText(Canvas c, String s, float x, float y, Typeface tf, float fs, int top, int bottom, int glow,
                          boolean fakeBold, float outline, float track, float skew) {
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setTypeface(tf); sh.setTextSize(fs); sh.setLetterSpacing(track); sh.setTextSkewX(skew); sh.setFakeBoldText(fakeBold);
        sh.setColor(0x00000000); sh.setShadowLayer(fs * 0.12f, 0, fs * 0.05f, 0xB0000000);
        c.drawText(s, x, y, sh);
        if (glow != 0) {
            Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
            gp.setTypeface(tf); gp.setTextSize(fs); gp.setLetterSpacing(track); gp.setTextSkewX(skew); gp.setFakeBoldText(fakeBold);
            gp.setColor(glow); gp.setShadowLayer(fs * 0.42f, 0, 0, glow);
            c.drawText(s, x, y, gp);
            c.drawText(s, x, y, gp);
        }
        if (outline > 0f) {
            Paint o = new Paint(Paint.ANTI_ALIAS_FLAG);
            o.setTypeface(tf); o.setTextSize(fs); o.setLetterSpacing(track); o.setTextSkewX(skew); o.setFakeBoldText(fakeBold);
            o.setStyle(Paint.Style.STROKE); o.setStrokeJoin(Paint.Join.ROUND);
            o.setStrokeWidth(fs * outline); o.setColor(0xE6000000);
            c.drawText(s, x, y, o);
        }
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setTypeface(tf); f.setTextSize(fs); f.setLetterSpacing(track); f.setTextSkewX(skew); f.setFakeBoldText(fakeBold);
        f.setShader(new LinearGradient(0, y - fs * 0.80f, 0, y + fs * 0.05f, top, bottom, Shader.TileMode.CLAMP));
        c.drawText(s, x, y, f);
    }

    /** Colour phases of the editor credit, cycling like the reference edit: steel-blue, yellow, white. {top, bottom, glow}. */
    static final int CREDIT_PHASES = 3;
    private static int[] creditPalette(int phase) {
        switch (((phase % CREDIT_PHASES) + CREDIT_PHASES) % CREDIT_PHASES) {
            case 1:  return new int[]{0xFFFFF86B, 0xFFFFD000, 0xCCFFD400};
            case 2:  return new int[]{0xFFFFFFFF, 0xFFD9E4FF, 0xB0BFD4FF};
            default: return new int[]{0xFFE6ECFF, 0xFF8E9BC4, 0xB07F90D8};
        }
    }

    /**
     * The editor credit "ROOHU X KHAN": one clean wide-tracked line in a heavy geometric font, smooth metallic
     * gradient, thin dark edge and a soft coloured glow (the look of the reference edit's credit). phase picks the colour.
     */
    static Bitmap nameCredit(int canvasW, float sizePct, int phase) {
        Typeface tf = Fonts.get("Poppins");
        int[] pal = creditPalette(phase);
        String text = "ROOHU X KHAN";
        float fs = canvasW * 0.046f * sizePct / 100f, maxW = canvasW * 0.80f;
        Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);
        m.setTypeface(tf);
        float track = 0.16f;
        for (int i = 0; i < 30; i++) {
            m.setTextSize(fs); m.setLetterSpacing(track);
            if (m.measureText(text) <= maxW) break;
            fs *= 0.94f;
        }
        m.setTextSize(fs); m.setLetterSpacing(track);
        float tw = m.measureText(text);
        int pad = Math.round(fs * 0.9f);
        int bw = (int) Math.ceil(tw + pad * 2), bh = (int) Math.ceil(fs * 1.25f + pad * 2);
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float x = pad, y = pad + fs * 0.95f;
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setTypeface(tf); g.setTextSize(fs); g.setLetterSpacing(track);
        g.setColor(pal[2]); g.setShadowLayer(fs * 0.55f, 0, 0, pal[2]);
        c.drawText(text, x, y, g);
        Paint o = new Paint(Paint.ANTI_ALIAS_FLAG);
        o.setTypeface(tf); o.setTextSize(fs); o.setLetterSpacing(track);
        o.setStyle(Paint.Style.STROKE); o.setStrokeJoin(Paint.Join.ROUND);
        o.setStrokeWidth(fs * 0.09f); o.setColor(0xCC000000);
        c.drawText(text, x, y, o);
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setTypeface(tf); f.setTextSize(fs); f.setLetterSpacing(track);
        f.setShader(new LinearGradient(0, y - fs * 0.75f, 0, y + fs * 0.05f, pal[0], pal[1], Shader.TileMode.CLAMP));
        c.drawText(text, x, y, f);
        return bmp;
    }

    static Bitmap progress(int w, int frameH, float p) {
        int h = Math.max(6, (int) (frameH * 0.006f));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint bg = new Paint(); bg.setColor(0x40FFFFFF);
        c.drawRect(0, 0, w, h, bg);
        Paint fg = new Paint(); fg.setColor(ORANGE);
        c.drawRect(0, 0, w * Math.max(0f, Math.min(1f, p)), h, fg);
        return b;
    }

    static Bitmap vignette(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(w / 2f, h / 2f, h * 0.75f,
                new int[]{0x00000000, 0x00000000, 0x59000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return b;
    }

    // ------------------------------------------------------------------ reaction look (Phase 1)

    /** Opaque black bars top and bottom with a crisp glowing divider on each inner edge. Full-frame bitmap. */
    static Bitmap reactFrame(int W, int H, float topFrac, float botFrac) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        int top = Math.round(H * topFrac), bot = Math.round(H * botFrac);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        c.drawRect(0, 0, W, top, p);
        c.drawRect(0, H - bot, W, H, p);
        glowLine(c, W, top, W * 0.012f, 0xB0FFFFFF);
        glowLine(c, W, H - bot, W * 0.012f, 0xB0FFFFFF);
        float core = Math.max(3f, W * 0.006f);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(Color.WHITE);
        c.drawRect(0, top - core / 2f, W, top + core / 2f, line);
        c.drawRect(0, H - bot - core / 2f, W, H - bot + core / 2f, line);
        return bmp;
    }

    /** Wide soft bloom around both dividers (transparent elsewhere). Its opacity is pulsed on loud moments. */
    static Bitmap reactGlow(int W, int H, float topFrac, float botFrac) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        glowLine(c, W, Math.round(H * topFrac), W * 0.06f, 0x90BFE9FF);
        glowLine(c, W, H - Math.round(H * botFrac), W * 0.06f, 0x90BFE9FF);
        return bmp;
    }

    private static void glowLine(Canvas c, int W, float y, float half, int peak) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, y - half, 0, y + half,
                new int[]{peak & 0x00FFFFFF, peak, peak & 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, y - half, W, y + half, p);
    }

    /** Splits a hook into at most two balanced lines. */
    private static String[] splitTwo(String s) {
        String[] w = s.split("\\s+");
        if (w.length < 3 || s.length() <= 16) return new String[]{s};
        int best = 1, bestDiff = Integer.MAX_VALUE;
        for (int i = 1; i < w.length; i++) {
            int a = 0, b = 0;
            for (int k = 0; k < i; k++) a += w[k].length() + 1;
            for (int k = i; k < w.length; k++) b += w[k].length() + 1;
            if (Math.abs(a - b) < bestDiff) { bestDiff = Math.abs(a - b); best = i; }
        }
        StringBuilder l1 = new StringBuilder(), l2 = new StringBuilder();
        for (int i = 0; i < w.length; i++) {
            StringBuilder t = i < best ? l1 : l2;
            if (t.length() > 0) t.append(' ');
            t.append(w[i]);
        }
        return new String[]{l1.toString(), l2.toString()};
    }

    /** Bold 3D hook title (red first line, yellow second) shown in the top bar. maxH keeps it inside the bar. */
    static Bitmap hookTitle(int W, float maxH, String text) {
        String up = text.trim().toUpperCase(java.util.Locale.ROOT);
        if (up.length() > 44) up = up.substring(0, 43).trim() + "\u2026";
        String[] lines = splitTwo(up);
        Typeface tf = face("Poppins");
        float fs = W * 0.072f, maxW = W * 0.92f;
        Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);
        m.setTypeface(tf); m.setLetterSpacing(NEON_TRACK);
        for (int i = 0; i < 30; i++) {
            m.setTextSize(fs);
            float wmax = 0;
            for (String l : lines) wmax = Math.max(wmax, m.measureText(l));
            if (wmax <= maxW && lines.length * fs * 1.28f + fs * 0.3f <= maxH) break;
            fs *= 0.93f;
        }
        int depth = Math.max(3, Math.round(fs * 0.10f));
        int h = (int) Math.ceil(lines.length * fs * 1.28f + fs * 0.3f + depth);
        Bitmap bmp = Bitmap.createBitmap(W, Math.max(h, 8), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        m.setTextSize(fs);
        for (int i = 0; i < lines.length; i++) {
            float x = (W - m.measureText(lines[i])) / 2f, y = fs * 1.05f + i * fs * 1.28f;
            if (i == 0 && lines.length > 1) glowText(c, lines[i], x, y, tf, fs, 0xFFFFF35C, 0xFFFFE600, 0xFF5A4A00);
            else glowText(c, lines[i], x, y, tf, fs, 0xFF5CFF6E, 0xFF14FF2E, 0xFF04450C);
        }
        return bmp;
    }


    /** Splits a title into {yellow line, green typed line}. A one-line title is all green. */
    static String[] neonLines(String text) {
        String up = text == null ? "" : text.trim().toUpperCase(java.util.Locale.ROOT);
        if (up.length() > 40) up = up.substring(0, 39).trim() + "\u2026";
        if (up.isEmpty()) return new String[]{"", ""};
        String[] p = splitTwo(up);
        return p.length == 1 ? new String[]{"", p[0]} : new String[]{p[0], p[1]};
    }

    /** Neon story title: yellow glowing first line, green glowing second line. n1 / n2 = letters shown so far (typewriter). */
    private static final float NEON_TRACK = 0.04f;

    static Bitmap neonTitle(int W, String l1, String l2, int n1, int n2) {
        Typeface tf = face("Poppins");
        float fs = W * 0.075f, maxW = W * 0.90f;
        Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);
        m.setTypeface(tf); m.setLetterSpacing(NEON_TRACK);
        for (int i = 0; i < 30; i++) {
            m.setTextSize(fs);
            if (Math.max(m.measureText(l1), m.measureText(l2)) <= maxW) break;
            fs *= 0.93f;
        }
        int pad = Math.round(fs * 0.7f);
        int h = (int) Math.ceil(fs * 2.6f) + pad * 2;
        Bitmap bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        m.setTextSize(fs);
        float y1 = pad + fs, y2 = y1 + fs * 1.22f;
        String s1 = l1.substring(0, Math.max(0, Math.min(n1, l1.length())));
        String s2 = l2.substring(0, Math.max(0, Math.min(n2, l2.length())));
        if (!s1.isEmpty()) glowText(c, s1, (W - m.measureText(l1)) / 2f, y1, tf, fs, 0xFFFFF35C, 0xFFFFE600, 0xFF5A4A00);
        if (!s2.isEmpty()) glowText(c, s2, (W - m.measureText(l2)) / 2f, l1.isEmpty() ? y1 : y2, tf, fs, 0xFF5CFF6E, 0xFF14FF2E, 0xFF04450C);
        return bmp;
    }

    private static void glowText(Canvas c, String s, float x, float y, Typeface tf, float fs, int face, int glow, int edge) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setTypeface(tf); g.setTextSize(fs); g.setLetterSpacing(NEON_TRACK); g.setColor(glow);
        g.setShadowLayer(fs * 0.62f, 0, 0, glow);
        c.drawText(s, x, y, g);
        c.drawText(s, x, y, g);
        c.drawText(s, x, y, g);
        Paint o = new Paint(Paint.ANTI_ALIAS_FLAG);
        o.setTypeface(tf); o.setTextSize(fs); o.setLetterSpacing(NEON_TRACK); o.setStyle(Paint.Style.STROKE);
        o.setStrokeJoin(Paint.Join.ROUND); o.setStrokeWidth(fs * 0.07f); o.setColor(edge);
        c.drawText(s, x, y, o);
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setTypeface(tf); f.setTextSize(fs); f.setLetterSpacing(NEON_TRACK); f.setColor(face);
        c.drawText(s, x, y, f);
    }

    /** 3D-look text: stacked extrusion, black outline, gradient face and a top specular highlight. */
    static void text3d(Canvas c, String s, float x, float y, Typeface tf, float fs, int top, int bottom, int side, int depth) {
        text3d(c, s, x, y, tf, fs, top, bottom, side, depth, 0, false, 0.17f);
    }

    /** Full 3D text. glow = ARGB glow colour (0 none); fakeBold thickens fallback script fonts; outline = stroke width in em. */
    static void text3d(Canvas c, String s, float x, float y, Typeface tf, float fs, int top, int bottom, int side,
                       int depth, int glow, boolean fakeBold, float outline) {
        if (glow != 0) {
            Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
            gp.setTypeface(tf); gp.setTextSize(fs); gp.setFakeBoldText(fakeBold);
            gp.setColor(glow);
            gp.setShadowLayer(fs * 0.38f, 0, 0, glow);
            c.drawText(s, x, y, gp);
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf); p.setTextSize(fs); p.setFakeBoldText(fakeBold);
        p.setStyle(Paint.Style.FILL_AND_STROKE); p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(fs * 0.06f); p.setColor(side);
        for (int i = depth; i >= 1; i--) c.drawText(s, x + i * 0.55f, y + i * 0.95f, p);
        if (outline > 0f) {
            Paint o = new Paint(Paint.ANTI_ALIAS_FLAG);
            o.setTypeface(tf); o.setTextSize(fs); o.setFakeBoldText(fakeBold);
            o.setStyle(Paint.Style.STROKE); o.setStrokeJoin(Paint.Join.ROUND);
            o.setStrokeWidth(fs * outline); o.setColor(Color.BLACK);
            c.drawText(s, x, y, o);
        }
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setTypeface(tf); f.setTextSize(fs); f.setFakeBoldText(fakeBold);
        f.setShader(new LinearGradient(0, y - fs * 0.85f, 0, y + fs * 0.1f, top, bottom, Shader.TileMode.CLAMP));
        c.drawText(s, x, y, f);
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setTypeface(tf); g.setTextSize(fs); g.setFakeBoldText(fakeBold);
        g.setShader(new LinearGradient(0, y - fs * 0.85f, 0, y - fs * 0.38f, 0x80FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawText(s, x, y, g);
    }
}
