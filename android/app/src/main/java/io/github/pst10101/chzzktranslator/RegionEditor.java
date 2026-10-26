package io.github.pst10101.chzzktranslator;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen editor over the CHZZK app: a blue box for where the chat is read and a green box for where
 * its translation is shown. Drag a box to move it, drag a corner to resize. Saved per rotation.
 */
final class RegionEditor {
    interface Done { void onDone(boolean saved); }

    private final Context ctx;
    private final Prefs prefs;
    private final WindowManager wm;
    private final Done done;
    private FrameLayout root;
    private Boxes boxes;
    private boolean added = false;

    RegionEditor(Context c, Prefs p, Done d) {
        ctx = c;
        prefs = p;
        done = d;
        wm = c.getSystemService(WindowManager.class);
    }

    boolean isShowing() { return added; }

    void show() {
        if (added) return;
        root = new FrameLayout(ctx);
        boxes = new Boxes(ctx);
        root.addView(boxes, new FrameLayout.LayoutParams(-1, -1));

        TextView hint = new TextView(ctx);
        hint.setText(R.string.ed_hint);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0xCC000000);
        hb.setCornerRadius(Ui.dp(ctx, 8));
        hint.setBackground(hb);
        int p = Ui.dp(ctx, 8);
        hint.setPadding(p, p / 2, p, p / 2);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hlp.topMargin = Ui.dp(ctx, 36);
        hlp.leftMargin = hlp.rightMargin = Ui.dp(ctx, 12);
        root.addView(hint, hlp);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(button(R.string.ed_reset, 0xFF455A64, v -> boxes.reset()));
        bar.addView(button(R.string.ed_cancel, 0xFF455A64, v -> close(false)));
        bar.addView(button(R.string.ed_save, 0xFF1E88E5, v -> {
            boxes.save();
            close(true);
        }));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = Ui.dp(ctx, 56);
        root.addView(bar, blp);

        WindowManager.LayoutParams lp = Ui.overlay(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        try {
            wm.addView(root, lp);
            added = true;
        } catch (Throwable t) {
            root = null;
        }
    }

    void close(boolean saved) {
        if (added && root != null) {
            try { wm.removeView(root); } catch (Throwable ignored) { }
        }
        added = false;
        root = null;
        boxes = null;
        if (done != null) done.onDone(saved);
    }

    private TextView button(int text, int color, View.OnClickListener l) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(Ui.dp(ctx, 20));
        b.setBackground(bg);
        int h = Ui.dp(ctx, 18), v = Ui.dp(ctx, 10);
        b.setPadding(h, v, h, v);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = lp.rightMargin = Ui.dp(ctx, 6);
        b.setLayoutParams(lp);
        return b;
    }

    /** The two boxes. Coordinates are view pixels; saved as fractions of the whole screen. */
    private final class Boxes extends View {
        private final RectF read = new RectF(), panel = new RectF();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] loc = new int[2];
        private boolean landscape, loaded = false;
        private RectF target;
        private int mode; // 0 move, 1..4 corner (TL, TR, BL, BR)
        private float lastX, lastY;
        private final float handle, minW, minH;

        Boxes(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Ui.dp(c, 3));
            text.setColor(Color.WHITE);
            text.setTextSize(Ui.dp(c, 14));
            text.setFakeBoldText(true);
            text.setShadowLayer(Ui.dp(c, 2), 0, 0, Color.BLACK);
            handle = Ui.dp(c, 30);
            minW = Ui.dp(c, 70);
            minH = Ui.dp(c, 40);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            load(); // first layout, or the phone was rotated: show the boxes saved for this rotation
        }

        private void load() {
            Point s = Ui.screen(getContext());
            landscape = s.x > s.y;
            set(read, prefs.readRect(landscape), s);
            set(panel, prefs.panelRect(landscape), s);
            loaded = true;
            invalidate();
        }

        void reset() {
            Point s = Ui.screen(getContext());
            set(read, landscape ? ChatText.READ_LANDSCAPE : ChatText.READ_PORTRAIT, s);
            set(panel, landscape ? ChatText.PANEL_LANDSCAPE : ChatText.PANEL_PORTRAIT, s);
            invalidate();
        }

        private void set(RectF r, float[] f, Point s) {
            getLocationOnScreen(loc);
            r.set(f[0] * s.x - loc[0], f[1] * s.y - loc[1], f[2] * s.x - loc[0], f[3] * s.y - loc[1]);
            clamp(r);
        }

        private float[] fractions(RectF r, Point s) {
            getLocationOnScreen(loc);
            return new float[]{
                    clamp01((r.left + loc[0]) / s.x), clamp01((r.top + loc[1]) / s.y),
                    clamp01((r.right + loc[0]) / s.x), clamp01((r.bottom + loc[1]) / s.y)};
        }

        private float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

        void save() {
            if (!loaded) return;
            Point s = Ui.screen(getContext());
            prefs.setAreas(s.x > s.y, fractions(read, s), fractions(panel, s));
        }

        private void clamp(RectF r) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (r.width() > w) r.right = r.left + w;
            if (r.height() > h) r.bottom = r.top + h;
            if (r.left < 0) r.offset(-r.left, 0);
            if (r.top < 0) r.offset(0, -r.top);
            if (r.right > w) r.offset(w - r.right, 0);
            if (r.bottom > h) r.offset(0, h - r.bottom);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(0x33000000);
            draw(c, read, 0xFF2196F3, getContext().getString(R.string.ed_read));
            draw(c, panel, 0xFF43A047, getContext().getString(R.string.ed_panel));
        }

        private void draw(Canvas c, RectF r, int color, String label) {
            fill.setColor((color & 0x00FFFFFF) | 0x33000000);
            c.drawRect(r, fill);
            stroke.setColor(color);
            c.drawRect(r, stroke);
            fill.setColor(color);
            float rad = Ui.dp(getContext(), 7);
            c.drawCircle(r.left, r.top, rad, fill);
            c.drawCircle(r.right, r.top, rad, fill);
            c.drawCircle(r.left, r.bottom, rad, fill);
            c.drawCircle(r.right, r.bottom, rad, fill);
            c.drawText(label, r.left + rad * 2, r.top + text.getTextSize() + rad, text);
        }

        private int corner(RectF r, float x, float y) {
            if (Math.hypot(x - r.left, y - r.top) < handle) return 1;
            if (Math.hypot(x - r.right, y - r.top) < handle) return 2;
            if (Math.hypot(x - r.left, y - r.bottom) < handle) return 3;
            if (Math.hypot(x - r.right, y - r.bottom) < handle) return 4;
            return 0;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    target = null;
                    // The green box is drawn on top, so it wins when the boxes overlap.
                    for (RectF r : new RectF[]{panel, read}) {
                        int k = corner(r, x, y);
                        if (k != 0 || r.contains(x, y)) {
                            target = r;
                            mode = k;
                            break;
                        }
                    }
                    lastX = x;
                    lastY = y;
                    return target != null;
                case MotionEvent.ACTION_MOVE:
                    if (target == null) return false;
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x;
                    lastY = y;
                    RectF r = target;
                    switch (mode) {
                        case 0: r.offset(dx, dy); break;
                        case 1: r.left = Math.min(r.left + dx, r.right - minW); r.top = Math.min(r.top + dy, r.bottom - minH); break;
                        case 2: r.right = Math.max(r.right + dx, r.left + minW); r.top = Math.min(r.top + dy, r.bottom - minH); break;
                        case 3: r.left = Math.min(r.left + dx, r.right - minW); r.bottom = Math.max(r.bottom + dy, r.top + minH); break;
                        default: r.right = Math.max(r.right + dx, r.left + minW); r.bottom = Math.max(r.bottom + dy, r.top + minH); break;
                    }
                    if (mode != 0) {
                        r.left = Math.max(0, r.left);
                        r.top = Math.max(0, r.top);
                        r.right = Math.min(getWidth(), r.right);
                        r.bottom = Math.min(getHeight(), r.bottom);
                    }
                    clamp(r);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    target = null;
                    return true;
                default:
                    return true;
            }
        }
    }
}
