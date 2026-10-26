package io.github.pst10101.chzzktranslator;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Speech subtitles drawn on top of other apps (for example the official CHZZK app). Not touchable, so taps
 * go to the app underneath. Also shows limit / error notices, since the user is looking at another app.
 */
final class SubtitleOverlay implements Bus.Listener {
    private static final long TTL = 7000;
    private static final long STATUS_TTL = 6000;

    private static final class Line {
        final long id;
        String ko, tr;
        long at = System.currentTimeMillis();
        Line(long id, String ko, String tr) { this.id = id; this.ko = ko; this.tr = tr; }
    }

    private final Context ctx;
    private final Prefs prefs;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Line> lines = new ArrayList<>();
    private TextView view;
    private WindowManager.LayoutParams lp;
    private boolean added = false;
    private String status = "";
    private int statusLevel = Bus.INFO;
    private long statusAt = 0;
    private int lastW = 0, lastH = 0;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            if (added) main.postDelayed(this, 500);
        }
    };

    SubtitleOverlay(Context c, Prefs p) {
        ctx = c;
        prefs = p;
        wm = c.getSystemService(WindowManager.class);
    }

    void show() {
        if (added) return;
        view = new TextView(ctx);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setShadowLayer(Ui.dp(ctx, 3), 0, 0, Color.BLACK);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3000000);
        bg.setCornerRadius(Ui.dp(ctx, 8));
        view.setBackground(bg);
        view.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 10), Ui.dp(ctx, 5));
        view.setVisibility(View.GONE);
        lp = Ui.overlay(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.alpha = Ui.passThroughAlpha(ctx);
        reposition();
        try {
            wm.addView(view, lp);
            added = true;
        } catch (Throwable t) {
            view = null;
            return;
        }
        main.post(tick);
    }

    void hide() {
        main.removeCallbacks(tick);
        if (added && view != null) {
            try { wm.removeView(view); } catch (Throwable ignored) { }
        }
        added = false;
        view = null;
        lines.clear();
    }

    /** Height above the bottom edge, from Settings (percent of the screen). */
    void reposition() {
        if (lp == null) return;
        Point s = Ui.screen(ctx);
        lastW = s.x;
        lastH = s.y;
        lp.y = Math.round(s.y * prefs.overlayPos() / 100f);
        if (added && view != null) {
            try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) { }
        }
    }

    @Override
    public void onSubtitle(long id, String ko, String tr) {
        ChatText.addOwn(ko); // never read our own subtitles back as chat
        if (tr != null) ChatText.addOwn(tr);
        Line line = null;
        for (Line l : lines) if (l.id == id) line = l;
        if (line == null) {
            lines.add(new Line(id, ko, tr));
            while (lines.size() > 2) lines.remove(0);
        } else {
            line.ko = ko;
            line.tr = tr;
            line.at = System.currentTimeMillis();
        }
        render();
    }

    @Override
    public void onStatus(String text, int level, boolean running) {
        if (level == Bus.INFO) return; // only problems (limits, errors) are worth covering another app
        status = text;
        statusLevel = level;
        statusAt = System.currentTimeMillis();
        render();
    }

    private void render() {
        if (!added || view == null) return;
        long now = System.currentTimeMillis();
        lines.removeIf(l -> now - l.at > TTL);
        boolean showStatus = !status.isEmpty() && now - statusAt < STATUS_TTL;
        boolean showLines = prefs.subsOn() && !lines.isEmpty();
        if (Bus.appVisible || (!showLines && !showStatus)) {
            view.setVisibility(View.GONE);
            return;
        }
        Point s = Ui.screen(ctx);
        if (s.x != lastW || s.y != lastH) reposition(); // rotated
        boolean land = s.x > s.y;
        view.setMaxWidth((int) (s.x * (land ? 0.7f : 0.92f)));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.fontSize() * (land ? 1.15f : 1f));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (showLines) {
            for (Line l : lines) {
                if (sb.length() > 0) sb.append('\n');
                int s0 = sb.length();
                if (l.tr == null) { // translating…
                    sb.append(l.ko);
                    sb.setSpan(new ForegroundColorSpan(0xFFB8C0C6), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (l.tr.isEmpty()) { // no translation: Korean is the main line
                    sb.append(l.ko);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    sb.append(l.tr);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (prefs.showOriginal()) {
                        sb.append('\n');
                        int k0 = sb.length();
                        sb.append(l.ko);
                        sb.setSpan(new RelativeSizeSpan(0.72f), k0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sb.setSpan(new ForegroundColorSpan(0xFFC9D1D6), k0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            }
        }
        if (showStatus) {
            if (sb.length() > 0) sb.append('\n');
            int s0 = sb.length();
            sb.append(status);
            sb.setSpan(new RelativeSizeSpan(0.72f), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(statusLevel == Bus.ERROR ? 0xFFFF9A94 : 0xFFFFC857), s0, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(sb);
        view.setVisibility(View.VISIBLE);
    }
}
