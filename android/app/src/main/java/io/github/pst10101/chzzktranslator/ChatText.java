package io.github.pst10101.chzzktranslator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Plain-Java helpers for reading chat off the screen (no Android classes, unit-tested on the JVM):
 * which recognised lines are worth translating, which ones we already saw while the chat scrolls,
 * and the screen areas the user picked (stored as fractions of the screen).
 */
public final class ChatText {
    private ChatText() { }

    static final Pattern HANGUL = Pattern.compile("[ㄱ-ㆎ가-힣]");

    /** Bits of the CHZZK screen itself that are not chat. Compared without spaces. */
    private static final String[] UI_TEXT = {
            "채팅을입력", "채팅방에오신것을환영", "로그인해주세요", "로그인이필요", "쾌적한시청환경",
            "클린라이브채팅", "채팅이제한", "후원하기",
    };

    /** Lowercase letters and digits only, so OCR noise in spaces and punctuation doesn't matter. */
    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        String low = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < low.length(); ) {
            int cp = low.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) b.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return b.toString();
    }

    /** 1.0 = same text, 0.0 = nothing in common (edit distance). */
    public static double similarity(String a, String b) {
        if (a.equals(b)) return 1;
        int n = a.length(), m = b.length();
        if (n == 0 || m == 0) return 0;
        int[] prev = new int[m + 1], cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return 1.0 - (double) prev[m] / Math.max(n, m);
    }

    /** Same line seen again, allowing for a few misread characters. */
    static boolean sameLine(String na, String nb) {
        if (na.equals(nb)) return true;
        int la = na.length(), lb = nb.length();
        if (Math.min(la, lb) < 4) return false;
        if (Math.abs(la - lb) > Math.max(la, lb) * 0.25) return false;
        return similarity(na, nb) >= 0.84;
    }

    /** A Korean chat line (not empty, not the app's own buttons and notices). */
    public static boolean worthTranslating(String line) {
        if (line == null) return false;
        String n = norm(line);
        if (n.length() < 2 || !HANGUL.matcher(line).find()) return false;
        for (String ui : UI_TEXT) if (n.contains(ui)) return false;
        return true;
    }

    /** Lines already handled. The chat scrolls, so the same lines are read again and again. */
    public static final class Seen {
        private final int cap;
        private final ArrayDeque<String> order = new ArrayDeque<>();
        private final HashSet<String> set = new HashSet<>();

        public Seen(int cap) { this.cap = cap; }

        /** Remembers the line; true if it was not seen before. */
        public synchronized boolean addIfNew(String line) {
            String n = norm(line);
            if (n.isEmpty() || contains(n)) return false;
            order.addLast(n);
            set.add(n);
            while (order.size() > cap) set.remove(order.pollFirst());
            return true;
        }

        public synchronized boolean seen(String line) {
            String n = norm(line);
            return !n.isEmpty() && contains(n);
        }

        private boolean contains(String n) {
            if (set.contains(n)) return true;
            int k = 0;
            for (Iterator<String> it = order.descendingIterator(); it.hasNext() && k < 150; k++) {
                if (sameLine(n, it.next())) return true;
            }
            return false;
        }

        public synchronized void clear() {
            order.clear();
            set.clear();
        }
    }

    /** Text this app put on screen itself (subtitles, translations) — never read it back as chat. */
    private static final Seen OWN = new Seen(80);

    public static void addOwn(String s) {
        if (s != null && !s.isEmpty()) OWN.addIfNew(s);
    }

    public static boolean isOwn(String s) { return OWN.seen(s); }

    /** Recognised line with its position in the picture (pixels). */
    public static final class Line {
        public final String text;
        public final int top, bottom;
        public Line(String text, int top, int bottom) { this.text = text; this.top = top; this.bottom = bottom; }
    }

    /**
     * New chat lines to translate, oldest first. Lines cut off at the top or bottom edge of the area are
     * skipped: the chat is still scrolling them in or out, and they are read whole on another pass.
     */
    public static List<String> newLines(List<Line> lines, int height, int edge, Seen seen) {
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort((a, b) -> Integer.compare(a.top, b.top));
        List<String> out = new ArrayList<>();
        for (Line l : sorted) {
            if (l.top <= edge || l.bottom >= height - edge) continue;
            String t = l.text.trim();
            if (!worthTranslating(t) || isOwn(t)) continue;
            if (seen.addIfNew(t)) out.add(t);
        }
        return out;
    }

    // ---------- screen areas ----------

    /** Where to read chat / where to show translations: {left, top, right, bottom} as 0..1 of the screen. */
    public static final float[] READ_PORTRAIT = {0.02f, 0.46f, 0.98f, 0.88f};
    public static final float[] PANEL_PORTRAIT = {0.02f, 0.30f, 0.98f, 0.45f};
    public static final float[] READ_LANDSCAPE = {0.64f, 0.12f, 0.99f, 0.86f};
    public static final float[] PANEL_LANDSCAPE = {0.01f, 0.04f, 0.42f, 0.40f};

    public static float[] parseRect(String s, float[] def) {
        if (s == null || s.isEmpty()) return def.clone();
        String[] p = s.split(",");
        if (p.length != 4) return def.clone();
        float[] r = new float[4];
        try {
            for (int i = 0; i < 4; i++) r[i] = Float.parseFloat(p[i].trim());
        } catch (NumberFormatException e) {
            return def.clone();
        }
        for (float v : r) if (!(v >= 0f && v <= 1f)) return def.clone();
        if (r[2] - r[0] < 0.05f || r[3] - r[1] < 0.03f) return def.clone();
        return r;
    }

    public static String formatRect(float[] r) {
        return String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", r[0], r[1], r[2], r[3]);
    }
}
