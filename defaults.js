// Shared default settings (used by the background worker and the popup).
export const DEFAULTS = {
  engine: "free", // "free" = Chrome built-in speech + translator, "keys" = Groq + NVIDIA
  groqKey: "",
  nvidiaKey: "",
  targetLang: "English",
  whisperModel: "whisper-large-v3",
  speechPace: "allday", // "allday" ≈ 8 h of Whisper a day (~15 s delay), "fast" ≈ 3–5 h (~5–10 s)
  llmModel: "meta/llama-3.3-70b-instruct",
  glossary: "",
  chatEnabled: true, // Korean chat → targetLang
  chatToKorean: false, // chat in other languages → Korean
  subsShowOriginal: true,
  subFontSize: 24,
  subPos: null, // { x, y }: centre / bottom edge of the subtitles as a fraction of the player; null = default spot
};

export async function getSettings() {
  const stored = await chrome.storage.local.get(Object.keys(DEFAULTS));
  return { ...DEFAULTS, ...stored };
}

export function todayKey() {
  return new Date().toLocaleDateString("sv-SE"); // YYYY-MM-DD in local time
}
