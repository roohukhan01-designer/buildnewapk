package com.roohu.clipforge;

import android.util.Log;

/** Turns any exception into one readable line that says what failed and where, so errors are never just "java.lang.IllegalStateException". */
final class Diag {
    private Diag() { }

    static String describe(Throwable t) {
        if (t == null) return "Unknown error";
        Log.e("ClipForge", "task failed", t);
        String msg = t.getMessage();
        boolean plain = t.getClass() == Exception.class || t instanceof java.io.IOException || t instanceof Net.HttpFail;
        StringBuilder sb = new StringBuilder();
        if (plain && msg != null && !msg.trim().isEmpty()) {
            sb.append(msg.trim());
        } else {
            sb.append(t.getClass().getSimpleName());
            if (msg != null && !msg.trim().isEmpty()) sb.append(": ").append(msg.trim());
            else sb.append(hint(t));
            String where = where(t);
            if (where != null) sb.append(" (").append(where).append(')');
        }
        Throwable c = t.getCause();
        int depth = 0;
        while (c != null && c != t && depth < 2) {
            String cm = c.getMessage();
            String line = c.getClass().getSimpleName() + (cm != null && !cm.trim().isEmpty() ? ": " + cm.trim() : "");
            if (sb.indexOf(line) < 0) sb.append(" | cause: ").append(line);
            c = c.getCause();
            depth++;
        }
        return sb.length() > 600 ? sb.substring(0, 600) : sb.toString();
    }

    private static String hint(Throwable t) {
        if (t instanceof IllegalStateException) return " (a media decoder or file reader was in the wrong state)";
        if (t instanceof NullPointerException) return " (something was missing)";
        if (t instanceof OutOfMemoryError) return " (the phone ran out of memory)";
        return "";
    }

    /** First stack frame that belongs to this app, as File.java:line. */
    private static String where(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) {
            for (StackTraceElement e : x.getStackTrace()) {
                if (e.getClassName().startsWith("com.roohu.clipforge")) return e.getFileName() + ":" + e.getLineNumber();
            }
        }
        StackTraceElement[] st = t.getStackTrace();
        return st.length > 0 ? st[0].getClassName().substring(st[0].getClassName().lastIndexOf('.') + 1) + "." + st[0].getMethodName() : null;
    }
}
