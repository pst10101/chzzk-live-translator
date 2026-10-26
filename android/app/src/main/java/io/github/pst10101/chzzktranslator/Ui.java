package io.github.pst10101.chzzktranslator;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;

/** Small helpers for the windows drawn on top of other apps. */
final class Ui {
    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** Full screen size in pixels for the current rotation (status and navigation bars included). */
    @SuppressWarnings("deprecation")
    static Point screen(Context c) {
        Point p = new Point();
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (d != null) d.getRealSize(p);
        if (p.x <= 0 || p.y <= 0) {
            p.x = c.getResources().getDisplayMetrics().widthPixels;
            p.y = c.getResources().getDisplayMetrics().heightPixels;
        }
        return p;
    }

    static boolean landscape(Context c) {
        Point p = screen(c);
        return p.x > p.y;
    }

    /** Window above other apps, positioned in screen pixels from the top-left corner. */
    static WindowManager.LayoutParams overlay(int w, int h, int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
        return lp;
    }

    /**
     * Android 12+ drops taps that pass through another app's overlay unless it is see-through enough.
     * Windows that let touches through use at most this opacity, so the app underneath keeps working.
     */
    static float passThroughAlpha(Context c) {
        float a = 0.8f;
        if (Build.VERSION.SDK_INT >= 31) {
            InputManager im = c.getSystemService(InputManager.class);
            if (im != null) a = im.getMaximumObscuringOpacityForTouch();
        }
        return Math.max(0.5f, Math.min(1f, a));
    }
}
