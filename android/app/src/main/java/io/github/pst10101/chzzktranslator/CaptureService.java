package io.github.pst10101.chzzktranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs while translation is on. With the screen-capture permission it
 *  - hears what other apps play (Android 10+ "audio playback capture"), cuts it into speech chunks, sends them
 *    to Groq Whisper and translates each line (same timing rules as the Chrome extension's offscreen.js);
 *  - reads the chat in the area the user picked off the screen (see ScreenChat);
 * and draws subtitles, the chat panel and the floating CC button on top of the other app.
 */
public class CaptureService extends Service {
    public static final String ACTION_STOP = "io.github.pst10101.chzzktranslator.STOP";
    private static final int RATE = 48000;
    private static final long MIN_GAP_MS = 3100; // keeps us under Groq's 20 requests / minute
    private static final int MAX_QUEUE = 3;
    private static final double QUOTA_SEC = 60; // a wait at least this long = hourly/daily free limit used up

    private MediaProjection projection;
    private AudioRecord record;
    private volatile boolean active = false;
    private volatile boolean stopped = false;
    private Thread captureThread, workThread;
    private final LinkedBlockingDeque<float[]> queue = new LinkedBlockingDeque<>();
    private ExecutorService translator;
    private final AtomicLong seq = new AtomicLong();
    private final List<String[]> context = new ArrayList<>();
    private volatile long pausedUntil = 0;
    private long lastReqAt = 0;
    private Prefs prefs;
    private AudioUtil.Chunker chunker;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;
    private SubtitleOverlay subs;
    private ChatOverlay chatPanel;
    private ControlBubble bubble;
    private RegionEditor editor;
    private ScreenChat screenChat;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            shutdown(getString(R.string.st_stopped));
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        stopped = false;
        queue.clear();
        pausedUntil = 0;
        translator = Executors.newSingleThreadExecutor();
        prefs = new Prefs(this);
        try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } catch (Exception e) {
            Bus.running(false, getString(R.string.st_capture_failed, e.getClass().getSimpleName()), Bus.ERROR);
            stopSelf();
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);
            if (projection == null) throw new IllegalStateException("no projection");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() { shutdown(getString(R.string.st_stopped)); }
            }, main);
        } catch (Throwable t) {
            shutdown(getString(R.string.st_capture_failed, t.getClass().getSimpleName()));
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            shutdown(getString(R.string.st_overlay_perm));
            return START_NOT_STICKY;
        }

        boolean audioOk = startAudio();
        if (!audioOk && prefs.subsOn() && !prefs.screenChatOn()) {
            shutdown(getString(R.string.st_need_mic));
            return START_NOT_STICKY;
        }

        // Settings changed (in the app or from the CC button's menu) while running: apply on the fly.
        prefListener = (sp, key) -> {
            if (key == null) return;
            if ("speechPace".equals(key) && chunker != null) {
                double[] np = AudioUtil.pace(prefs.pace());
                chunker.setLimits(np[0], np[1]);
            }
            if ("overlayPos".equals(key) && subs != null) subs.reposition();
            if (key.startsWith("read") || key.startsWith("panel")) {
                if (chatPanel != null) chatPanel.place();
                if (screenChat != null) screenChat.areaChanged();
            }
            if (("subsOn".equals(key) || "screenChatOn".equals(key)) && bubble != null) bubble.refresh();
            if ("subsOn".equals(key) && prefs.subsOn() && record == null) Bus.status(getString(R.string.st_need_mic), Bus.WARN);
        };
        prefs.raw().registerOnSharedPreferenceChangeListener(prefListener);

        active = true;
        subs = new SubtitleOverlay(this, prefs);
        subs.show();
        Bus.setOverlay(subs);
        chatPanel = new ChatOverlay(this, prefs);
        chatPanel.show();
        bubble = new ControlBubble(this, prefs, new ControlBubble.Actions() {
            @Override public void editAreas() { openEditor(); }
            @Override public void openApp() { openMainScreen(); }
            @Override public void stop() { shutdown(getString(R.string.st_stopped)); }
        });
        bubble.show();
        screenChat = new ScreenChat(this, prefs, projection, chatPanel);
        screenChat.start();

        Bus.running(true, getString(R.string.st_running), Bus.INFO);
        if (!audioOk && prefs.subsOn()) Bus.status(getString(R.string.st_need_mic), Bus.WARN);
        if (record != null) {
            double[] p = AudioUtil.pace(prefs.pace());
            chunker = new AudioUtil.Chunker(this::onChunk, p[0], p[1]);
            captureThread = new Thread(this::captureLoop, "ctr-capture");
            workThread = new Thread(this::workLoop, "ctr-whisper");
            captureThread.start();
            workThread.start();
        }
        return START_NOT_STICKY;
    }

    /** Starts hearing what other apps play. False when that isn't possible (no permission, refused). */
    private boolean startAudio() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(minBuf * 2, RATE)) // >= 0.5 s
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord");
            record.startRecording();
            return true;
        } catch (Throwable t) {
            try { if (record != null) record.release(); } catch (Throwable ignored) { }
            record = null;
            Bus.status(getString(R.string.st_capture_failed, t.getClass().getSimpleName()), Bus.WARN);
            return false;
        }
    }

    /** Shows the box editor for the chat areas over whatever app is open. */
    private void openEditor() {
        if (editor != null && editor.isShowing()) return;
        if (bubble != null) bubble.setVisible(false);
        editor = new RegionEditor(this, prefs, saved -> {
            if (bubble != null) bubble.setVisible(true);
            if (saved && chatPanel != null) chatPanel.clear();
        });
        editor.show();
    }

    private void openMainScreen() {
        try {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Throwable ignored) { }
    }

    private void captureLoop() {
        AudioRecord r = record;
        AudioUtil.Resampler rs = new AudioUtil.Resampler(RATE);
        short[] buf = new short[RATE / 10]; // 100 ms
        while (active) {
            int n;
            try {
                n = r.read(buf, 0, buf.length);
            } catch (Throwable t) {
                break;
            }
            if (n < 0) break;
            if (n == 0) continue;
            chunker.push(rs.push(buf, n, 1));
        }
    }

    private void onChunk(float[] samples) {
        if (!prefs.subsOn() || System.currentTimeMillis() < pausedUntil) return;
        queue.addLast(samples);
        while (queue.size() > MAX_QUEUE) queue.pollFirst(); // falling behind: drop the oldest, stay live
    }

    private void workLoop() {
        while (active) {
            float[] chunk;
            try {
                chunk = queue.pollFirst(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (chunk == null || !active) continue;
            long wait = lastReqAt + MIN_GAP_MS - System.currentTimeMillis();
            if (wait > 0) sleep(wait);
            if (!active || System.currentTimeMillis() < pausedUntil) continue;
            transcribe(chunk);
        }
    }

    private void transcribe(float[] samples) {
        String key = prefs.groqKey();
        if (key.isEmpty()) {
            Bus.status(getString(R.string.st_no_groq), Bus.ERROR);
            return;
        }
        lastReqAt = System.currentTimeMillis();
        String whisperPrompt = TranslateUtil.whisperPrompt(Glossary.effective(this, prefs));
        String ko;
        try {
            ko = Net.whisper(AudioUtil.encodeWav(samples), key, whisperPrompt);
        } catch (Net.HttpError e) {
            if (e.code == 401) {
                shutdown(getString(R.string.st_bad_groq));
            } else if (e.code == 429) {
                double ra = e.retrySec > 0 ? e.retrySec : 30;
                pausedUntil = System.currentTimeMillis() + (long) (ra * 1000);
                queue.clear();
                if (ra >= QUOTA_SEC) {
                    String at = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(pausedUntil));
                    Bus.status(getString(R.string.st_groq_quota, at), Bus.WARN);
                } else {
                    Bus.status(getString(R.string.st_groq_wait, (int) Math.ceil(ra)), Bus.WARN);
                }
            } else {
                Bus.status(getString(R.string.st_speech_error, e.code), Bus.WARN);
            }
            return;
        } catch (Exception e) {
            Bus.status(getString(R.string.st_network), Bus.WARN);
            return;
        }
        if (ko.isEmpty() || AudioUtil.isPromptEcho(ko, whisperPrompt)) return;
        long id = seq.incrementAndGet();
        Bus.subtitle(id, ko, Llm.hasKey(prefs) ? null : "");
        ExecutorService tx = translator;
        if (Llm.hasKey(prefs) && tx != null && !tx.isShutdown()) {
            try { tx.execute(() -> translateLine(id, ko)); } catch (java.util.concurrent.RejectedExecutionException ignored) { }
        }
    }

    private void translateLine(long id, String ko) {
        if (id < seq.get() - 2) { // newer lines are waiting; keep subtitles live
            Bus.subtitle(id, ko, "");
            return;
        }
        List<String[]> ctx = new ArrayList<>(context);
        StringBuilder seenText = new StringBuilder(ko);
        for (String[] c : ctx) seenText.append(' ').append(c[0]);
        String glossary = TranslateUtil.relevantGlossary(Glossary.effective(this, prefs), seenText.toString());
        String[] pr = TranslateUtil.subtitlePrompt(ko, ctx, prefs.targetLang(), glossary);
        String tr = "";
        try {
            tr = TranslateUtil.cleanSubtitle(Llm.complete(prefs, false, pr[0], pr[1], 300));
        } catch (Llm.Busy e) {
            Bus.status(getString(R.string.st_tr_busy), Bus.WARN);
        } catch (Net.HttpError e) {
            Bus.status(e.code == 401 ? getString(R.string.st_bad_groq) : getString(R.string.st_tr_error, e.code), Bus.WARN);
        } catch (Exception e) {
            Bus.status(getString(R.string.st_network), Bus.WARN);
        }
        context.add(new String[]{ko, tr});
        while (context.size() > 3) context.remove(0);
        Bus.subtitle(id, ko, tr);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("ctr", getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "ctr")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.btn_stop), stop).build())
                .setOngoing(true)
                .build();
    }

    /** Stops everything once (safe to call from any thread, more than once). */
    private synchronized void shutdown(String status) {
        if (stopped) return;
        stopped = true;
        active = false;
        queue.clear();
        if (prefListener != null) {
            prefs.raw().unregisterOnSharedPreferenceChangeListener(prefListener);
            prefListener = null;
        }
        ScreenChat sc = screenChat;
        screenChat = null;
        if (sc != null) sc.stop();
        Thread ct = captureThread;
        if (ct != null && ct != Thread.currentThread()) {
            try { ct.join(400); } catch (InterruptedException ignored) { }
        }
        try {
            if (record != null) { record.stop(); record.release(); }
        } catch (Throwable ignored) { }
        record = null;
        MediaProjection mp = projection;
        projection = null;
        try { if (mp != null) mp.stop(); } catch (Throwable ignored) { }
        if (translator != null) translator.shutdownNow();
        main.post(() -> {
            Bus.setOverlay(null);
            if (editor != null && editor.isShowing()) editor.close(false);
            editor = null;
            if (subs != null) subs.hide();
            subs = null;
            if (chatPanel != null) chatPanel.hide();
            chatPanel = null;
            if (bubble != null) bubble.hide();
            bubble = null;
        });
        Bus.running(false, status, Bus.INFO);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        shutdown(getString(R.string.st_stopped));
        super.onDestroy();
    }
}
