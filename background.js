// Service worker: starts/stops audio capture, translates subtitles and chat with NVIDIA's free API.
import { getSettings, todayKey } from "./defaults.js";
import {
  buildSubtitleMessages,
  buildChatMessages,
  buildChatToKoreanMessages,
  parseChatResponse,
  cleanSubtitle,
  whisperPromptFrom,
} from "./translate-utils.js";

const NV_URL = "https://integrate.api.nvidia.com/v1/chat/completions";
const NV_LIMIT = 38; // NVIDIA free tier allows ~40 requests / minute
const NV_CHAT_LIMIT = 28; // chat may use up to this many; the rest is kept for subtitles

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class ApiError extends Error {
  constructor(code, detail = "") {
    super(code);
    this.code = code;
    this.detail = detail;
  }
}

// ---------- messaging helpers ----------

async function getCaptureTab() {
  const { captureTabId } = await chrome.storage.session.get("captureTabId");
  return captureTabId ?? null;
}

function sendTab(tabId, msg) {
  if (tabId == null) return;
  chrome.tabs.sendMessage(tabId, msg).catch(() => {});
}

async function broadcastState() {
  chrome.runtime.sendMessage({ target: "popup", type: "state", ...(await getState()) }).catch(() => {});
}

async function getState() {
  const tabId = await getCaptureTab();
  const { usage } = await chrome.storage.local.get("usage");
  const today = usage?.day === todayKey() ? usage : { day: todayKey(), groqReq: 0, groqSec: 0, nvReq: 0 };
  return { capturing: tabId != null && (await hasOffscreen()), tabId, usage: today };
}

// ---------- usage counter ----------

let usageChain = Promise.resolve();
function bumpUsage({ groqReq = 0, groqSec = 0, nvReq = 0 }) {
  usageChain = usageChain.then(async () => {
    const { usage } = await chrome.storage.local.get("usage");
    const u = usage?.day === todayKey() ? usage : { day: todayKey(), groqReq: 0, groqSec: 0, nvReq: 0 };
    u.groqReq += groqReq;
    u.groqSec += groqSec;
    u.nvReq += nvReq;
    await chrome.storage.local.set({ usage: u });
  });
  return usageChain;
}

// ---------- offscreen document (audio capture) ----------

async function hasOffscreen() {
  const ctx = await chrome.runtime.getContexts({ contextTypes: ["OFFSCREEN_DOCUMENT"] });
  return ctx.length > 0;
}

async function closeOffscreen() {
  if (!(await hasOffscreen())) return;
  await chrome.runtime.sendMessage({ target: "offscreen", type: "stop" }).catch(() => {});
  await chrome.offscreen.closeDocument().catch(() => {});
}

async function waitForOffscreen() {
  for (let i = 0; i < 40; i++) {
    const r = await chrome.runtime.sendMessage({ target: "offscreen", type: "ping" }).catch(() => null);
    if (r?.ok) return true;
    await sleep(100);
  }
  return false;
}

async function startSubs(tabId, streamId) {
  const cfg = await getSettings();
  if (!cfg.groqKey) return { ok: false, error: "NO_GROQ_KEY" };

  await closeOffscreen();
  await chrome.offscreen.createDocument({
    url: "offscreen.html",
    reasons: ["USER_MEDIA"],
    justification: "Capture the stream tab's audio to create live subtitles.",
  });
  if (!(await waitForOffscreen())) {
    await closeOffscreen();
    return { ok: false, error: "OFFSCREEN_FAILED" };
  }

  const res = await chrome.runtime
    .sendMessage({
      target: "offscreen",
      type: "start",
      streamId,
      groqKey: cfg.groqKey,
      model: cfg.whisperModel,
      lang: "ko",
      pace: cfg.speechPace,
      whisperPrompt: whisperPromptFrom(cfg.glossary),
    })
    .catch((e) => ({ ok: false, error: String(e?.message || e) }));

  if (!res?.ok) {
    await closeOffscreen();
    return { ok: false, error: res?.error || "CAPTURE_FAILED" };
  }

  await chrome.storage.session.set({ captureTabId: tabId });
  subCtx = [];
  sendTab(tabId, {
    type: "subStatus",
    level: "info",
    text: cfg.nvidiaKey
      ? "Listening… subtitles appear a few seconds after speech"
      : "Listening… (translating with Chrome's built-in translator)",
  });
  broadcastState();
  return { ok: true };
}

async function stopSubs(reason = "user") {
  const tabId = await getCaptureTab();
  await closeOffscreen();
  await chrome.storage.session.remove("captureTabId");
  if (tabId != null) {
    const text =
      reason === "badkey"
        ? "Subtitles stopped: Groq API key is invalid"
        : reason === "ended"
          ? "Subtitles stopped: the tab's audio ended"
          : "Subtitles stopped";
    sendTab(tabId, { type: "subStatus", text, level: reason === "badkey" ? "error" : "info", stopped: true });
  }
  broadcastState();
}

chrome.tabs.onRemoved.addListener(async (tabId) => {
  if (tabId === (await getCaptureTab())) stopSubs("closed");
});

// Settings edited while subtitles run: hand the new values to the capture page.
chrome.storage.onChanged.addListener(async (changes, area) => {
  if (area !== "local") return;
  if (!["groqKey", "whisperModel", "glossary", "speechPace"].some((k) => k in changes)) return;
  if (!(await hasOffscreen())) return;
  const cfg = await getSettings();
  chrome.runtime
    .sendMessage({
      target: "offscreen",
      type: "updateCfg",
      cfg: {
        groqKey: cfg.groqKey,
        model: cfg.whisperModel,
        pace: cfg.speechPace,
        whisperPrompt: whisperPromptFrom(cfg.glossary),
      },
    })
    .catch(() => {});
});

// ---------- NVIDIA translation ----------

const nv = { stamps: [], pausedUntil: 0 };

/** Waits for a free slot under the per-minute limit. Returns false if it would take longer than maxWaitMs. */
async function nvAcquire(isChat, maxWaitMs) {
  const deadline = Date.now() + maxWaitMs;
  for (;;) {
    const now = Date.now();
    nv.stamps = nv.stamps.filter((t) => now - t < 60000);
    const cap = isChat ? NV_CHAT_LIMIT : NV_LIMIT;
    if (now >= nv.pausedUntil && nv.stamps.length < cap) {
      nv.stamps.push(now);
      return true;
    }
    const waitPause = nv.pausedUntil - now;
    const waitSlot = nv.stamps.length >= cap ? nv.stamps[nv.stamps.length - cap] + 60000 - now : 0;
    const wait = Math.max(200, waitPause, waitSlot);
    if (now + wait > deadline) return false;
    await sleep(wait);
  }
}

async function nvChat(cfg, messages, maxTokens) {
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), 25000);
  let res;
  try {
    res = await fetch(NV_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${cfg.nvidiaKey}`,
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: JSON.stringify({
        model: cfg.llmModel,
        messages,
        temperature: 0.2,
        top_p: 0.9,
        max_tokens: maxTokens,
        stream: false,
      }),
      signal: ac.signal,
    });
  } catch (e) {
    throw new ApiError(e?.name === "AbortError" ? "TIMEOUT" : "NETWORK");
  } finally {
    clearTimeout(timer);
  }

  bumpUsage({ nvReq: 1 });

  if (res.ok) {
    const data = await res.json();
    return data?.choices?.[0]?.message?.content ?? "";
  }
  const body = await res.text().catch(() => "");
  if (res.status === 429) {
    const ra = parseFloat(res.headers.get("retry-after")) || 15;
    nv.pausedUntil = Date.now() + ra * 1000;
    throw new ApiError("RATE_LIMIT", body);
  }
  if (res.status === 401 || res.status === 403) throw new ApiError("BAD_KEY", body);
  if (res.status === 404 || /not\s*found|unknown model|does not exist/i.test(body)) throw new ApiError("BAD_MODEL", body);
  throw new ApiError(`HTTP_${res.status}`, body);
}

function errorText(code) {
  switch (code) {
    case "BAD_KEY":
      return "NVIDIA API key is invalid — check it in the extension popup";
    case "BAD_MODEL":
      return "Translation model not found — pick another in the extension popup";
    case "RATE_LIMIT":
      return "Translation limit reached — waiting a moment";
    case "TIMEOUT":
      return "Translation is slow right now — skipping a line";
    default:
      return `Translation error (${code})`;
  }
}

// Subtitles: show Korean right away, then fill in the translation.
let subCtx = [];
let subSeq = 0;
let subChain = Promise.resolve();

async function handleTranscript(text) {
  const tabId = await getCaptureTab();
  if (tabId == null) return;
  const id = ++subSeq;
  sendTab(tabId, { type: "subtitle", id, ko: text });
  subChain = subChain.then(() => translateSubtitle(id, text, tabId)).catch(() => {});
}

async function translateSubtitle(id, text, tabId) {
  const cfg = await getSettings();
  if (!cfg.nvidiaKey) return;
  if (id < subSeq - 2) return; // newer lines are already waiting; keep subtitles live
  if (!(await nvAcquire(false, 15000))) return;
  try {
    const messages = buildSubtitleMessages({ text, context: subCtx, lang: cfg.targetLang, glossary: cfg.glossary });
    const tr = cleanSubtitle(await nvChat(cfg, messages, 300));
    subCtx = [...subCtx, { ko: text, tr }].slice(-3);
    if (tr) sendTab(tabId, { type: "subtitleTr", id, tr });
  } catch (e) {
    sendTab(tabId, { type: "subStatus", text: errorText(e.code), level: "warn" });
  }
}

/** dir "out": Korean → targetLang, dir "ko": other languages → Korean. */
async function translateChat(texts, dir = "out") {
  const cfg = await getSettings();
  if (!cfg.nvidiaKey) return { ok: false, error: "NO_NVIDIA_KEY" };
  if (!Array.isArray(texts) || !texts.length) return { ok: true, translations: [] };
  if (!(await nvAcquire(true, 4000))) return { ok: false, error: "BUSY" };
  try {
    const messages =
      dir === "ko"
        ? buildChatToKoreanMessages({ texts, glossary: cfg.glossary })
        : buildChatMessages({ texts, lang: cfg.targetLang, glossary: cfg.glossary });
    const content = await nvChat(cfg, messages, Math.min(4000, 100 + texts.length * 80));
    return { ok: true, translations: parseChatResponse(content, texts.length) };
  } catch (e) {
    return { ok: false, error: e.code || String(e), message: errorText(e.code) };
  }
}

// ---------- router ----------

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg?.target !== "background") return;
  switch (msg.type) {
    // from popup
    case "startSubs":
      startSubs(msg.tabId, msg.streamId).then(sendResponse, (e) =>
        sendResponse({ ok: false, error: String(e?.message || e) })
      );
      return true;
    case "stopSubs":
      stopSubs("user").then(() => sendResponse({ ok: true }));
      return true;
    case "getState":
      getState().then(sendResponse);
      return true;
    // from content script
    case "translateChat":
      translateChat(msg.texts, msg.dir).then(sendResponse);
      return true;
    // from offscreen
    case "transcript":
      handleTranscript(msg.text);
      break;
    case "subStatus":
      getCaptureTab().then((tabId) => sendTab(tabId, { type: "subStatus", text: msg.text, level: msg.level }));
      break;
    case "usage":
      bumpUsage(msg).then(broadcastState);
      break;
    case "captureEnded":
      stopSubs(msg.reason);
      break;
    // Groq free limit used up / reset: the page switches to Chrome speech and back.
    case "groqQuota":
      getCaptureTab().then((tabId) => sendTab(tabId, { type: "fallbackFree", until: msg.until }));
      break;
    case "groqResumed":
      getCaptureTab().then((tabId) => sendTab(tabId, { type: "fallbackEnd" }));
      break;
  }
});
