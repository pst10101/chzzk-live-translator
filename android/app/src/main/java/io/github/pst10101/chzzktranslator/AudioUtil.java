package io.github.pst10101.chzzktranslator;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java audio helpers, ported 1:1 from the Chrome extension's audio-utils.js
 * (no Android dependencies, unit-tested on a PC).
 */
public final class AudioUtil {
    private AudioUtil() { }

    public static final int RATE = 16000;
    static final int FRAME = 1600; // 100 ms at 16 kHz

    /** Windowed-sinc (Blackman) low-pass FIR taps. */
    static float[] lowpassTaps(double cutoffHz, double rate, int taps) {
        double fc = cutoffHz / rate;
        double mid = (taps - 1) / 2.0;
        float[] h = new float[taps];
        double sum = 0;
        for (int i = 0; i < taps; i++) {
            double x = i - mid;
            double sinc = x == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
            double w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (taps - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (taps - 1));
            h[i] = (float) (sinc * w);
            sum += h[i];
        }
        for (int i = 0; i < taps; i++) h[i] /= (float) sum;
        return h;
    }

    /**
     * Streaming resampler: any rate -> 16 kHz mono. Low-passes at ~7 kHz first, then averages
     * the input samples each output sample covers.
     */
    public static final class Resampler {
        private final double ratio;
        private double pos = 0;
        private float[] left = new float[0];
        private final float[] h;
        private float[] hist;

        public Resampler(int srcRate) {
            ratio = srcRate / (double) RATE;
            h = srcRate > RATE ? lowpassTaps(RATE * 0.44, srcRate, 41) : null;
            hist = h != null ? new float[h.length - 1] : null;
        }

        /** Interleaved PCM16 (len shorts, any channel count) -> 16 kHz mono floats. */
        public float[] push(short[] pcm, int len, int channels) {
            int frames = len / channels;
            float[] mono = new float[frames];
            for (int f = 0; f < frames; f++) {
                int acc = 0;
                for (int c = 0; c < channels; c++) acc += pcm[f * channels + c];
                mono[f] = acc / (float) channels / 32768f;
            }
            return push(mono);
        }

        public float[] push(float[] raw) {
            float[] input = h != null ? filter(raw) : raw;
            float[] data = new float[left.length + input.length];
            System.arraycopy(left, 0, data, 0, left.length);
            System.arraycopy(input, 0, data, left.length, input.length);
            float[] out = new float[(int) (data.length / ratio) + 2];
            int n = 0;
            while (pos + ratio <= data.length) {
                int start = (int) Math.floor(pos);
                int end = Math.max(start + 1, (int) Math.floor(pos + ratio));
                float s = 0;
                for (int i = start; i < end; i++) s += data[i];
                out[n++] = s / (end - start);
                pos += ratio;
            }
            int keep = (int) Math.floor(pos);
            left = Arrays.copyOfRange(data, keep, data.length);
            pos -= keep;
            return Arrays.copyOf(out, n);
        }

        private float[] filter(float[] input) {
            int taps = h.length;
            float[] data = new float[hist.length + input.length];
            System.arraycopy(hist, 0, data, 0, hist.length);
            System.arraycopy(input, 0, data, hist.length, input.length);
            float[] out = new float[input.length];
            for (int i = 0; i < input.length; i++) {
                float s = 0;
                for (int k = 0; k < taps; k++) s += h[k] * data[i + k];
                out[i] = s;
            }
            hist = Arrays.copyOfRange(data, data.length - (taps - 1), data.length);
            return out;
        }
    }

    /** Chunk lengths per "Whisper timing" (Groq free tier: 2,000 requests and 28,800 audio seconds a day). */
    public static double[] pace(String name) {
        return "fast".equals(name) ? new double[]{4, 12} : new double[]{13, 20};
    }

    /**
     * Cuts a continuous 16 kHz stream into speech chunks: at a short pause once the chunk is at least
     * minSec long, never past maxSec (cut at the quietest moment of the last 3 s), and drops chunks with
     * almost no voice. "Silence" is judged relative to recent loudness, so it works with background music.
     */
    public static final class Chunker {
        public interface Sink { void onChunk(float[] samples); }

        private static final class Frame {
            final float[] data; final float rms; final boolean silent, voiced;
            Frame(float[] d, float r, boolean s, boolean v) { data = d; rms = r; silent = s; voiced = v; }
        }

        private final Sink sink;
        private volatile int minFrames, maxFrames;
        private static final int SILENCE_FRAMES = 3; // 300 ms pause
        private static final float ABS_SILENCE = 0.004f, ABS_VOICE = 0.012f, REL_SILENCE = 0.3f;
        private static final int MIN_VOICED_FRAMES = 5; // 0.5 s of voice

        private float[] pending = new float[0];
        private final List<Frame> frames = new ArrayList<>();
        private int voicedInFrames = 0;
        private float peak = 0;
        private int silentRun = 0;

        public Chunker(Sink sink, double minSec, double maxSec) {
            this.sink = sink;
            setLimits(minSec, maxSec);
        }

        public void setLimits(double minSec, double maxSec) {
            minFrames = (int) Math.round(minSec * 10);
            maxFrames = (int) Math.round(maxSec * 10);
        }

        public void push(float[] samples) {
            float[] data = new float[pending.length + samples.length];
            System.arraycopy(pending, 0, data, 0, pending.length);
            System.arraycopy(samples, 0, data, pending.length, samples.length);
            int off = 0;
            while (off + FRAME <= data.length) {
                frame(Arrays.copyOfRange(data, off, off + FRAME));
                off += FRAME;
            }
            pending = Arrays.copyOfRange(data, off, data.length);
        }

        private void frame(float[] f) {
            double e = 0;
            for (float v : f) e += v * v;
            float rms = (float) Math.sqrt(e / f.length);
            peak = Math.max(rms, peak * 0.98f);
            boolean silent = rms < Math.max(ABS_SILENCE, REL_SILENCE * peak);
            boolean voiced = !silent && rms > ABS_VOICE;
            frames.add(new Frame(f, rms, silent, voiced));
            if (voiced) voicedInFrames++;
            silentRun = silent ? silentRun + 1 : 0;

            // Nothing said yet: keep only a short pre-roll so chunks start near speech.
            if (voicedInFrames == 0 && frames.size() > 20) {
                frames.subList(0, frames.size() - 3).clear();
                return;
            }
            int n = frames.size();
            if (n >= minFrames && silentRun >= SILENCE_FRAMES) {
                emit(n);
            } else if (n >= maxFrames) {
                int best = n - 1;
                for (int i = Math.max(minFrames, n - 30); i < n; i++) {
                    if (frames.get(i).rms < frames.get(best).rms) best = i;
                }
                emit(best + 1);
            }
        }

        private void emit(int count) {
            List<Frame> take = new ArrayList<>(frames.subList(0, count));
            frames.subList(0, count).clear();
            silentRun = 0;
            int voiced = 0;
            for (Frame f : take) if (f.voiced) voiced++;
            voicedInFrames = 0;
            for (Frame f : frames) if (f.voiced) voicedInFrames++;
            if (voiced < MIN_VOICED_FRAMES) return;
            float[] out = new float[take.size() * FRAME];
            for (int i = 0; i < take.size(); i++) System.arraycopy(take.get(i).data, 0, out, i * FRAME, FRAME);
            sink.onChunk(out);
        }
    }

    private static final Pattern TRY_AGAIN = Pattern.compile(
            "try again in\\s+((?:[\\d.]+h)?\\s*(?:[\\d.]+m(?!s))?\\s*(?:[\\d.]+s)?\\s*(?:[\\d.]+ms)?)", Pattern.CASE_INSENSITIVE);

    private static double part(String s, String re) {
        Matcher m = Pattern.compile(re).matcher(s);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    /**
     * Seconds to wait after a 429, from the Retry-After header and/or Groq's message
     * ("... Please try again in 1h2m3.5s ..."). Returns the larger of the two, or fallback.
     */
    public static double parseRetryAfter(String header, String body, double fallback) {
        double sec = 0;
        try { if (header != null) sec = Double.parseDouble(header.trim()); } catch (NumberFormatException ignored) { }
        if (Double.isNaN(sec) || sec < 0) sec = 0;
        Matcher m = TRY_AGAIN.matcher(body == null ? "" : body);
        if (m.find()) {
            String g = m.group(1);
            double parsed = part(g, "([\\d.]+)h") * 3600 + part(g, "([\\d.]+)m(?!s)") * 60
                    + part(g, "([\\d.]+)s") + part(g, "([\\d.]+)ms") / 1000;
            sec = Math.max(sec, parsed);
        }
        return sec > 0 ? sec : fallback;
    }

    /** 16-bit PCM mono WAV at 16 kHz. */
    public static byte[] encodeWav(float[] s) {
        int dataLen = s.length * 2;
        ByteArrayOutputStream o = new ByteArrayOutputStream(44 + dataLen);
        str(o, "RIFF"); i32(o, 36 + dataLen); str(o, "WAVE");
        str(o, "fmt "); i32(o, 16); i16(o, 1); i16(o, 1);
        i32(o, RATE); i32(o, RATE * 2); i16(o, 2); i16(o, 16);
        str(o, "data"); i32(o, dataLen);
        for (float v : s) {
            float c = Math.max(-1f, Math.min(1f, v));
            i16(o, (int) (c < 0 ? c * 0x8000 : c * 0x7fff));
        }
        return o.toByteArray();
    }

    private static void str(ByteArrayOutputStream o, String s) { for (char c : s.toCharArray()) o.write(c); }
    private static void i32(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24); }
    private static void i16(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); }

    // Phrases Whisper tends to invent on silence / music in Korean.
    private static final Pattern[] HALLUCINATIONS = {
            Pattern.compile("시청\\s*해\\s*주셔서\\s*감사합니다"),
            Pattern.compile("구독\\s*(과|와)?\\s*좋아요"),
            Pattern.compile("좋아요\\s*(와|과)?\\s*구독"),
            Pattern.compile("(MBC|KBS|SBS|YTN|JTBC)\\s*뉴스"),
            Pattern.compile("자막\\s*(제공|by|BY|협찬)"),
            Pattern.compile("한글\\s*자막"),
            Pattern.compile("다음\\s*(영상|시간)에서\\s*(만나요|뵙겠습니다)"),
    };

    /** Whisper sometimes just repeats its prompt (our list of names) over music: a comma list of prompt terms. */
    public static boolean isPromptEcho(String text, String prompt) {
        if (text == null || prompt == null || text.isEmpty() || prompt.isEmpty()) return false;
        java.util.Set<String> terms = new java.util.HashSet<>();
        for (String t : prompt.split("[,，、]")) if (!t.trim().isEmpty()) terms.add(t.trim());
        int n = 0, hits = 0;
        for (String part : text.split("[,，、]")) {
            String p = part.trim().replaceAll("[.!?~…]+$", "");
            if (p.isEmpty()) continue;
            n++;
            if (terms.contains(p)) hits++;
        }
        return n >= 4 && hits >= 0.6 * n;
    }

    public static boolean isHallucination(String t) {
        for (Pattern p : HALLUCINATIONS) if (p.matcher(t).find()) return true;
        return false;
    }

    /** Whisper segment filter: drops segments Whisper itself thinks are non-speech or repetitive. */
    public static boolean segmentOk(double noSpeech, double avgLogprob, double compression) {
        if (noSpeech > 0.6 && avgLogprob < -0.7) return false;
        if (compression > 2.4) return false;
        return !(avgLogprob < -1.2);
    }

    /** Joins kept segment texts, dropping hallucinated ones. */
    public static String joinSegments(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String t = p == null ? "" : p.trim();
            if (t.isEmpty() || isHallucination(t)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }
}
