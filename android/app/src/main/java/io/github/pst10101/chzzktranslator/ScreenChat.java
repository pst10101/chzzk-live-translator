package io.github.pst10101.chzzktranslator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Reads the chat off the screen: mirrors the screen into a small off-screen image, crops the area the user
 * picked, recognises the text on the phone (ML Kit, nothing is uploaded), keeps only new Korean lines and
 * sends those as text to the translation model. Results go to the chat panel.
 */
final class ScreenChat {
    private static final long TICK_MS = 1500;
    private static final int MAX_BATCH = 15;
    /** At most one chat request every few seconds: the free models allow few tokens a minute. */
    private static final long MIN_GAP_MS = 6000;
    private static final int MAX_PENDING = 36;

    private final Context ctx;
    private final Prefs prefs;
    private final MediaProjection projection;
    private final ChatOverlay panel;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread, frameThread;
    private Handler h, hf;
    private volatile boolean running = false;

    private VirtualDisplay vd;
    private volatile ImageReader reader;
    private int vw, vh;
    private boolean displayFailed = false;
    private final Object frameLock = new Object();
    private Bitmap frame;
    private int frameW, frameH;
    private long lastCopy = 0;
    private long lastHash = 0;
    private TextRecognizer recognizer;
    private final ChatText.Seen seen = new ChatText.Seen(300);
    private long lastOcrError = 0;

    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final ExecutorService tx = Executors.newSingleThreadExecutor();
    private volatile boolean txBusy = false;
    private long lastTxAt = 0;
    private long busyNoticeAt = 0;

    ScreenChat(Context c, Prefs p, MediaProjection mp, ChatOverlay panel) {
        ctx = c;
        prefs = p;
        projection = mp;
        this.panel = panel;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("ctr-screen-chat");
        thread.start();
        h = new Handler(thread.getLooper());
        // Frames are taken on their own thread, so text recognition never holds up the screen mirror.
        frameThread = new HandlerThread("ctr-screen-frames");
        frameThread.start();
        hf = new Handler(frameThread.getLooper());
        h.post(tick);
    }

    /** The user moved the read area: read it again from scratch. */
    void areaChanged() {
        Handler hh = h;
        if (hh != null) hh.post(() -> lastHash = 0);
    }

    void stop() {
        running = false;
        tx.shutdownNow();
        Handler hh = h;
        if (hh == null) return;
        hh.removeCallbacksAndMessages(null);
        hh.post(() -> {
            try { if (vd != null) vd.release(); } catch (Throwable ignored) { }
            vd = null;
            try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
            reader = null;
            try { if (recognizer != null) recognizer.close(); } catch (Throwable ignored) { }
            recognizer = null;
            synchronized (frameLock) { frame = null; }
            thread.quitSafely();
            frameThread.quitSafely();
        });
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                step();
            } catch (Throwable t) {
                if (SystemClock.elapsedRealtime() - lastOcrError > 30000) {
                    lastOcrError = SystemClock.elapsedRealtime();
                    Bus.status(ctx.getString(R.string.st_ocr_failed, t.getClass().getSimpleName()), Bus.WARN);
                }
            }
            if (running) h.postDelayed(this, TICK_MS);
        }
    };

    private void step() throws Exception {
        if (!prefs.screenChatOn()) return;
        if (vd == null) {
            if (displayFailed) return;
            createDisplay();
            return; // first frame arrives shortly
        }
        followRotation();

        boolean land = vw > vh;
        float[] r = prefs.readRect(land);
        Rect px = new Rect(Math.round(r[0] * vw), Math.round(r[1] * vh), Math.round(r[2] * vw), Math.round(r[3] * vh));
        Bitmap crop;
        synchronized (frameLock) {
            if (frame == null) return;
            if (!px.intersect(0, 0, Math.min(vw, frameW), Math.min(vh, frameH)) || px.width() < 24 || px.height() < 24) return;
            crop = Bitmap.createBitmap(frame, px.left, px.top, px.width(), px.height());
        }
        long hash = hash(crop);
        if (hash == lastHash) { // chat didn't move
            crop.recycle();
            return;
        }
        lastHash = hash;
        if (recognizer == null) recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
        Text result;
        try {
            result = Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0)), 8, TimeUnit.SECONDS);
        } finally {
            crop.recycle();
        }
        List<ChatText.Line> lines = new ArrayList<>();
        for (Text.TextBlock b : result.getTextBlocks()) {
            for (Text.Line l : b.getLines()) {
                Rect box = l.getBoundingBox();
                if (box == null) continue;
                lines.add(new ChatText.Line(l.getText(), box.top, box.bottom));
            }
        }
        List<String> fresh = ChatText.newLines(lines, px.height(), Math.max(2, px.height() / 80), seen);
        if (!fresh.isEmpty()) {
            synchronized (pending) {
                pending.addAll(fresh);
                while (pending.size() > MAX_PENDING) pending.pollFirst(); // far behind: keep the newest
            }
        }
        translateSoon();
    }

    // ---------- screen mirror ----------

    private void createDisplay() {
        Point s = Ui.screen(ctx);
        // Small enough to be cheap, big enough for chat text: shorter side at most 1080 px.
        float scale = Math.min(1f, 1080f / Math.min(s.x, s.y));
        vw = Math.max(2, Math.round(s.x * scale));
        vh = Math.max(2, Math.round(s.y * scale));
        int dpi = Math.max(120, Math.round(ctx.getResources().getDisplayMetrics().densityDpi * scale));
        try {
            reader = newReader(vw, vh);
            vd = projection.createVirtualDisplay("ctr-chat", vw, vh, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h);
            if (vd == null) throw new IllegalStateException("no display");
        } catch (Throwable t) {
            displayFailed = true;
            try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
            reader = null;
            vd = null;
            Bus.status(ctx.getString(R.string.st_screen_failed), Bus.WARN);
        }
    }

    private ImageReader newReader(int w, int hgt) {
        ImageReader r = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2);
        r.setOnImageAvailableListener(this::onImage, hf);
        return r;
    }

    /** Takes every frame off the queue (so the screen mirror never stalls) but copies at most ~1 per tick. */
    private void onImage(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (Throwable t) {
            return;
        }
        if (img == null) return;
        try {
            long now = SystemClock.elapsedRealtime();
            if (r != reader || !prefs.screenChatOn() || now - lastCopy < TICK_MS - 300) return;
            Image.Plane p = img.getPlanes()[0];
            int ps = p.getPixelStride(), rs = p.getRowStride();
            int w = rs / ps;
            ByteBuffer buf = p.getBuffer();
            buf.rewind();
            int rows = Math.min(img.getHeight(), buf.remaining() / rs); // some phones leave the last row short
            if (rows <= 0) return;
            synchronized (frameLock) {
                if (frame == null || frame.getWidth() != w || frame.getHeight() != rows) {
                    frame = Bitmap.createBitmap(w, rows, Bitmap.Config.ARGB_8888);
                }
                frame.copyPixelsFromBuffer(buf);
                frameW = img.getWidth();
                frameH = rows;
            }
            lastCopy = now;
        } catch (Throwable ignored) {
            // a bad frame: skip it
        } finally {
            img.close();
        }
    }

    /** Rotated: give the mirror the new shape so the picked areas line up again. */
    private void followRotation() {
        Point s = Ui.screen(ctx);
        boolean land = s.x > s.y;
        if (land == (vw > vh)) return;
        int nw = vh, nh = vw;
        ImageReader old = reader;
        try {
            reader = newReader(nw, nh);
            vd.setSurface(reader.getSurface());
            vd.resize(nw, nh, Math.max(120, Math.round(ctx.getResources().getDisplayMetrics().densityDpi
                    * Math.min(1f, 1080f / Math.min(s.x, s.y)))));
            vw = nw;
            vh = nh;
        } catch (Throwable t) {
            displayFailed = true;
        }
        try { if (old != null) old.close(); } catch (Throwable ignored) { }
        synchronized (frameLock) { frame = null; }
        lastHash = 0;
    }

    /** Cheap fingerprint of the chat area, to skip text recognition while nothing changes. */
    private static long hash(Bitmap b) {
        int w = b.getWidth(), hgt = b.getHeight();
        long x = 1469598103934665603L;
        for (int gy = 0; gy < 40; gy++) {
            int y = gy * (hgt - 1) / 39;
            for (int gx = 0; gx < 16; gx++) {
                int px = b.getPixel(gx * (w - 1) / 15, y) & 0xF0F0F0; // ignore tiny colour noise
                x = (x ^ px) * 1099511628211L;
            }
        }
        return x;
    }

    // ---------- translation ----------

    private void translateSoon() {
        if (txBusy || !running || SystemClock.elapsedRealtime() - lastTxAt < MIN_GAP_MS) return;
        final List<String> batch = new ArrayList<>();
        synchronized (pending) {
            while (!pending.isEmpty() && batch.size() < MAX_BATCH) batch.add(pending.pollFirst());
        }
        if (batch.isEmpty()) return;
        txBusy = true;
        lastTxAt = SystemClock.elapsedRealtime();
        try {
            tx.execute(() -> {
                try {
                    translate(batch);
                } finally {
                    txBusy = false;
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            txBusy = false;
        }
    }

    private void translate(List<String> batch) {
        if (!Llm.hasKey(prefs)) return;
        try {
            String glossary = TranslateUtil.relevantGlossary(Glossary.effective(ctx, prefs), String.join("\n", batch));
            String content = Llm.complete(prefs, true, TranslateUtil.chatSystem(prefs.targetLang(), glossary),
                    TranslateUtil.jsonArray(batch), Math.min(4000, 100 + batch.size() * 80));
            List<String> out = TranslateUtil.parseChatResponse(content, batch.size());
            main.post(() -> {
                for (int i = 0; i < batch.size(); i++) {
                    String tr = i < out.size() ? out.get(i) : null;
                    if (tr != null && !tr.trim().isEmpty()) panel.add(batch.get(i), tr.trim());
                }
            });
        } catch (Llm.Busy e) {
            synchronized (pending) { // try the newest again in a moment
                for (int i = batch.size() - 1; i >= Math.max(0, batch.size() - 6); i--) pending.addFirst(batch.get(i));
            }
            long now = SystemClock.elapsedRealtime();
            if (now - busyNoticeAt > 60000) {
                busyNoticeAt = now;
                Bus.status(ctx.getString(R.string.st_chat_busy), Bus.WARN);
            }
        } catch (Net.HttpError e) {
            Bus.status(e.code == 401 ? ctx.getString(R.string.st_bad_groq) : ctx.getString(R.string.st_tr_error, e.code),
                    e.code == 401 ? Bus.ERROR : Bus.WARN);
        } catch (Exception e) {
            Bus.status(ctx.getString(R.string.st_network), Bus.WARN);
        }
    }
}
