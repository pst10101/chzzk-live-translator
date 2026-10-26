import { DEFAULTS, getSettings } from "./defaults.js";

// [stored name used in prompts, label, Chrome built-in translator code]
const LANGS = [
  ["English", "English", "en"],
  ["Japanese", "日本語 (Japanese)", "ja"],
  ["Chinese (Simplified)", "简体中文", "zh"],
  ["Chinese (Traditional)", "繁體中文", "zh-Hant"],
  ["Spanish", "Español", "es"],
  ["Portuguese (Brazil)", "Português (BR)", "pt"],
  ["French", "Français", "fr"],
  ["German", "Deutsch", "de"],
  ["Italian", "Italiano", "it"],
  ["Russian", "Русский", "ru"],
  ["Polish", "Polski", "pl"],
  ["Turkish", "Türkçe", "tr"],
  ["Arabic", "العربية", "ar"],
  ["Hindi", "हिन्दी", "hi"],
  ["Indonesian", "Bahasa Indonesia", "id"],
  ["Vietnamese", "Tiếng Việt", "vi"],
  ["Thai", "ไทย", "th"],
  ["Filipino", "Filipino", "fil"],
  ["__custom", "Other…", null],
];

const $ = (id) => document.getElementById(id);
const els = {
  engineRadios: [...document.querySelectorAll('input[name="engine"]')],
  toggle: $("toggle"),
  status: $("status"),
  targetLang: $("targetLang"),
  customLang: $("customLang"),
  chatEnabled: $("chatEnabled"),
  chatToKorean: $("chatToKorean"),
  subsShowOriginal: $("subsShowOriginal"),
  subFontSize: $("subFontSize"),
  groqKey: $("groqKey"),
  nvidiaKey: $("nvidiaKey"),
  whisperModel: $("whisperModel"),
  llmModel: $("llmModel"),
  glossary: $("glossary"),
  stelPreset: $("stelPreset"),
  setup: $("setup"),
  usage: $("usage"),
  freeNote: $("freeNote"),
  keysNote: $("keysNote"),
  paceRow: $("paceRow"),
  speechPace: $("speechPace"),
  uGroq: $("uGroq"),
  uNv: $("uNv"),
  test: $("test"),
  testResult: $("testResult"),
  loadModels: $("loadModels"),
  modelList: $("modelList"),
};

let engine = DEFAULTS.engine;
let keysCapturing = false;
let freeRunning = false;

function setStatus(el, text, kind = "") {
  el.textContent = text;
  el.className = `status ${kind}`;
}

// ---------- settings ----------

function save(patch) {
  return chrome.storage.local.set(patch);
}

let saveTimer = null;
function saveSoon(patch) {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => save(patch), 300);
}

function showEngine() {
  const free = engine === "free";
  els.engineRadios.forEach((r) => (r.checked = r.value === engine));
  els.freeNote.classList.toggle("hidden", !free);
  els.keysNote.classList.toggle("hidden", free);
  els.paceRow.classList.toggle("hidden", free);
  els.usage.classList.toggle("hidden", free);
  els.customLang.placeholder = free
    ? "Language name or code, e.g. Dutch or nl"
    : "Language name, e.g. Swahili";
}

async function loadSettings() {
  const s = await getSettings();
  engine = s.engine;

  for (const [value, label] of LANGS) els.targetLang.add(new Option(label, value));
  const known = LANGS.some(([v]) => v === s.targetLang);
  els.targetLang.value = known ? s.targetLang : "__custom";
  els.customLang.value = known ? "" : s.targetLang;
  els.customLang.classList.toggle("hidden", known);

  els.chatEnabled.checked = s.chatEnabled;
  els.chatToKorean.checked = s.chatToKorean;
  els.subsShowOriginal.checked = s.subsShowOriginal;
  els.subFontSize.value = s.subFontSize;
  els.groqKey.value = s.groqKey;
  els.nvidiaKey.value = s.nvidiaKey;
  els.whisperModel.value = s.whisperModel;
  els.speechPace.value = s.speechPace;
  els.llmModel.value = s.llmModel;
  els.glossary.value = s.glossary;
  els.stelPreset.checked = s.stelPreset;

  if (engine === "keys" && (!s.groqKey || !s.nvidiaKey)) els.setup.open = true;
  showEngine();
}

els.engineRadios.forEach((r) =>
  r.addEventListener("change", async () => {
    if (!r.checked) return;
    if (isRunning()) await stopAll();
    engine = r.value;
    await save({ engine });
    showEngine();
    if (engine === "keys" && (!els.groqKey.value || !els.nvidiaKey.value)) els.setup.open = true;
    setStatus(els.status, "");
    refreshState();
  })
);

els.targetLang.addEventListener("change", () => {
  const custom = els.targetLang.value === "__custom";
  els.customLang.classList.toggle("hidden", !custom);
  if (custom) els.customLang.focus();
  else save({ targetLang: els.targetLang.value });
});
els.customLang.addEventListener("input", () => {
  const v = els.customLang.value.trim();
  if (v) saveSoon({ targetLang: v });
});
els.chatEnabled.addEventListener("change", () => save({ chatEnabled: els.chatEnabled.checked }));
els.chatToKorean.addEventListener("change", () => {
  if (els.chatToKorean.checked) warmKoreanTranslators();
  save({ chatToKorean: els.chatToKorean.checked });
});
els.subsShowOriginal.addEventListener("change", () => save({ subsShowOriginal: els.subsShowOriginal.checked }));
els.subFontSize.addEventListener("input", () => saveSoon({ subFontSize: Number(els.subFontSize.value) }));
// The CHZZK tab listens for these: "move" shows a sample line to drag, "reset" puts the subtitles back at the bottom.
$("movePos").addEventListener("click", () => save({ subPreviewAt: Date.now() }));
$("resetPos").addEventListener("click", () => save({ subPos: null, subPreviewAt: Date.now() }));
els.groqKey.addEventListener("input", () => saveSoon({ groqKey: els.groqKey.value.trim() }));
els.nvidiaKey.addEventListener("input", () => saveSoon({ nvidiaKey: els.nvidiaKey.value.trim() }));
els.whisperModel.addEventListener("change", () => save({ whisperModel: els.whisperModel.value }));
els.speechPace.addEventListener("change", () => save({ speechPace: els.speechPace.value }));
els.llmModel.addEventListener("input", () =>
  saveSoon({ llmModel: els.llmModel.value.trim() || DEFAULTS.llmModel })
);
els.glossary.addEventListener("input", () => saveSoon({ glossary: els.glossary.value }));
els.stelPreset.addEventListener("change", () => save({ stelPreset: els.stelPreset.checked }));

// ---------- state ----------

const isRunning = () => (engine === "free" ? freeRunning : keysCapturing);

function renderState(state) {
  if (state) {
    keysCapturing = !!state.capturing;
    const u = state.usage;
    if (u) {
      const min = Math.round((u.groqSec || 0) / 60);
      els.uGroq.textContent = `${min} / 480 min · ${u.groqReq} / 2,000 req`;
      els.uNv.textContent = `${u.nvReq} requests`;
    }
  }
  const running = isRunning();
  els.toggle.textContent = running ? "Stop subtitles" : "Start live subtitles";
  els.toggle.classList.toggle("stop", running);
}

chrome.runtime.onMessage.addListener((msg) => {
  if (msg?.target === "popup" && msg.type === "state") renderState(msg);
});

async function activeTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  return tab;
}

async function pingTab(tab) {
  if (!tab?.url?.startsWith("https://chzzk.naver.com/")) return null;
  return chrome.tabs.sendMessage(tab.id, { type: "ctrPing" }).catch(() => null);
}

async function refreshState() {
  const ping = await pingTab(await activeTab());
  freeRunning = !!ping?.freeRunning;
  renderState(await chrome.runtime.sendMessage({ target: "background", type: "getState" }));
}

async function stopAll() {
  const tab = await activeTab();
  if (freeRunning && tab) await chrome.tabs.sendMessage(tab.id, { type: "freeStop" }).catch(() => {});
  if (keysCapturing) await chrome.runtime.sendMessage({ target: "background", type: "stopSubs" });
  freeRunning = false;
  keysCapturing = false;
}

// ---------- start / stop ----------

const START_ERRORS = {
  NO_GROQ_KEY: "Add your Groq API key under “API keys & models”.",
  OFFSCREEN_FAILED: "Couldn't start audio capture. Try again.",
  ENGINE_MISSING: "Reload the CHZZK page once, then try again.",
  NO_SPEECH_API: "This browser has no built-in speech recognition. Use desktop Chrome.",
};

function selectedLangCode() {
  const row = LANGS.find(([v]) => v === els.targetLang.value);
  return row?.[2] || null;
}

// Uses this click to let Chrome download its translator if needed (best effort).
function warmTranslator() {
  const code = selectedLangCode();
  if (!code || !("Translator" in self)) return;
  try {
    Translator.create({ sourceLanguage: "ko", targetLanguage: code }).catch(() => {});
  } catch {}
}

// Foreign chat → Korean: use this click to fetch Chrome's free translators for the common chat languages.
function warmKoreanTranslators() {
  try {
    if ("LanguageDetector" in self) LanguageDetector.create().catch(() => {});
    if (!("Translator" in self)) return;
    for (const src of ["en", "ja", "zh"]) {
      Translator.create({ sourceLanguage: src, targetLanguage: "ko" }).catch(() => {});
    }
  } catch {}
}

els.toggle.addEventListener("click", async () => {
  if (engine === "free" && !isRunning()) {
    warmTranslator();
    if (els.chatToKorean.checked) warmKoreanTranslators();
  }
  els.toggle.disabled = true;
  try {
    if (isRunning()) {
      await stopAll();
      setStatus(els.status, "Stopped.");
      return;
    }

    const tab = await activeTab();
    if (!tab?.url?.startsWith("https://chzzk.naver.com/")) {
      setStatus(els.status, "Open a CHZZK live stream in this tab first.", "warn");
      return;
    }
    const ping = await pingTab(tab);
    if (!ping) {
      setStatus(els.status, "Reload the CHZZK page once, then try again.", "warn");
      return;
    }
    if (!ping.hasVideo) {
      setStatus(els.status, "No video player on this page — open a live stream.", "warn");
      return;
    }

    let res;
    if (engine === "free") {
      res = await chrome.tabs.sendMessage(tab.id, { type: "freeStart" }).catch(() => null);
      if (res?.ok) freeRunning = true;
    } else {
      clearTimeout(saveTimer);
      await save({ groqKey: els.groqKey.value.trim(), nvidiaKey: els.nvidiaKey.value.trim() });
      if (!els.groqKey.value.trim()) {
        els.setup.open = true;
        setStatus(els.status, START_ERRORS.NO_GROQ_KEY, "warn");
        return;
      }
      const streamId = await chrome.tabCapture.getMediaStreamId({ targetTabId: tab.id });
      res = await chrome.runtime.sendMessage({ target: "background", type: "startSubs", tabId: tab.id, streamId });
    }

    if (res?.ok) {
      setStatus(
        els.status,
        engine === "free" ? "Listening. Subtitles follow the speech live." : "Listening. Subtitles appear a few seconds after speech.",
        "ok"
      );
    } else {
      setStatus(els.status, START_ERRORS[res?.error] || `Couldn't start: ${res?.error || "unknown error"}`, "err");
    }
  } catch (e) {
    setStatus(els.status, `Couldn't start: ${e?.message || e}`, "err");
  } finally {
    els.toggle.disabled = false;
    if (engine === "free") renderState();
    else refreshState();
  }
});

// ---------- key test & model list ----------

async function testGroq(key) {
  if (!key) return "Groq: no key";
  try {
    const r = await fetch("https://api.groq.com/openai/v1/models", { headers: { Authorization: `Bearer ${key}` } });
    if (r.ok) return "Groq ✓";
    if (r.status === 401) return "Groq ✗ invalid key";
    return `Groq ✗ error ${r.status}`;
  } catch {
    return "Groq ✗ can't reach server";
  }
}

async function testNvidia(key, model) {
  if (!key) return "NVIDIA: no key";
  try {
    const r = await fetch("https://integrate.api.nvidia.com/v1/chat/completions", {
      method: "POST",
      headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        model,
        messages: [{ role: "user", content: "Translate into English, reply with the translation only: 안녕하세요 여러분" }],
        max_tokens: 30,
        temperature: 0,
      }),
    });
    if (r.ok) {
      const d = await r.json();
      const out = String(d?.choices?.[0]?.message?.content || "").replace(/<think>[\s\S]*?<\/think>/g, "").trim();
      return `NVIDIA ✓ “${out.slice(0, 40)}”`;
    }
    if (r.status === 401 || r.status === 403) return "NVIDIA ✗ invalid key";
    if (r.status === 404) return "NVIDIA ✗ model not found";
    if (r.status === 429) return "NVIDIA ✗ rate limited, try again in a minute";
    return `NVIDIA ✗ error ${r.status}`;
  } catch {
    return "NVIDIA ✗ can't reach server";
  }
}

els.test.addEventListener("click", async () => {
  els.test.disabled = true;
  setStatus(els.testResult, "Testing…");
  const model = els.llmModel.value.trim() || DEFAULTS.llmModel;
  const [g, n] = await Promise.all([testGroq(els.groqKey.value.trim()), testNvidia(els.nvidiaKey.value.trim(), model)]);
  const ok = g.includes("✓") && n.includes("✓");
  setStatus(els.testResult, `${g} · ${n}`, ok ? "ok" : "warn");
  els.test.disabled = false;
});

const SKIP_MODEL = /embed|rerank|reward|guard|safety|parse|detect|clip|retriev|ocr|vista|cosmos|flux|sdxl|stable-diffusion|asr|tts|whisper|canary|parakeet/i;

els.loadModels.addEventListener("click", async () => {
  const key = els.nvidiaKey.value.trim();
  els.loadModels.textContent = "loading…";
  try {
    const r = await fetch("https://integrate.api.nvidia.com/v1/models", {
      headers: key ? { Authorization: `Bearer ${key}` } : {},
    });
    const d = await r.json();
    const ids = (d?.data || []).map((m) => m.id).filter((id) => id && !SKIP_MODEL.test(id)).sort();
    els.modelList.replaceChildren(...ids.map((id) => new Option(id, id)));
    els.loadModels.textContent = `${ids.length} models`;
    els.llmModel.focus();
  } catch {
    els.loadModels.textContent = "failed — retry";
  }
});

// ---------- init ----------

loadSettings().then(refreshState);
