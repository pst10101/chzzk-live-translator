package io.github.pst10101.chzzktranslator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks a translation model and keeps every model under its free per-minute limit.
 * NVIDIA (if a key is set) first; otherwise Groq. On Groq, subtitles prefer Llama 3.3 70B and chat
 * prefers Llama 3.1 8B (much larger daily allowance), each falling back to the other when rate limited.
 * Chat may only use part of each model's per-minute budget so subtitles always have room.
 */
public final class Llm {
    private Llm() { }

    static final class Model {
        final String base, id;
        final boolean nvidia;
        final int rpm;
        final ArrayDeque<Long> stamps = new ArrayDeque<>();
        long pausedUntil = 0;

        Model(String base, String id, boolean nvidia, int rpm) {
            this.base = base; this.id = id; this.nvidia = nvidia; this.rpm = rpm;
        }
    }

    static final Model NV = new Model(Net.NVIDIA, "meta/llama-3.3-70b-instruct", true, 38);
    static final Model G70 = new Model(Net.GROQ, "llama-3.3-70b-versatile", false, 28);
    static final Model G8 = new Model(Net.GROQ, "llama-3.1-8b-instant", false, 28);

    /** Every model is busy or rate limited right now. */
    public static final class Busy extends Exception {
        Busy() { super("busy"); }
    }

    private static synchronized boolean acquire(Model m, int cap) {
        long now = System.currentTimeMillis();
        while (!m.stamps.isEmpty() && now - m.stamps.peekFirst() >= 60000) m.stamps.pollFirst();
        if (now < m.pausedUntil || m.stamps.size() >= cap) return false;
        m.stamps.addLast(now);
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
            if (chat) { order.add(G8); order.add(G70); } else { order.add(G70); order.add(G8); }
        }
        Exception last = null;
        for (Model m : order) {
            int cap = chat ? (int) (m.rpm * 0.6) : m.rpm;
            if (!acquire(m, cap)) continue;
            String key = m.nvidia ? p.nvKey() : p.groqKey();
            try {
                return Net.chat(m.base, key, m.id, system, user, maxTokens);
            } catch (Net.HttpError e) {
                last = e;
                if (e.code == 429) { pause(m, (long) (Math.max(e.retrySec, 15) * 1000)); continue; }
                if (e.code >= 500 || e.code == 408) { pause(m, 5000); continue; }
                if (m.nvidia && (e.code == 401 || e.code == 403 || e.code == 404)) { pause(m, 10 * 60_000); continue; }
                throw e;
            } catch (java.io.IOException e) {
                last = e; // network hiccup / timeout: try the next model
            }
        }
        if (last instanceof Net.HttpError && ((Net.HttpError) last).code != 429) throw last;
        if (last instanceof java.io.IOException) throw last;
        throw new Busy();
    }
}
