package io.github.pst10101.chzzktranslator;

import android.os.Handler;
import android.os.Looper;

/** Tiny main-thread event hub between the capture service and the screen. */
public final class Bus {
    private Bus() { }

    public interface Listener {
        /** tr == null: translation pending; tr == "": no translation available (show Korean). */
        void onSubtitle(long id, String ko, String tr);
        void onStatus(String text, int level, boolean running);
    }

    public static final int INFO = 0, WARN = 1, ERROR = 2;

    private static volatile Listener listener;
    private static final Handler main = new Handler(Looper.getMainLooper());
    public static volatile boolean running = false;

    public static void set(Listener l) { listener = l; }

    public static void subtitle(long id, String ko, String tr) {
        main.post(() -> { Listener l = listener; if (l != null) l.onSubtitle(id, ko, tr); });
    }

    public static void status(String text, int level) {
        main.post(() -> { Listener l = listener; if (l != null) l.onStatus(text, level, running); });
    }

    public static void running(boolean r, String text, int level) {
        running = r;
        status(text, level);
    }
}
