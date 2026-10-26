package io.github.pst10101.chzzktranslator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** HTTPS calls to Groq (Whisper + LLM) and NVIDIA (LLM). Keys go only to these two hosts. */
public final class Net {
    private Net() { }

    public static final String GROQ = "https://api.groq.com/openai/v1";
    public static final String NVIDIA = "https://integrate.api.nvidia.com/v1";

    public static final class HttpError extends Exception {
        public final int code;
        public final String body;
        /** Seconds to wait (Retry-After header or "try again in …" in the body), 0 if none. */
        public final double retrySec;

        HttpError(int code, String body, double retrySec) {
            super("HTTP " + code);
            this.code = code;
            this.body = body;
            this.retrySec = retrySec;
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = s.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }

    private static String finish(HttpURLConnection c) throws Exception {
        try {
            int code = c.getResponseCode();
            String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                throw new HttpError(code, body.length() > 500 ? body.substring(0, 500) : body,
                        AudioUtil.parseRetryAfter(c.getHeaderField("retry-after"), body, 0));
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String key, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    /** Groq Whisper. Returns cleaned Korean text ("" if nothing usable). */
    public static String whisper(byte[] wav, String key, String prompt) throws Exception {
        String boundary = "----ctr" + System.nanoTime();
        HttpURLConnection c = open(GROQ + "/audio/transcriptions", key, 30000);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream o = c.getOutputStream()) {
            field(o, boundary, "model", "whisper-large-v3");
            field(o, boundary, "language", "ko");
            field(o, boundary, "response_format", "verbose_json");
            field(o, boundary, "temperature", "0");
            if (prompt != null && !prompt.isEmpty()) field(o, boundary, "prompt", prompt);
            o.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            o.write(wav);
            o.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        JSONObject j = new JSONObject(finish(c));
        List<String> parts = new ArrayList<>();
        JSONArray segs = j.optJSONArray("segments");
        if (segs != null && segs.length() > 0) {
            for (int i = 0; i < segs.length(); i++) {
                JSONObject s = segs.getJSONObject(i);
                if (AudioUtil.segmentOk(s.optDouble("no_speech_prob", 0), s.optDouble("avg_logprob", 0),
                        s.optDouble("compression_ratio", 1))) {
                    parts.add(s.optString("text", ""));
                }
            }
        } else {
            parts.add(j.optString("text", ""));
        }
        return AudioUtil.joinSegments(parts);
    }

    private static void field(OutputStream o, String boundary, String name, String value) throws Exception {
        o.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** OpenAI-compatible chat completion; returns the message content. */
    public static String chat(String base, String key, String model, String system, String user, int maxTokens) throws Exception {
        HttpURLConnection c = open(base + "/chat/completions", key, 25000);
        c.setRequestProperty("Content-Type", "application/json");
        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", system));
        msgs.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", msgs);
        body.put("temperature", 0.2);
        body.put("top_p", 0.9);
        if (model.startsWith("openai/gpt-oss")) {
            // Reasoning models: think briefly, don't send the thinking back; the limit covers thinking + answer.
            body.put("reasoning_effort", "low");
            body.put("include_reasoning", false);
            body.put("max_completion_tokens", maxTokens + 400);
        } else if (model.startsWith("qwen/")) {
            body.put("reasoning_effort", "none");
            body.put("max_completion_tokens", maxTokens);
        } else {
            body.put("max_tokens", maxTokens);
        }
        body.put("stream", false);
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        JSONObject j = new JSONObject(finish(c));
        JSONArray choices = j.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return "";
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        return msg == null ? "" : msg.optString("content", "");
    }
}
