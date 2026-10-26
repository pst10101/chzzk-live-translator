package io.github.pst10101.chzzktranslator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks a translation model and keeps every model under its free limits.
 * NVIDIA (if a key is set) first; otherwise Groq's free models (30 requests and 8,000 tokens a minute,
 * 1,000 requests a day each). Subtitles prefer GPT-OSS 120B, chat prefers the faster GPT-OSS 20B; each falls
 * back to the others when one is rate limited or gone. Chat may only use part of each model's budget so
 * subtitles always have room.
 */
public final class Llm {
    private Llm() { }

    static final class Model {
        final String base, id;
        final boolean nvidia;
        final int rpm, tpm;
        final ArrayDeque<long[]> stamps = new ArrayDeque<>(); // {time, estimated tokens}
        long pausedUntil = 0;

        Model(String base, String id, boolean nvidia, int rpm, int tpm) {
            this.base = base; this.id = id; this.nvidia = nvidia; this.rpm = rpm; this.tpm = tpm;
        }
    }

    static final Model NV = new Model(Net.NVIDIA, "meta/llama-3.3-70b-instruct", true, 38, 1_000_000);
    // Groq retired llama-3.3-70b-versatile / llama-3.1-8b-instant for free accounts on 2026-08-16.
    static final Model G120 = new Model(Net.GROQ, "openai/gpt-oss-120b", false, 27, 7200);
    static final Model G20 = new Model(Net.GROQ, "openai/gpt-oss-20b", false, 27, 7200);
    static final Model GQ = new Model(Net.GROQ, "qwen/qwen3.8-27b", false, 27, 7200);

    /** Every model is busy or rate limited right now. */
    public static final class Busy extends Exception {
        Busy() { super("busy"); }
    }

    /** Rough token count: Korean/Japanese text is ~1 token per 1–2 characters. */
    static int estimate(String system, String user, int maxTokens) {
        return (system.length() + user.length()) * 2 / 3 + maxTokens / 2 + 20;
    }

    private static synchronized boolean acquire(Model m, double share, int tokens) {
        long now = System.currentTimeMillis();
        while (!m.stamps.isEmpty() && now - m.stamps.peekFirst()[0] >= 60000) m.stamps.pollFirst();
        if (now < m.pausedUntil) return false;
        long used = 0;
        for (long[] s : m.stamps) used += s[1];
        if (m.stamps.size() >= (int) (m.rpm * share) || used + tokens > (long) (m.tpm * share)) return false;
        m.stamps.addLast(new long[]{now, tokens});
        return true;
    }

    private static synchronized void pause(Model m, long ms) {
        m.pausedUntil = Math.max(m.pausedUntil, System.currentTimeMillis() + ms);
    }

    public static boolean hasKey(Prefs p) {
        return !p.nvKey().isEmpty() || !p.groqKey().isEmpty();
    }

    public static String complete(Prefs p, boolean chat, String system, String user, int maxTokens) throws Exception {
        List<Model> order = new ArrayList<>();
        if (!p.nvKey().isEmpty()) order.add(NV);
        if (!p.groqKey().isEmpty()) {
            if (chat) { order.add(G20); order.add(GQ); order.add(G120); } else { order.add(G120); order.add(G20); order.add(GQ); }
        }
        Exception last = null;
        int tokens = estimate(system, user, maxTokens);
        for (Model m : order) {
            if (!acquire(m, chat ? 0.6 : 1.0, tokens)) continue;
            String key = m.nvidia ? p.nvKey() : p.groqKey();
            try {
                String out = Net.chat(m.base, key, m.id, system, user, maxTokens);
                if (out.trim().isEmpty()) { // ran out of tokens while thinking: try another model
                    last = new java.io.IOException("empty");
                    continue;
                }
                return out;
            } catch (Net.HttpError e) {
                last = e;
                if (e.code == 429) { pause(m, (long) (Math.max(e.retrySec, 15) * 1000)); continue; }
                if (e.code >= 500 || e.code == 408) { pause(m, 5000); continue; }
                // Model retired / not offered to this account: leave it alone for a while, use the others.
                if (e.code == 404 || (e.code == 400 && e.body != null && e.body.contains("model"))) { pause(m, 30 * 60_000); continue; }
                if (m.nvidia && (e.code == 401 || e.code == 403)) { pause(m, 10 * 60_000); continue; }
                throw e;
            } catch (java.io.IOException e) {
                last = e; // network hiccup / timeout: try the next model
            }
        }
        if (last instanceof Net.HttpError && ((Net.HttpError) last).code != 429) throw last;
        if (last instanceof java.io.IOException && !"empty".equals(last.getMessage())) throw last;
        throw new Busy();
    }
}
