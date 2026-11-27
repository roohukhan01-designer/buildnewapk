package com.roohu.clipforge;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Gives native code a plain file it can always read. Picker links (content://) are sometimes temporary or provider-backed
 * and break MediaExtractor / Media3 in odd ways. A copy is made only when it is needed, only once per video, after a free
 * space check, and old copies are removed when a new video is picked.
 */
final class MediaCache {
    private MediaCache() { }

    static File dir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "clipforge_media");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Returns a file:// Uri for the media. file:// input is returned as is. Blocking: call from a worker thread only. */
    static synchronized Uri localize(Context ctx, Uri uri, String label) throws IOException {
        if (uri == null) throw new IOException("No video is selected. Pick the video again.");
        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) return uri;
        if (!"content".equalsIgnoreCase(scheme)) throw new IOException("Unsupported media link type: " + scheme);

        String name = null;
        long size = -1;
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0);
                if (!c.isNull(1)) size = c.getLong(1);
            }
        } catch (Exception ignored) { }
        String ext = ".mp4"; // fallback for providers that omit a file extension
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String cand = name.substring(dot).toLowerCase(Locale.US);
                if (cand.matches("\\.[a-z0-9]{1,8}")) ext = cand;
            }
        }
        // Preserve the actual container when a cloud/document provider supplies MIME but no extension.
        if (".mp4".equals(ext) || ".media".equals(ext)) {
            String mime = null;
            try { mime = ctx.getContentResolver().getType(uri); } catch (Exception ignored) { }
            if ("video/quicktime".equals(mime)) ext = ".mov";
            else if ("video/webm".equals(mime)) ext = ".webm";
            else if ("video/x-matroska".equals(mime)) ext = ".mkv";
            else if ("video/3gpp".equals(mime) || "video/3gpp2".equals(mime)) ext = ".3gp";
            else if ("video/mp4".equals(mime)) ext = ".mp4";
        }
        File dir = dir(ctx);
        if (!dir.isDirectory()) throw new IOException("Cannot create the media cache folder.");
        File target = new File(dir, label + "_" + key(uri, size) + ext);
        if (target.isFile() && target.length() > 0 && (size <= 0 || target.length() == size)) return Uri.fromFile(target);
        target.delete();

        if (size > 0) {
            long free = dir.getUsableSpace();
            if (free < size + (64L << 20)) {
                throw new IOException("Not enough free storage to read this video: it needs about " + ((size >> 20) + 64)
                        + " MB free and the phone has " + (free >> 20) + " MB. Free some space and try again.");
            }
        }
        File part = new File(target.getAbsolutePath() + ".part");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(part)) {
            if (in == null) throw new IOException("The video provider returned no data.");
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled.");
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (IOException e) {
            part.delete();
            throw new IOException("Cannot read the selected video: " + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
        } catch (RuntimeException e) {
            part.delete();
            throw new IOException("Cannot read the selected video: " + Diag.describe(e), e);
        }
        if (size > 0 && part.length() != size) {
            long got = part.length();
            part.delete();
            throw new IOException("The video copy is incomplete (" + (got >> 20) + " of " + (size >> 20) + " MB). Pick the video again.");
        }
        if (!part.renameTo(target)) { part.delete(); throw new IOException("Cannot finish copying the video."); }
        return Uri.fromFile(target);
    }

    /** A ready MediaMetadataRetriever. Tries the picker link first; if the phone cannot read it, retries on a private copy. Caller releases it. */
    static MediaMetadataRetriever retriever(Context ctx, Uri uri) throws IOException {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) != null) return r;
        } catch (RuntimeException ignored) { }
        try { r.release(); } catch (Exception ignored) { }
        if ("file".equalsIgnoreCase(uri.getScheme())) throw new IOException("The phone cannot read the pictures of this video.");
        Uri local = localize(ctx, uri, "video");
        MediaMetadataRetriever r2 = new MediaMetadataRetriever();
        try {
            r2.setDataSource(ctx, local);
            return r2;
        } catch (RuntimeException e) {
            try { r2.release(); } catch (Exception ignored) { }
            throw new IOException("The phone cannot read the pictures of this video.", e);
        }
    }

    /** Deletes cached copies whose names start with prefix ("video" or "camera"), plus any unfinished .part files. */
    static void clear(Context ctx, String prefix) {
        File[] fs = dir(ctx).listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".part") || (prefix != null && n.startsWith(prefix + "_"))) f.delete();
        }
    }

    private static String key(Uri uri, long size) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((uri.toString() + "|" + size).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(uri.toString().hashCode());
        }
    }
}
