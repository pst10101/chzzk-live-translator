// Prompt building and response parsing for the translation model (pure, testable in Node).

/**
 * "아이네 = Ine, 징버거" (commas or new lines) -> [{ ko: "아이네", tr: "Ine", note: "" }, { ko: "징버거", tr: "", note: "" }]
 * An optional "| note" after an entry tells the AI who or what the term is ("덕삼이 = Otaku Pork Belly | Hiyori's nickname").
 * Lines starting with # are comments.
 */
export function parseGlossary(text) {
  return String(text || "")
    .split("\n")
    .filter((l) => !l.trim().startsWith("#")) // comment lines
    .join("\n")
    .split(/[\n,]/)
    .map((s) => s.trim())
    .filter(Boolean)
    .map((entry) => {
      const [main, ...noteParts] = entry.split("|");
      const [ko, ...rest] = main.split(/=|→|->/);
      return { ko: ko.trim(), tr: rest.join("=").trim(), note: noteParts.join("|").trim() };
    })
    .filter((e) => e.ko);
}

function entryLine(e) {
  return (e.tr ? `${e.ko} = ${e.tr}` : e.ko) + (e.note ? ` | ${e.note}` : "");
}

/** Korean spellings only — Whisper uses this as a spelling hint. */
export function whisperPromptFrom(glossaryText) {
  const terms = parseGlossary(glossaryText).map((e) => e.ko);
  let out = "";
  for (const t of terms) {
    const next = out ? `${out}, ${t}` : t;
    if (next.length > 300) break;
    out = next;
  }
  return out;
}

/**
 * Built-in StelLive glossary for a target language.
 * en/ja = contents of glossary/stellive-en.txt and -ja.txt ("# section" lines start a section).
 * Japanese → the Japanese file; English → the English file; any other language → names with their
 * official romanization, and only the Korean spelling of everything else (the model translates those).
 */
export function presetGlossary(enText, jaText, lang) {
  const l = String(lang || "").trim().toLowerCase();
  const ja = /^(japanese|ja|日本語)/.test(l);
  const en = /^(english|en)$/.test(l);
  let section = "";
  const out = [];
  for (const raw of String((ja ? jaText : enText) || "").split("\n")) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith("#")) {
      section = line.slice(1).trim().toLowerCase();
      continue;
    }
    if (ja || en || section === "names") {
      out.push(line);
    } else {
      // other languages: Korean term + note only; the model translates it itself
      const [e] = parseGlossary(line);
      if (e) out.push(entryLine({ ...e, tr: "" }));
    }
  }
  return out.join("\n");
}

/** User's own terms first (they win on duplicates), then the built-in ones. */
export function mergeGlossaries(user, preset) {
  const seen = new Set();
  const out = [];
  for (const text of [user, preset]) {
    for (const e of parseGlossary(text)) {
      if (seen.has(e.ko)) continue;
      seen.add(e.ko);
      out.push(entryLine(e));
    }
  }
  return out.join("\n");
}

function glossaryBlock(glossaryText, reverse = false) {
  const entries = parseGlossary(glossaryText);
  if (!entries.length) return "";
  const lines = entries.map((e) => {
    const note = e.note ? ` (${e.note})` : "";
    if (!e.tr) return `- ${e.ko}${note || " (proper name, keep consistent)"}`;
    return reverse ? `- ${e.tr} → ${e.ko}${note}` : `- ${e.ko} → ${e.tr}${note}`;
  });
  return (
    `\n\nGlossary (use these renderings; text in parentheses only explains who or what a term is — never put it in your output):\n` +
    lines.join("\n")
  );
}

export function buildSubtitleMessages({ text, context = [], lang, glossary }) {
  const system =
    `You are a live subtitle translator for a Korean live stream. ` +
    `Translate the CURRENT line from Korean into ${lang}. ` +
    `The input comes from speech recognition and may contain mistakes; translate what the speaker most likely meant. ` +
    `Write natural, casual, short subtitles like a fan translator would. ` +
    `Output ONLY the ${lang} translation of the current line — no quotes, no romanization, no notes.` +
    glossaryBlock(glossary);

  const ctx = context
    .slice(-3)
    .map((c) => `KO: ${c.ko}${c.tr ? `\n${lang}: ${c.tr}` : ""}`)
    .join("\n");
  const user = (ctx ? `Previous lines (context only, do not translate again):\n${ctx}\n\n` : "") + `CURRENT line:\n${text}`;

  return [
    { role: "system", content: system },
    { role: "user", content: user },
  ];
}

/** Chat in other languages (English, Japanese, Chinese…) → natural Korean stream-chat style. */
export function buildChatToKoreanMessages({ texts, glossary }) {
  const system =
    `You translate live-stream chat messages written by foreign viewers (English, Japanese, Chinese and other languages) into Korean. ` +
    `Write short, natural, casual Korean the way Korean stream chat talks (e.g. lol / www / 草 = ㅋㅋ). ` +
    `Keep emojis and emoticons as they are, and keep streamer and member names as they are or as in the glossary. ` +
    `If a message is already Korean, copy it unchanged. ` +
    `You receive a JSON array of N strings. Reply with ONLY a JSON array of exactly N translated strings in the same order.` +
    glossaryBlock(glossary, true);
  return [
    { role: "system", content: system },
    { role: "user", content: JSON.stringify(texts) },
  ];
}

export function buildChatMessages({ texts, lang, glossary }) {
  const system =
    `You translate Korean live-stream chat messages into ${lang}. ` +
    `Chat uses internet slang (e.g. ㅋㅋ = lol, ㄹㅇ = for real, ㅇㅈ = agreed, ㄱㄱ = let's go); translate the meaning naturally and keep it short. ` +
    `Keep emojis and emoticons as they are. If a message is not Korean, copy it unchanged. ` +
    `You receive a JSON array of N strings. Reply with ONLY a JSON array of exactly N translated strings in the same order.` +
    glossaryBlock(glossary);
  return [
    { role: "system", content: system },
    { role: "user", content: JSON.stringify(texts) },
  ];
}

/** Removes reasoning blocks some models emit. */
export function stripThinking(s) {
  return String(s || "")
    .replace(/<think>[\s\S]*?<\/think>/gi, "")
    .replace(/^[\s\S]*?<\/think>/i, "")
    .trim();
}

export function cleanSubtitle(s) {
  let t = stripThinking(s).split("\n").filter((l) => l.trim()).join(" ").trim();
  t = t.replace(/^(translation|current line|번역)\s*[:：]\s*/i, "");
  if (/^["“「『].*["”」』]$/.test(t)) t = t.slice(1, -1).trim();
  return t;
}

/** Returns an array of length n; entries the model failed to give are null. */
export function parseChatResponse(content, n) {
  const text = stripThinking(content).replace(/```(?:json)?/gi, "");
  const start = text.indexOf("[");
  const end = text.lastIndexOf("]");
  if (start !== -1 && end > start) {
    try {
      const arr = JSON.parse(text.slice(start, end + 1));
      if (Array.isArray(arr)) {
        const out = arr.map((x) => (typeof x === "string" ? x.trim() : x == null ? null : String(x)));
        if (out.length === n) return out;
        if (out.length > n) return out.slice(0, n);
        return [...out, ...Array(n - out.length).fill(null)];
      }
    } catch {
      /* fall through */
    }
  }
  // Fallback: numbered or plain lines.
  const lines = text
    .split("\n")
    .map((l) => l.replace(/^\s*(\d+[.)]|[-*])\s*/, "").replace(/^"(.*)",?$/, "$1").trim())
    .filter(Boolean);
  if (lines.length === n) return lines;
  return Array(n).fill(null);
}
