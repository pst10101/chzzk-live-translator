package io.github.pst10101.chzzktranslator;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Your own terms + the built-in StelLive terms for the current translation language. */
public final class Glossary {
    private Glossary() { }

    private static volatile String en, ja;

    private static String asset(Context c, String name) {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static String effective(Context c, Prefs p) {
        if (!p.stelPreset()) return p.glossary();
        if (en == null) en = asset(c, "glossary/stellive-en.txt");
        if (ja == null) ja = asset(c, "glossary/stellive-ja.txt");
        return TranslateUtil.mergeGlossaries(p.glossary(), TranslateUtil.presetGlossary(en, ja, p.targetLang()));
    }
}
