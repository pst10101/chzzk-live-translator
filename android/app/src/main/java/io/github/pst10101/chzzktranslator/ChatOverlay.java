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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Panel with the translated chat, in the screen area the user picked. Newest line at the bottom; older lines
 * scroll out of the top. Not touchable, so the app underneath keeps working.
 */
final class ChatOverlay {
    private static final int MAX_LINES = 14;

    private final Context ctx;
    private final Prefs prefs;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout list;
    private WindowManager.LayoutParams lp;
    private boolean added = false;
    private boolean placedLandscape;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            if (added) main.postDelayed(this, 700);
        }
    };

    ChatOverlay(Context c, Prefs p) {
        ctx = c;
        prefs = p;
        wm = c.getSystemService(WindowManager.class);
    }

    void show() {
        if (added) return;
        root = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(Ui.dp(ctx, 8));
        root.setBackground(bg);
        int pad = Ui.dp(ctx, 6);
        root.setPadding(pad, pad / 2, pad, pad / 2);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setGravity(Gravity.BOTTOM); // overflowing old lines are pushed out of the top
        root.addView(list, new FrameLayout.LayoutParams(-1, -1));
        root.setVisibility(View.GONE);
        lp = Ui.overlay(100, 100, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        lp.alpha = Ui.passThroughAlpha(ctx);
        place();
        try {
            wm.addView(root, lp);
            added = true;
        } catch (Throwable t) {
            root = null;
            return;
        }
        main.post(tick);
    }

    void hide() {
        main.removeCallbacks(tick);
        if (added && root != null) {
            try { wm.removeView(root); } catch (Throwable ignored) { }
        }
        added = false;
        root = null;
        list = null;
    }

    /** Moves the panel to the area chosen for this rotation. */
    void place() {
        if (lp == null) return;
        Point s = Ui.screen(ctx);
        placedLandscape = s.x > s.y;
        float[] r = prefs.panelRect(placedLandscape);
        lp.x = Math.round(r[0] * s.x);
        lp.y = Math.round(r[1] * s.y);
        lp.width = Math.max(Ui.dp(ctx, 80), Math.round((r[2] - r[0]) * s.x));
        lp.height = Math.max(Ui.dp(ctx, 40), Math.round((r[3] - r[1]) * s.y));
        if (added && root != null) {
            try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) { }
        }
    }

    /** Adds one translated chat line (main thread). */
    void add(String ko, String tr) {
        if (list == null || tr == null || tr.isEmpty()) return;
        ChatText.addOwn(tr);
        TextView t = new TextView(ctx);
        t.setTextColor(Color.WHITE);
        t.setShadowLayer(Ui.dp(ctx, 2), 0, 0, Color.BLACK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(11, prefs.fontSize() * 0.78f));
        t.setPadding(0, Ui.dp(ctx, 1), 0, Ui.dp(ctx, 1));
        SpannableStringBuilder sb = new SpannableStringBuilder(tr);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (prefs.showOriginal()) {
            sb.append("  ");
            int k0 = sb.length();
            sb.append(ko);
            sb.setSpan(new RelativeSizeSpan(0.8f), k0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(0xFFB8C0C6), k0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        t.setText(sb);
        list.addView(t, new LinearLayout.LayoutParams(-1, -2));
        while (list.getChildCount() > MAX_LINES) list.removeViewAt(0);
        refresh();
    }

    void clear() {
        if (list != null) list.removeAllViews();
        refresh();
    }

    private void refresh() {
        if (!added || root == null) return;
        if (Ui.landscape(ctx) != placedLandscape) place(); // rotated
        boolean show = prefs.screenChatOn() && !Bus.appVisible && list.getChildCount() > 0;
        root.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
