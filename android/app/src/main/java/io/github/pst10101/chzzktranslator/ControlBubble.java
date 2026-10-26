package io.github.pst10101.chzzktranslator;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small floating "CC" button over other apps. Drag it anywhere; tap it for a menu:
 * subtitles on/off, chat translation on/off, pick the chat area, open the app, stop.
 */
final class ControlBubble {
    interface Actions {
        void editAreas();
        void openApp();
        void stop();
    }

    private final Context ctx;
    private final Prefs prefs;
    private final Actions actions;
    private final WindowManager wm;
    private TextView bubble;
    private LinearLayout menu;
    private TextView subsItem, chatItem;
    private WindowManager.LayoutParams lp, mlp;
    private boolean added = false, menuShown = false;

    ControlBubble(Context c, Prefs p, Actions a) {
        ctx = c;
        prefs = p;
        actions = a;
        wm = c.getSystemService(WindowManager.class);
    }

    @SuppressLint("ClickableViewAccessibility")
    void show() {
        if (added) return;
        int size = Ui.dp(ctx, 46);
        bubble = new TextView(ctx);
        bubble.setText(R.string.cc);
        bubble.setTextColor(Color.WHITE);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setTextSize(14);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xD91E88E5);
        bg.setStroke(Ui.dp(ctx, 2), 0xCCFFFFFF);
        bubble.setBackground(bg);
        bubble.setContentDescription(ctx.getString(R.string.app_name));
        lp = Ui.overlay(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        Point s = Ui.screen(ctx);
        lp.x = Math.round(prefs.bubbleX() * (s.x - size));
        lp.y = Math.round(prefs.bubbleY() * (s.y - size));
        bubble.setOnTouchListener(new Drag());
        bubble.setOnClickListener(v -> {
            if (menuShown) hideMenu();
            else showMenu();
        });
        try {
            wm.addView(bubble, lp);
            added = true;
        } catch (Throwable t) {
            bubble = null;
        }
    }

    void hide() {
        hideMenu();
        if (added && bubble != null) {
            try { wm.removeView(bubble); } catch (Throwable ignored) { }
        }
        added = false;
        bubble = null;
    }

    /** Hidden while the area editor is open, so it can't cover the boxes. */
    void setVisible(boolean v) {
        if (bubble != null) bubble.setVisibility(v ? View.VISIBLE : View.GONE);
        if (!v) hideMenu();
    }

    /** Labels follow the settings (they can also be changed in the app). */
    void refresh() {
        if (subsItem != null) subsItem.setText(prefs.subsOn() ? R.string.menu_subs_on : R.string.menu_subs_off);
        if (chatItem != null) chatItem.setText(prefs.screenChatOn() ? R.string.menu_chat_on : R.string.menu_chat_off);
    }

    private final class Drag implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) < Ui.dp(ctx, 8)) return true;
                    if (!moved) hideMenu();
                    moved = true;
                    Point s = Ui.screen(ctx);
                    lp.x = Math.max(0, Math.min(s.x - lp.width, Math.round(startX + dx)));
                    lp.y = Math.max(0, Math.min(s.y - lp.height, Math.round(startY + dy)));
                    try { wm.updateViewLayout(bubble, lp); } catch (Throwable ignored) { }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved) {
                        Point s2 = Ui.screen(ctx);
                        prefs.setBubble(lp.x / (float) Math.max(1, s2.x - lp.width), lp.y / (float) Math.max(1, s2.y - lp.height));
                    } else {
                        v.performClick();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showMenu() {
        if (menuShown || bubble == null) return;
        menu = new LinearLayout(ctx);
        menu.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0202A30);
        bg.setCornerRadius(Ui.dp(ctx, 12));
        menu.setBackground(bg);
        int p = Ui.dp(ctx, 4);
        menu.setPadding(p, p, p, p);
        subsItem = item(0, v -> {
            prefs.edit().putBoolean("subsOn", !prefs.subsOn()).apply();
            refresh();
        });
        chatItem = item(0, v -> {
            prefs.edit().putBoolean("screenChatOn", !prefs.screenChatOn()).apply();
            refresh();
        });
        item(R.string.menu_area, v -> { hideMenu(); actions.editAreas(); });
        item(R.string.menu_app, v -> { hideMenu(); actions.openApp(); });
        TextView stop = item(R.string.menu_stop, v -> { hideMenu(); actions.stop(); });
        stop.setTextColor(0xFFFF8A80);
        refresh();
        // Tapping anywhere else closes the menu; that tap still reaches the app underneath.
        menu.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) hideMenu();
            return false;
        });

        mlp = Ui.overlay(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int mw = menu.getMeasuredWidth(), mh = menu.getMeasuredHeight();
        Point s = Ui.screen(ctx);
        boolean rightSide = lp.x + lp.width / 2 > s.x / 2;
        mlp.x = rightSide ? lp.x - mw - Ui.dp(ctx, 6) : lp.x + lp.width + Ui.dp(ctx, 6);
        mlp.x = Math.max(0, Math.min(s.x - mw, mlp.x));
        mlp.y = Math.max(0, Math.min(s.y - mh, lp.y));
        try {
            wm.addView(menu, mlp);
            menuShown = true;
        } catch (Throwable t) {
            menu = null;
        }
    }

    private TextView item(int text, View.OnClickListener l) {
        TextView t = new TextView(ctx);
        if (text != 0) t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        int h = Ui.dp(ctx, 16), v = Ui.dp(ctx, 11);
        t.setPadding(h, v, h, v);
        t.setOnClickListener(l);
        menu.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    void hideMenu() {
        if (menuShown && menu != null) {
            try { wm.removeView(menu); } catch (Throwable ignored) { }
        }
        menuShown = false;
        menu = null;
        subsItem = chatItem = null;
    }
}
