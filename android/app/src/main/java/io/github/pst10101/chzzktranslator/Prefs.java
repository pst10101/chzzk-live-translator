package io.github.pst10101.chzzktranslator;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings, stored only on this device (same names/defaults as the Chrome extension where they overlap). */
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

    /** Subtitles from the stream's sound. */
    public boolean subsOn() { return sp.getBoolean("subsOn", true); }
    /** Chat read off the screen (Korean → targetLang). */
    public boolean screenChatOn() { return sp.getBoolean("screenChatOn", true); }

    public boolean showOriginal() { return sp.getBoolean("subsShowOriginal", true); }
    public int fontSize() { return sp.getInt("subFontSize", 18); }
    /** Subtitle height above the bottom edge, percent of the screen. */
    public int overlayPos() { return sp.getInt("overlayPos", 14); }
    public String glossary() { return sp.getString("glossary", ""); }
    /** Built-in StelLive names & memes (assets/glossary/stellive-*.txt). */
    public boolean stelPreset() { return sp.getBoolean("stelPreset", true); }
    public boolean askedNotifications() { return sp.getBoolean("askedNotif", false); }

    /** Where chat is read from, for this rotation (fractions of the screen). */
    public float[] readRect(boolean landscape) {
        return ChatText.parseRect(sp.getString(landscape ? "readL" : "readP", ""),
                landscape ? ChatText.READ_LANDSCAPE : ChatText.READ_PORTRAIT);
    }

    /** Where chat translations are shown, for this rotation. */
    public float[] panelRect(boolean landscape) {
        return ChatText.parseRect(sp.getString(landscape ? "panelL" : "panelP", ""),
                landscape ? ChatText.PANEL_LANDSCAPE : ChatText.PANEL_PORTRAIT);
    }

    public void setAreas(boolean landscape, float[] read, float[] panel) {
        sp.edit()
                .putString(landscape ? "readL" : "readP", ChatText.formatRect(read))
                .putString(landscape ? "panelL" : "panelP", ChatText.formatRect(panel))
                .apply();
    }

    public float bubbleX() { return sp.getFloat("bubbleX", 1f); }
    public float bubbleY() { return sp.getFloat("bubbleY", 0.22f); }
    public void setBubble(float x, float y) { sp.edit().putFloat("bubbleX", x).putFloat("bubbleY", y).apply(); }

    public SharedPreferences.Editor edit() { return sp.edit(); }
    public SharedPreferences raw() { return sp; }
    public void setAskedNotifications() { sp.edit().putBoolean("askedNotif", true).apply(); }
}
