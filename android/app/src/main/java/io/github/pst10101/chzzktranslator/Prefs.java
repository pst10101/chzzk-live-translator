package io.github.pst10101.chzzktranslator;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings, stored only on this device (same names/defaults as the Chrome extension). */
public final class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("ctr", Context.MODE_PRIVATE);
    }

    public String groqKey() { return sp.getString("groqKey", "").trim(); }
    public String nvKey() { return sp.getString("nvidiaKey", "").trim(); }
    /** Language name used in prompts, e.g. "English", "Japanese". */
    public String targetLang() { return sp.getString("targetLang", "English"); }
    public String pace() { return sp.getString("speechPace", "allday"); }
    /** Korean chat -> targetLang. */
    public boolean chatEnabled() { return sp.getBoolean("chatEnabled", true); }
    /** Chat in other languages -> Korean. */
    public boolean chatToKorean() { return sp.getBoolean("chatToKorean", false); }
    public boolean showOriginal() { return sp.getBoolean("subsShowOriginal", true); }
    public int fontSize() { return sp.getInt("subFontSize", 18); }
    public String glossary() { return sp.getString("glossary", ""); }
    /** Built-in StelLive names & memes (assets/glossary/stellive-*.txt). */
    public boolean stelPreset() { return sp.getBoolean("stelPreset", true); }
    public boolean askedNotifications() { return sp.getBoolean("askedNotif", false); }
    public float pillY() { return sp.getFloat("pillY", 0.62f); }

    public SharedPreferences.Editor edit() { return sp.edit(); }
    public void setAskedNotifications() { sp.edit().putBoolean("askedNotif", true).apply(); }
    public void setPillY(float y) { sp.edit().putFloat("pillY", y).apply(); }
}
