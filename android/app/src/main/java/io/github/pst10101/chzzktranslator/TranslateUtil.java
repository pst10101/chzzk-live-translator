package io.github.pst10101.chzzktranslator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Prompt building and response parsing, ported from the extension's translate-utils.js.
 * Pure Java (no org.json) so it can be unit-tested on a PC.
 */
public final class TranslateUtil {
    private TranslateUtil() { }

    /** One glossary entry: Korean spelling and (optional) preferred rendering. */
    public static final class Entry {
        public final String ko, tr;
        Entry(String ko, String tr) { this.ko = ko; this.tr = tr; }
    }

    /** "아이네 = Ine, 징버거" (commas or new lines) -> [(아이네, Ine), (징버거, "")] */
    public static List<Entry> parseGlossary(String text) {
        List<Entry> out = new ArrayList<>();
        if (text == null) return out;
        StringBuilder noComments = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.trim().startsWith("#")) noComments.append(line).append('\n');
        }
        for (String raw : noComments.toString().split("[\\n,]")) {
            String e = raw.trim();
            if (e.isEmpty()) continue;
            String[] parts = e.split("=|→|->", 2);
            String ko = parts[0].trim();
            String tr = parts.length > 1 ? parts[1].replaceAll("=|→|->", "=").trim() : "";
            if (!ko.isEmpty()) out.add(new Entry(ko, tr));
        }
        return out;
    }

    /**
     * Built-in StelLive glossary for a target language (same rules as the extension):
     * Japanese → the Japanese file, English → the English file, other languages → names with their
     * official romanization and only the Korean spelling of everything else.
     */
    public static String presetGlossary(String enText, String jaText, String lang) {
        String l = lang == null ? "" : lang.trim().toLowerCase(java.util.Locale.ROOT);
        boolean ja = l.startsWith("japanese") || l.startsWith("ja") || l.startsWith("日本語");
        boolean en = l.equals("english") || l.equals("en");
        String src = ja ? jaText : enText;
        StringBuilder out = new StringBuilder();
        String section = "";
        for (String raw : (src == null ? "" : src).split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                section = line.substring(1).trim().toLowerCase(java.util.Locale.ROOT);
                continue;
            }
            if (out.length() > 0) out.append('\n');
            out.append(ja || en || section.equals("names") ? line : line.split("=")[0].trim());
        }
        return out.toString();
    }

    /** The user's own terms first (they win on duplicates), then the built-in ones. */
    public static String mergeGlossaries(String user, String preset) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        StringBuilder out = new StringBuilder();
        for (String text : new String[]{user, preset}) {
            for (Entry e : parseGlossary(text)) {
                if (!seen.add(e.ko)) continue;
                if (out.length() > 0) out.append('\n');
                out.append(e.tr.isEmpty() ? e.ko : e.ko + " = " + e.tr);
            }
        }
        return out.toString();
    }

    /** Korean spellings only (max 300 chars) — Whisper uses this as a spelling hint. */
    public static String whisperPrompt(String glossary) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : parseGlossary(glossary)) {
            int extra = (sb.length() > 0 ? 2 : 0) + e.ko.length();
            if (sb.length() + extra > 300) break;
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.ko);
        }
        return sb.toString();
    }

    static String glossaryBlock(String glossary, boolean reverse) {
        List<Entry> entries = parseGlossary(glossary);
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nGlossary (use these renderings):");
        for (Entry e : entries) {
            sb.append("\n- ");
            if (e.tr.isEmpty()) sb.append(e.ko).append(" (proper name, keep consistent)");
            else if (reverse) sb.append(e.tr).append(" → ").append(e.ko);
            else sb.append(e.ko).append(" → ").append(e.tr);
        }
        return sb.toString();
    }

    /** {system, user} for one subtitle line; context = previous {ko, tr} pairs (oldest first). */
    public static String[] subtitlePrompt(String text, List<String[]> context, String lang, String glossary) {
        String system = "You are a live subtitle translator for a Korean live stream. "
                + "Translate the CURRENT line from Korean into " + lang + ". "
                + "The input comes from speech recognition and may contain mistakes; translate what the speaker most likely meant. "
                + "Write natural, casual, short subtitles like a fan translator would. "
                + "Output ONLY the " + lang + " translation of the current line — no quotes, no romanization, no notes."
                + glossaryBlock(glossary, false);
        StringBuilder user = new StringBuilder();
        if (context != null && !context.isEmpty()) {
            user.append("Previous lines (context only, do not translate again):\n");
            int from = Math.max(0, context.size() - 3);
            for (int i = from; i < context.size(); i++) {
                String[] c = context.get(i);
                if (i > from) user.append('\n');
                user.append("KO: ").append(c[0]);
                if (c[1] != null && !c[1].isEmpty()) user.append('\n').append(lang).append(": ").append(c[1]);
            }
            user.append("\n\n");
        }
        user.append("CURRENT line:\n").append(text);
        return new String[]{system, user.toString()};
    }

    /** Korean chat -> lang. */
    public static String chatSystem(String lang, String glossary) {
        return "You translate Korean live-stream chat messages into " + lang + ". "
                + "Chat uses internet slang (e.g. ㅋㅋ = lol, ㄹㅇ = for real, ㅇㅈ = agreed, ㄱㄱ = let's go); translate the meaning naturally and keep it short. "
                + "Keep emojis and emoticons as they are. If a message is not Korean, copy it unchanged. "
                + "You receive a JSON array of N strings. Reply with ONLY a JSON array of exactly N translated strings in the same order."
                + glossaryBlock(glossary, false);
    }

    /** Chat in other languages -> natural Korean stream-chat style. */
    public static String chatToKoreanSystem(String glossary) {
        return "You translate live-stream chat messages written by foreign viewers (English, Japanese, Chinese and other languages) into Korean. "
                + "Write short, natural, casual Korean the way Korean stream chat talks (e.g. lol / www / 草 = ㅋㅋ). "
                + "Keep emojis and emoticons as they are, and keep streamer and member names as they are or as in the glossary. "
                + "If a message is already Korean, copy it unchanged. "
                + "You receive a JSON array of N strings. Reply with ONLY a JSON array of exactly N translated strings in the same order."
                + glossaryBlock(glossary, true);
    }

    /** Removes reasoning blocks some models emit. */
    public static String stripThinking(String s) {
        if (s == null) return "";
        return s.replaceAll("(?is)<think>.*?</think>", "").replaceAll("(?is)^.*?</think>", "").trim();
    }

    public static String cleanSubtitle(String s) {
        StringBuilder sb = new StringBuilder();
        for (String l : stripThinking(s).split("\n")) {
            if (l.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(l.trim());
        }
        String t = sb.toString().trim().replaceFirst("(?i)^(translation|current line|번역)\\s*[:：]\\s*", "");
        if (t.length() >= 2 && t.matches("(?s)^[\"“「『].*[\"”」』]$")) t = t.substring(1, t.length() - 1).trim();
        return t;
    }

    /** Returns a list of length n; entries the model failed to give are null. */
    public static List<String> parseChatResponse(String content, int n) {
        String text = stripThinking(content).replaceAll("(?i)```(?:json)?", "");
        int start = text.indexOf('['), end = text.lastIndexOf(']');
        if (start != -1 && end > start) {
            List<String> arr = parseJsonArray(text.substring(start, end + 1));
            if (arr != null) {
                List<String> out = new ArrayList<>(arr.subList(0, Math.min(n, arr.size())));
                while (out.size() < n) out.add(null);
                return out;
            }
        }
        // Fallback: numbered or plain lines.
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\n")) {
            String t = l.replaceFirst("^\\s*(\\d+[.)]|[-*])\\s*", "").replaceFirst("^\"(.*)\",?$", "$1").trim();
            if (!t.isEmpty()) lines.add(t);
        }
        if (lines.size() == n) return lines;
        return new ArrayList<>(Arrays.asList(new String[n]));
    }

    /** Parses a JSON array whose items are strings (others become null / their text). Null if malformed. */
    public static List<String> parseJsonArray(String s) {
        List<String> out = new ArrayList<>();
        int i = skipWs(s, 0);
        if (i >= s.length() || s.charAt(i) != '[') return null;
        i = skipWs(s, i + 1);
        if (i < s.length() && s.charAt(i) == ']') return out;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    if (d == '"') { closed = true; i++; break; }
                    if (d == '\\' && i + 1 < s.length()) {
                        char e = s.charAt(++i);
                        switch (e) {
                            case 'n': sb.append('\n'); break;
                            case 't': sb.append('\t'); break;
                            case 'r': sb.append('\r'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'u':
                                if (i + 4 >= s.length()) return null;
                                try { sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); } catch (NumberFormatException ex) { return null; }
                                i += 4;
                                break;
                            default: sb.append(e);
                        }
                        i++;
                    } else {
                        sb.append(d);
                        i++;
                    }
                }
                if (!closed) return null;
                out.add(sb.toString().trim());
            } else {
                int j = i;
                while (j < s.length() && s.charAt(j) != ',' && s.charAt(j) != ']') j++;
                String tok = s.substring(i, j).trim();
                if (tok.isEmpty() || tok.startsWith("[") || tok.startsWith("{")) return null;
                out.add("null".equals(tok) ? null : tok);
                i = j;
            }
            i = skipWs(s, i);
            if (i >= s.length()) return null;
            if (s.charAt(i) == ',') { i = skipWs(s, i + 1); continue; }
            if (s.charAt(i) == ']') return out;
            return null;
        }
        return null;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    public static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case ' ': sb.append("\\u2028"); break;
                case ' ': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    public static String jsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonString(items.get(i)));
        }
        return sb.append(']').toString();
    }
}
