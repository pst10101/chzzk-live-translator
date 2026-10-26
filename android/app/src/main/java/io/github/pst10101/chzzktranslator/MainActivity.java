package io.github.pst10101.chzzktranslator;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CHZZK's mobile site in a WebView, with live subtitles drawn on top of the video and chat translation
 * inside the page. The page script only gets an origin-restricted message channel (no Java objects).
 */
public class MainActivity extends Activity implements Bus.Listener {
    private static final int REQ_PERM = 1, REQ_PROJ = 2;
    private static final String HOME = "https://chzzk.naver.com/";
    private static final Set<String> ORIGINS = new HashSet<>(Arrays.asList("https://chzzk.naver.com", "https://m.chzzk.naver.com"));
    /** [name used in prompts, label] — same list as the Chrome extension. */
    static final String[][] LANGS = {
            {"English", "English"}, {"Japanese", "日本語 (Japanese)"}, {"Chinese (Simplified)", "简体中文"},
            {"Chinese (Traditional)", "繁體中文"}, {"Spanish", "Español"}, {"Portuguese (Brazil)", "Português (BR)"},
            {"French", "Français"}, {"German", "Deutsch"}, {"Italian", "Italiano"}, {"Russian", "Русский"},
            {"Polish", "Polski"}, {"Turkish", "Türkçe"}, {"Arabic", "العربية"}, {"Hindi", "हिन्दी"},
            {"Indonesian", "Bahasa Indonesia"}, {"Vietnamese", "Tiếng Việt"}, {"Thai", "ไทย"}, {"Filipino", "Filipino"},
    };
    private static final long LINE_TTL = 9000;

    private Prefs prefs;
    private FrameLayout root;
    private WebView web;
    private TextView subView, statusView, ccBtn;
    private LinearLayout controls;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private JavaScriptReplyProxy page;
    private boolean bridgeOk = false;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService chatPool = Executors.newSingleThreadExecutor();

    // video position reported by the page, in CSS px
    private boolean videoKnown = false;
    private float vTop, vBottom, vLeft, vRight, vw = 1;

    private static final class Line {
        final long id;
        String ko, tr;
        long at;
        Line(long id, String ko, String tr) { this.id = id; this.ko = ko; this.tr = tr; this.at = System.currentTimeMillis(); }
    }
    private final List<Line> lines = new ArrayList<>();
    private final Runnable expireLines = this::expireLines;
    private final Runnable hideStatus = () -> statusView.setVisibility(View.GONE);

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // =====================================================================
    // Setup
    // =====================================================================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        subView = new TextView(this);
        subView.setTextColor(Color.WHITE);
        subView.setGravity(Gravity.CENTER);
        subView.setShadowLayer(dp(3), 0, 0, Color.BLACK);
        GradientDrawable subBg = new GradientDrawable();
        subBg.setColor(0xA6000000);
        subBg.setCornerRadius(dp(6));
        subView.setBackground(subBg);
        subView.setPadding(dp(10), dp(4), dp(10), dp(5));
        subView.setVisibility(View.GONE);
        root.addView(subView, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        subView.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> placeSubs());

        buildControls();
        setContentView(root);
        setupWeb();

        Uri data = getIntent().getData();
        web.loadUrl(isChzzk(data) ? data.toString() : HOME);
        if (prefs.groqKey().isEmpty()) main.postDelayed(this::showSettings, 600);
    }

    /** Small draggable pill on the right edge: [⠿ grip] [▶ CC] [⚙], with a status line under it. */
    @SuppressLint("ClickableViewAccessibility")
    private void buildControls() {
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.END);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC15191C);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), 0x552EE6A6);
        pill.setBackground(bg);
        pill.setPadding(dp(4), dp(2), dp(4), dp(2));

        TextView grip = pillText("⠿", 0xFF8A949C);
        ccBtn = pillText(getString(R.string.btn_cc_start), 0xFF2EE6A6);
        ccBtn.setTypeface(Typeface.DEFAULT_BOLD);
        TextView gear = pillText("⚙", 0xFFE6E6E6);
        pill.addView(grip);
        pill.addView(ccBtn);
        pill.addView(gear);
        ccBtn.setOnClickListener(v -> toggle());
        gear.setOnClickListener(v -> showSettings());

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(12);
        statusView.setMaxWidth(dp(260));
        statusView.setPadding(dp(8), dp(4), dp(8), dp(4));
        statusView.setVisibility(View.GONE);
        statusView.setOnClickListener(v -> v.setVisibility(View.GONE));

        controls.addView(pill, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = dp(4);
        controls.addView(statusView, sp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.rightMargin = dp(6);
        root.addView(controls, lp);
        root.post(() -> controls.setY(clampY(prefs.pillY() * root.getHeight())));

        // Drag the pill up/down by its grip (so it never has to cover CHZZK's own buttons).
        final float[] grab = new float[1];
        grip.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    grab[0] = e.getRawY() - controls.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    controls.setY(clampY(e.getRawY() - grab[0]));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (root.getHeight() > 0) prefs.setPillY(controls.getY() / root.getHeight());
                    return true;
            }
            return false;
        });
    }

    private float clampY(float y) {
        int h = root.getHeight();
        if (h == 0) return y;
        return Math.max(dp(56), Math.min(y, h - dp(48)));
    }

    private TextView pillText(String s, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(40));
        t.setMinHeight(dp(40));
        t.setPadding(dp(8), 0, dp(8), 0);
        return t;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        // Look like regular mobile Chrome so CHZZK serves its normal mobile site.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        String script = asset("chat-utils.js") + "\n;\n" + asset("chat.js");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            bridgeOk = true;
            WebViewCompat.addWebMessageListener(web, "CTR", ORIGINS, (view, message, sourceOrigin, isMainFrame, reply) -> {
                if (!isMainFrame || message.getData() == null) return;
                page = reply;
                onPageMessage(message.getData());
            });
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, script, ORIGINS);
                script = null;
            }
        }
        final String lateScript = script; // older WebView: inject after load (CHZZK pages only)

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (!scheme.equals("http") && !scheme.equals("https")) return true; // "open in app" intents etc.
                String host = u.getHost() == null ? "" : u.getHost();
                if (host.endsWith("naver.com") || host.endsWith("naver.net") || host.endsWith("pstatic.net")) return false;
                if (r.isForMainFrame()) openExternal(u.toString()); // other sites open in the browser
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (bridgeOk && lateScript != null && isChzzk(Uri.parse(url))) v.evaluateJavascript(lateScript, null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View v, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = v;
                customCb = cb;
                root.addView(v, 1, new FrameLayout.LayoutParams(-1, -1));
                web.setVisibility(View.INVISIBLE);
                setFullscreenUi(true);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                placeSubs();
                renderSubs();
            }

            @Override
            public void onHideCustomView() { exitFullscreen(); }
        });
    }

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        WebChromeClient.CustomViewCallback cb = customCb;
        customCb = null;
        if (cb != null) cb.onCustomViewHidden();
        setFullscreenUi(false);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        placeSubs();
        renderSubs();
    }

    private void setFullscreenUi(boolean on) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (on) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(on
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
        updateKeepScreenOn();
    }

    private void updateKeepScreenOn() {
        if (Bus.running || customView != null) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private static boolean isChzzk(Uri u) {
        return u != null && "https".equals(u.getScheme())
                && ("chzzk.naver.com".equals(u.getHost()) || "m.chzzk.naver.com".equals(u.getHost()));
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) { }
    }

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri data = intent.getData();
        if (isChzzk(data)) web.loadUrl(data.toString());
    }

    // =====================================================================
    // Page <-> app messages
    // =====================================================================

    private void postToPage(JSONObject o) {
        JavaScriptReplyProxy p = page;
        if (p == null) return;
        try {
            p.postMessage(o.toString());
        } catch (Exception ignored) { }
    }

    private void sendCfg() {
        try {
            postToPage(new JSONObject()
                    .put("type", "cfg")
                    .put("out", prefs.chatEnabled())
                    .put("ko", prefs.chatToKorean())
                    .put("english", "English".equals(prefs.targetLang()))
                    .put("ready", Llm.hasKey(prefs))
                    .put("lang", prefs.targetLang()));
        } catch (Exception ignored) { }
    }

    private void onPageMessage(String data) {
        JSONObject m;
        try {
            m = new JSONObject(data);
        } catch (Exception e) {
            return;
        }
        switch (m.optString("type")) {
            case "hello":
                sendCfg();
                break;
            case "tr":
                translateChat(m);
                break;
            case "video":
                videoKnown = !m.optBoolean("none", false);
                vTop = (float) m.optDouble("top", 0);
                vBottom = (float) m.optDouble("bottom", 0);
                vLeft = (float) m.optDouble("left", 0);
                vRight = (float) m.optDouble("right", 0);
                vw = (float) Math.max(1, m.optDouble("vw", 1));
                placeSubs();
                break;
        }
    }

    private void translateChat(JSONObject m) {
        final int id = m.optInt("id");
        final String dir = m.optString("dir", "out");
        JSONArray arr = m.optJSONArray("texts");
        if (arr == null || arr.length() == 0) return;
        final List<String> texts = new ArrayList<>();
        for (int i = 0; i < arr.length() && i < 40; i++) texts.add(arr.optString(i, ""));
        final JavaScriptReplyProxy target = page;
        chatPool.execute(() -> {
            JSONObject reply = new JSONObject();
            try {
                reply.put("type", "trDone").put("id", id);
                String system = "ko".equals(dir)
                        ? TranslateUtil.chatToKoreanSystem(prefs.glossary())
                        : TranslateUtil.chatSystem(prefs.targetLang(), prefs.glossary());
                String content = Llm.complete(prefs, true, system, TranslateUtil.jsonArray(texts),
                        Math.min(4000, 100 + texts.size() * 80));
                List<String> out = TranslateUtil.parseChatResponse(content, texts.size());
                JSONArray ja = new JSONArray();
                for (String s : out) ja.put(s == null ? JSONObject.NULL : s);
                reply.put("out", ja);
            } catch (Llm.Busy e) {
                try { reply.put("retry", true); } catch (Exception ignored) { }
            } catch (Net.HttpError e) {
                if (e.code == 401) Bus.status(getString(R.string.st_bad_groq), Bus.ERROR);
            } catch (Exception ignored) {
                // network hiccup: these lines just stay untranslated
            }
            main.post(() -> {
                if (target == page) postToPage(reply);
            });
        });
    }

    // =====================================================================
    // Subtitles
    // =====================================================================

    @Override
    public void onSubtitle(long id, String ko, String tr) {
        Line line = null;
        for (Line l : lines) if (l.id == id) line = l;
        if (line == null) {
            line = new Line(id, ko, tr);
            lines.add(line);
            while (lines.size() > 2) lines.remove(0);
        } else {
            line.ko = ko;
            line.tr = tr;
            line.at = System.currentTimeMillis();
        }
        renderSubs();
    }

    private void expireLines() {
        long now = System.currentTimeMillis();
        lines.removeIf(l -> now - l.at > LINE_TTL);
        renderSubs();
    }

    private void renderSubs() {
        main.removeCallbacks(expireLines);
        // Small portrait player: only the newest line, so the video stays visible.
        int max = customView != null || isLandscape() ? 2 : 1;
        List<Line> show = lines.subList(Math.max(0, lines.size() - max), lines.size());
        if (show.isEmpty()) {
            subView.setVisibility(View.GONE);
            return;
        }
        float size = prefs.fontSize() * (customView != null || isLandscape() ? 1.15f : 1f);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (Line l : show) {
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
        subView.setText(sb);
        subView.setVisibility(View.VISIBLE);
        placeSubs();
        long oldest = Long.MAX_VALUE;
        for (Line l : lines) oldest = Math.min(oldest, l.at);
        main.postDelayed(expireLines, Math.max(500, oldest + LINE_TTL - System.currentTimeMillis() + 50));
    }

    private boolean isLandscape() {
        return root.getWidth() > root.getHeight();
    }

    /** Puts the subtitles on the bottom of the video (or the bottom of the screen if the video is scrolled away). */
    private void placeSubs() {
        int H = root.getHeight(), W = root.getWidth();
        if (H == 0 || subView.getVisibility() != View.VISIBLE) return;
        float bottom, left = 0, right = W;
        if (customView != null) {
            bottom = H - dp(24);
        } else if (videoKnown) {
            float scale = web.getWidth() / vw;
            float top = web.getY() + vTop * scale;
            float vb = web.getY() + vBottom * scale;
            if (vb > dp(60) && top < H - dp(60)) {
                bottom = Math.min(vb, H) - dp(8);
                left = Math.max(0, vLeft * scale);
                right = Math.min(W, vRight * scale);
            } else {
                bottom = H - dp(16);
            }
        } else {
            bottom = H - dp(16);
        }
        int maxW = (int) Math.max(dp(160), right - left - dp(16));
        if (subView.getMaxWidth() != maxW) subView.setMaxWidth(maxW);
        subView.setX(left + (right - left - subView.getWidth()) / 2f);
        subView.setY(Math.max(0, bottom - subView.getHeight()));
    }

    @Override
    public void onStatus(String text, int level, boolean running) {
        ccBtn.setText(running ? R.string.btn_cc_stop : R.string.btn_cc_start);
        ccBtn.setTextColor(running ? 0xFFFF7A7A : 0xFF2EE6A6);
        updateKeepScreenOn();
        if (!running) {
            lines.clear();
            renderSubs();
        }
        if (text == null || text.isEmpty()) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(level == Bus.ERROR ? 0xE6B3261E : level == Bus.WARN ? 0xE68A5A00 : 0xE6202A30);
        statusView.setBackground(bg);
        statusView.setText(text);
        statusView.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideStatus);
        main.postDelayed(hideStatus, level == Bus.INFO ? 4000 : 9000);
    }

    // =====================================================================
    // Start / stop subtitles
    // =====================================================================

    private void toggle() {
        if (Bus.running) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
            return;
        }
        if (prefs.groqKey().isEmpty()) {
            Toast.makeText(this, R.string.st_no_groq, Toast.LENGTH_LONG).show();
            showSettings();
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications()
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
            prefs.setAskedNotifications(); // ask once; subtitles work without it
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERM);
            return;
        }
        askProjection();
    }

    private void askProjection() {
        MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
        Intent i = Build.VERSION.SDK_INT >= 34
                ? m.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : m.createScreenCaptureIntent();
        startActivityForResult(i, REQ_PROJ);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_PERM) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askProjection();
        else onStatus(getString(R.string.st_need_mic), Bus.ERROR, false);
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
    // Lifecycle
    // =====================================================================

    @Override
    protected void onResume() {
        super.onResume();
        Bus.set(this);
        ccBtn.setText(Bus.running ? R.string.btn_cc_stop : R.string.btn_cc_start);
        ccBtn.setTextColor(Bus.running ? 0xFFFF7A7A : 0xFF2EE6A6);
        updateKeepScreenOn();
    }

    @Override
    protected void onDestroy() {
        Bus.set(null);
        if (isFinishing() && Bus.running) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
        }
        chatPool.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) exitFullscreen();
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // =====================================================================
    // Settings
    // =====================================================================

    private TextView label(LinearLayout p, int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(13);
        t.setPadding(0, dp(14), 0, dp(2));
        p.addView(t);
        return t;
    }

    private TextView link(LinearLayout p, int res, String url) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(13);
        t.setTextColor(0xFF2E9BE6);
        t.setPadding(0, dp(2), 0, dp(4));
        t.setOnClickListener(v -> openExternal(url));
        p.addView(t);
        return t;
    }

    private EditText field(LinearLayout p, String value, boolean secret, int lines) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextSize(14);
        if (secret) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setSingleLine(true);
        } else if (lines > 1) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setMinLines(lines);
            e.setGravity(Gravity.TOP | Gravity.START);
        }
        p.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private Switch toggleRow(LinearLayout p, int res, boolean on) {
        Switch s = new Switch(this);
        s.setText(res);
        s.setTextSize(14);
        s.setChecked(on);
        s.setPadding(0, dp(10), 0, dp(4));
        p.addView(s, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    private void showSettings() {
        if (isFinishing()) return;
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(22), dp(8), dp(22), dp(8));

        TextView intro = new TextView(this);
        intro.setText(R.string.set_intro);
        intro.setTextSize(13);
        p.addView(intro);

        label(p, R.string.set_groq);
        EditText groq = field(p, prefs.groqKey(), true, 1);
        groq.setHint("gsk_…");
        link(p, R.string.set_groq_get, "https://console.groq.com/keys");

        label(p, R.string.set_nv);
        EditText nv = field(p, prefs.nvKey(), true, 1);
        nv.setHint("nvapi-…");
        link(p, R.string.set_nv_get, "https://build.nvidia.com/meta/llama-3_3-70b-instruct");

        label(p, R.string.set_lang);
        String[] labels = new String[LANGS.length];
        int sel = 0;
        for (int i = 0; i < LANGS.length; i++) {
            labels[i] = LANGS[i][1];
            if (LANGS[i][0].equals(prefs.targetLang())) sel = i;
        }
        Spinner lang = new Spinner(this);
        lang.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        lang.setSelection(sel);
        p.addView(lang);

        label(p, R.string.set_pace);
        Spinner pace = new Spinner(this);
        pace.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.pace_allday), getString(R.string.pace_fast)}));
        pace.setSelection("fast".equals(prefs.pace()) ? 1 : 0);
        p.addView(pace);

        Switch chat = toggleRow(p, R.string.set_chat, prefs.chatEnabled());
        Switch chatKo = toggleRow(p, R.string.set_chat_ko, prefs.chatToKorean());
        Switch orig = toggleRow(p, R.string.set_orig, prefs.showOriginal());

        TextView fontLabel = label(p, R.string.set_font);
        SeekBar font = new SeekBar(this);
        font.setMax(20); // 12..32 sp
        font.setProgress(Math.max(0, Math.min(20, prefs.fontSize() - 12)));
        fontLabel.setText(getString(R.string.set_font) + " — " + prefs.fontSize());
        font.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) { fontLabel.setText(getString(R.string.set_font) + " — " + (v + 12)); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        p.addView(font, new LinearLayout.LayoutParams(-1, -2));

        label(p, R.string.set_glossary);
        EditText gl = field(p, prefs.glossary(), false, 3);

        TextView note = new TextView(this);
        note.setText(R.string.set_privacy);
        note.setTextSize(12);
        note.setPadding(0, dp(14), 0, 0);
        p.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.addView(p);
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_title)
                .setView(sv)
                .setPositiveButton(R.string.btn_save, (d, w) -> {
                    prefs.edit()
                            .putString("groqKey", groq.getText().toString().trim())
                            .putString("nvidiaKey", nv.getText().toString().trim())
                            .putString("targetLang", LANGS[lang.getSelectedItemPosition()][0])
                            .putString("speechPace", pace.getSelectedItemPosition() == 1 ? "fast" : "allday")
                            .putBoolean("chatEnabled", chat.isChecked())
                            .putBoolean("chatToKorean", chatKo.isChecked())
                            .putBoolean("subsShowOriginal", orig.isChecked())
                            .putInt("subFontSize", font.getProgress() + 12)
                            .putString("glossary", gl.getText().toString())
                            .apply();
                    sendCfg();
                    renderSubs();
                    if (!bridgeOk && (chat.isChecked() || chatKo.isChecked())) {
                        onStatus(getString(R.string.st_old_webview), Bus.WARN, Bus.running);
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }
}
