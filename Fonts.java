package com.roohu.clipforge;

import android.content.Context;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/** Loads the real display fonts bundled in assets/fonts (downloaded by the GitHub build). Falls back to system fonts. */
final class Fonts {
    private static Context app;
    private static final Map<String, Typeface> cache = new HashMap<>();

    static void init(Context c) { if (app == null) app = c.getApplicationContext(); }

    private static String file(String name) {
        switch (name) {
            case "Anton": case "Oswald": return "Anton-Regular.ttf";
            case "Bebas Neue": return "BebasNeue-Regular.ttf";
            case "Bangers": return "Bangers-Regular.ttf";
            case "Archivo Black": return "ArchivoBlack-Regular.ttf";
            case "Poppins": case "Montserrat": return "Poppins-ExtraBold.ttf";
            case "Poppins Black": return "Poppins-Black.ttf";
            case "Michroma": return "Michroma-Regular.ttf";
            case "Bungee": return "Bungee-Regular.ttf";
            case "Luckiest Guy": return "LuckiestGuy-Regular.ttf";
            case "Russo One": return "RussoOne-Regular.ttf";
            case "Black Ops One": return "BlackOpsOne-Regular.ttf";
            case "Tajawal": return "Tajawal-Black.ttf";
            default: return null;
        }
    }

    /** True when the real font file made it into the APK. */
    static boolean has(String name) {
        String f = file(name);
        if (f == null || app == null) return false;
        try { app.getAssets().open("fonts/" + f).close(); return true; } catch (Exception e) { return false; }
    }

    static synchronized Typeface get(String name) {
        if (name == null) name = "";
        Typeface t = cache.get(name);
        if (t != null) return t;
        String f = file(name);
        if (f != null && app != null) {
            try { t = Typeface.createFromAsset(app.getAssets(), "fonts/" + f); } catch (Exception ignored) { }
        }
        if (t == null) {
            switch (name) {
                case "Playfair Display": t = Typeface.create(Typeface.SERIF, Typeface.BOLD); break;
                case "Anton": case "Bebas Neue": case "Oswald": t = Typeface.create("sans-serif-condensed", Typeface.BOLD); break;
                case "Bangers": case "Luckiest Guy": t = Typeface.create("casual", Typeface.BOLD); break;
                default: t = Typeface.create("sans-serif-black", Typeface.NORMAL);
            }
        }
        cache.put(name, t);
        return t;
    }
}
