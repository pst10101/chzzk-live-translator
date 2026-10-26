package io.github.pst10101.chzzktranslator;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's only screen: API keys, what to translate, and the start button. Translation itself runs in
 * CaptureService and is drawn on top of other apps (the official CHZZK app, a browser, …).
 */
public class MainActivity extends Activity implements Bus.Listener {
    private static final int REQ_PERM = 1, REQ_PROJ = 2;
    private static final int BG = 0xFF101418, CARD = 0xFF1A2128, FG = 0xFFECEFF1, DIM = 0xFF9AA5AD, ACCENT = 0xFF2EE6A6;

    static final String[][] LANGS = {
            {"English", "English"}, {"Japanese", "日本語 (Japanese)"}, {"Chinese (Simplified)", "简体中文"},
            {"Chinese (Traditional)", "繁體中文"}, {"Spanish", "Español"}, {"Portuguese (Brazil)", "Português (BR)"},
            {"French", "Français"}, {"German", "Deutsch"}, {"Italian", "Italiano"}, {"Russian", "Русский"},
            {"Polish", "Polski"}, {"Turkish", "Türkçe"}, {"Arabic", "العربية"}, {"Hindi", "हिन्दी"},
            {"Indonesian", "Bahasa Indonesia"}, {"Vietnamese", "Tiếng Việt"}, {"Thai", "ไทย"}, {"Filipino", "Filipino"},
    };

    private Prefs prefs;
    private TextView statusView, startBtn;
    private EditText groq, nv, glossary;
    private boolean pendingStart = false;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(18), dp(18), dp(18), dp(28));

        TextView title = text(p, R.string.app_name, 22, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        text(p, R.string.main_sub, 13, DIM);

        // ---- start ----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(16), 0, dp(6));
        startBtn = button(R.string.btn_start, 0xFF1E88E5);
        startBtn.setOnClickListener(v -> toggle());
        row.addView(startBtn, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView open = button(R.string.btn_open_chzzk, 0xFF37474F);
        open.setOnClickListener(v -> openChzzk());
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, dp(52));
        olp.leftMargin = dp(8);
        row.addView(open, olp);
        p.addView(row);
        statusView = text(p, R.string.st_ready, 13, DIM);
        text(p, R.string.main_steps, 13, FG).setPadding(0, dp(10), 0, 0);

        // ---- what to translate ----
        LinearLayout what = card(p, R.string.sec_what);
        toggleRow(what, R.string.set_subs, prefs.subsOn(), "subsOn");
        toggleRow(what, R.string.set_screen_chat, prefs.screenChatOn(), "screenChatOn");
        note(what, R.string.set_screen_chat_note);

        // ---- keys ----
        LinearLayout keys = card(p, R.string.sec_keys);
        note(keys, R.string.set_intro);
        label(keys, R.string.set_groq);
        groq = field(keys, prefs.groqKey(), true, 1);
        groq.setHint("gsk_…");
        link(keys, R.string.set_groq_get, "https://console.groq.com/keys");
        label(keys, R.string.set_nv);
        nv = field(keys, prefs.nvKey(), true, 1);
        nv.setHint("nvapi-…");
        link(keys, R.string.set_nv_get, "https://build.nvidia.com/meta/llama-3_3-70b-instruct");

        // ---- translation ----
        LinearLayout tr = card(p, R.string.sec_translation);
        label(tr, R.string.set_lang);
        String[] labels = new String[LANGS.length];
        int sel = 0;
        for (int i = 0; i < LANGS.length; i++) {
            labels[i] = LANGS[i][1];
            if (LANGS[i][0].equals(prefs.targetLang())) sel = i;
        }
        Spinner lang = spinner(tr, labels, sel);
        lang.setOnItemSelectedListener(onPick(pos -> prefs.edit().putString("targetLang", LANGS[pos][0]).apply()));
        toggleRow(tr, R.string.set_orig, prefs.showOriginal(), "subsShowOriginal");
        toggleRow(tr, R.string.set_preset, prefs.stelPreset(), "stelPreset");
        note(tr, R.string.set_preset_note);
        label(tr, R.string.set_glossary);
        glossary = field(tr, prefs.glossary(), false, 3);

        // ---- look ----
        LinearLayout look = card(p, R.string.sec_look);
        slider(look, R.string.set_font, 12, 32, prefs.fontSize(), "subFontSize", "");
        slider(look, R.string.set_overlay_pos, 0, 70, prefs.overlayPos(), "overlayPos", "%");
        label(look, R.string.set_pace);
        Spinner pace = spinner(look, new String[]{getString(R.string.pace_allday), getString(R.string.pace_fast)},
                "fast".equals(prefs.pace()) ? 1 : 0);
        pace.setOnItemSelectedListener(onPick(pos -> prefs.edit().putString("speechPace", pos == 1 ? "fast" : "allday").apply()));

        TextView privacy = text(p, R.string.set_privacy, 12, DIM);
        privacy.setPadding(0, dp(16), 0, 0);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        sv.addView(p);
        setContentView(sv);
    }

    // =====================================================================
    // Small UI builders (explicit colours: readable on every phone's theme)
    // =====================================================================

    private TextView text(ViewGroup p, int res, float size, int color) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(size);
        t.setTextColor(color);
        p.addView(t);
        return t;
    }

    private void note(ViewGroup p, int res) {
        text(p, res, 12, DIM).setPadding(0, dp(2), 0, dp(4));
    }

    private void label(ViewGroup p, int res) {
        text(p, res, 13, DIM).setPadding(0, dp(12), 0, dp(2));
    }

    private void link(ViewGroup p, int res, String url) {
        TextView t = text(p, res, 13, 0xFF4FB3FF);
        t.setPadding(0, dp(4), 0, dp(6));
        t.setOnClickListener(v -> openUrl(url));
    }

    private TextView button(int res, int color) {
        TextView b = new TextView(this);
        b.setText(res);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        return b;
    }

    private LinearLayout card(ViewGroup p, int titleRes) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(12));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        p.addView(c, lp);
        TextView t = text(c, titleRes, 15, FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return c;
    }

    private EditText field(ViewGroup p, String value, boolean secret, int lines) {
        EditText e = new EditText(this);
        e.setTextSize(14);
        e.setTextColor(FG);
        e.setHintTextColor(0xFF5E6A72);
        if (secret) {
            e.setSingleLine(true); // before masking: setSingleLine() swaps the transformation and would show the key
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setTransformationMethod(PasswordTransformationMethod.getInstance());
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setMinLines(lines);
            e.setGravity(Gravity.TOP | Gravity.START);
        }
        e.setText(value);
        p.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private void toggleRow(ViewGroup p, int res, boolean on, String key) {
        Switch s = new Switch(this);
        s.setText(res);
        s.setTextSize(14);
        s.setTextColor(FG);
        s.setChecked(on);
        s.setPadding(0, dp(10), 0, dp(4));
        // Saved right away: the running service and the CC button's menu follow it.
        s.setOnCheckedChangeListener((v, c) -> prefs.edit().putBoolean(key, c).apply());
        p.addView(s, new LinearLayout.LayoutParams(-1, -2));
    }

    private void slider(ViewGroup p, int res, int min, int max, int value, String key, String unit) {
        TextView l = text(p, res, 13, DIM);
        l.setPadding(0, dp(12), 0, 0);
        String name = getString(res);
        l.setText(getString(R.string.value_fmt, name, value, unit));
        SeekBar s = new SeekBar(this);
        s.setMax(max - min);
        s.setProgress(Math.max(0, Math.min(max - min, value - min)));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int v, boolean u) {
                l.setText(getString(R.string.value_fmt, name, v + min, unit));
                if (u) prefs.edit().putInt(key, v + min).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        p.addView(s, new LinearLayout.LayoutParams(-1, -2));
    }

    private Spinner spinner(ViewGroup p, String[] items, int sel) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                return paint(super.getView(pos, cv, parent), false);
            }
            @Override public View getDropDownView(int pos, View cv, ViewGroup parent) {
                return paint(super.getDropDownView(pos, cv, parent), true);
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(sel);
        s.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(CARD));
        p.addView(s, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    private View paint(View v, boolean dropDown) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            t.setTextColor(FG);
            t.setTextSize(15);
            if (dropDown) t.setPadding(dp(14), dp(12), dp(14), dp(12));
        }
        return v;
    }

    private interface Pick { void on(int pos); }

    private AdapterView.OnItemSelectedListener onPick(Pick p) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { p.on(pos); }
            @Override public void onNothingSelected(AdapterView<?> a) { }
        };
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) { }
    }

    /** Opens CHZZK — in the official app when it is installed, otherwise in the browser. */
    private void openChzzk() {
        openUrl("https://chzzk.naver.com/");
    }

    private void saveTexts() {
        if (groq == null) return;
        prefs.edit()
                .putString("groqKey", groq.getText().toString().trim())
                .putString("nvidiaKey", nv.getText().toString().trim())
                .putString("glossary", glossary.getText().toString())
                .apply();
    }

    // =====================================================================
    // Start / stop
    // =====================================================================

    private void toggle() {
        if (Bus.running) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
            return;
        }
        saveTexts();
        if (prefs.groqKey().isEmpty()) {
            onStatus(getString(R.string.st_no_groq), Bus.ERROR, false);
            groq.requestFocus();
            return;
        }
        if (!prefs.subsOn() && !prefs.screenChatOn()) {
            onStatus(getString(R.string.st_pick_one), Bus.WARN, false);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true;
            onStatus(getString(R.string.st_overlay_perm), Bus.WARN, false);
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException ignored) { }
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications()
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
            prefs.setAskedNotifications(); // ask once; translation works without it
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERM);
            return;
        }
        askProjection();
    }

    private void askProjection() {
        MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
        // The whole screen (not a single app): needed to hear the other app and read its chat.
        Intent i = Build.VERSION.SDK_INT >= 34
                ? m.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : m.createScreenCaptureIntent();
        startActivityForResult(i, REQ_PROJ);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_PERM) return;
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!mic && !prefs.screenChatOn()) {
            onStatus(getString(R.string.st_need_mic), Bus.ERROR, false);
            return;
        }
        askProjection(); // without the mic permission only the chat can be translated
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PROJ) return;
        if (res != RESULT_OK || data == null) {
            onStatus(getString(R.string.st_capture_denied), Bus.WARN, false);
            return;
        }
        startForegroundService(new Intent(this, CaptureService.class).putExtra("resultCode", res).putExtra("data", data));
    }

    // =====================================================================
    // Status
    // =====================================================================

    @Override
    public void onSubtitle(long id, String ko, String tr) { }

    @Override
    public void onStatus(String text, int level, boolean running) {
        if (statusView == null) return;
        statusView.setText(text);
        statusView.setTextColor(level == Bus.ERROR ? 0xFFFF8A80 : level == Bus.WARN ? 0xFFFFC857 : ACCENT);
        refreshButton();
    }

    private void refreshButton() {
        startBtn.setText(Bus.running ? R.string.btn_stop_all : R.string.btn_start);
        GradientDrawable bg = (GradientDrawable) startBtn.getBackground();
        bg.setColor(Bus.running ? 0xFFC62828 : 0xFF1E88E5);
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Override
    protected void onResume() {
        super.onResume();
        Bus.appVisible = true;
        Bus.set(this);
        refreshButton();
        if (pendingStart && Settings.canDrawOverlays(this)) { // back from the "display over other apps" screen
            pendingStart = false;
            toggle();
        }
    }

    @Override
    protected void onPause() {
        Bus.appVisible = false;
        saveTexts();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Bus.set(null);
        super.onDestroy();
    }
}
