// Captures the stream tab's audio, cuts it into speech chunks and sends them to Groq Whisper.
// When Groq's free limit runs out, it tells the background so Chrome's free speech recognition
// takes over, and switches back once the limit resets.
import {
  Resampler,
  Chunker,
  encodeWav,
  cleanTranscript,
  isPromptEcho,
  parseRetryAfter,
  PACES,
  TARGET_RATE,
} from "./audio-utils.js";

const GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
const MIN_GAP_MS = 3100; // keeps us under Groq's 20 requests / minute
const MAX_QUEUE = 3;
const QUOTA_SEC = 60; // a wait at least this long means the hourly/daily free limit is used up

let session = null; // { stream, ctx, node, chunker, cfg }
let queue = [];
let busy = false;
let lastReqAt = 0;
let pausedUntil = 0;
let onFallback = false; // Chrome speech is covering while Groq is out of quota

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const toBg = (msg) => chrome.runtime.sendMessage({ target: "background", ...msg }).catch(() => {});
const status = (text, level = "info") => toBg({ type: "subStatus", text, level });
const paceOf = (cfg) => PACES[cfg?.pace] || PACES.allday;

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg?.target !== "offscreen") return;
  if (msg.type === "start") {
    start(msg).then(
      () => sendResponse({ ok: true }),
      (e) => sendResponse({ ok: false, error: String(e?.message || e) })
    );
    return true;
  }
  if (msg.type === "stop") {
    stop();
    sendResponse({ ok: true });
  } else if (msg.type === "updateCfg") {
    if (session) {
      session.cfg = { ...session.cfg, ...msg.cfg };
      const p = paceOf(session.cfg);
      session.chunker.setLimits(p.minSec, p.maxSec);
    }
    sendResponse({ ok: true });
  } else if (msg.type === "ping") {
    sendResponse({ ok: true });
  }
});

async function start(cfg) {
  stop();
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { mandatory: { chromeMediaSource: "tab", chromeMediaSourceId: cfg.streamId } },
    video: false,
  });

  const ctx = new AudioContext();
  if (ctx.state === "suspended") await ctx.resume().catch(() => {});
  const src = ctx.createMediaStreamSource(stream);
  src.connect(ctx.destination); // capturing a tab mutes it — play it back so the viewer still hears it

  await ctx.audioWorklet.addModule("pcm-worklet.js");
  const node = new AudioWorkletNode(ctx, "pcm-capture");
  const silent = ctx.createGain();
  silent.gain.value = 0;
  src.connect(node);
  node.connect(silent);
  silent.connect(ctx.destination); // keeps the worklet running without adding sound

  const resampler = new Resampler(ctx.sampleRate, TARGET_RATE);
  const chunker = new Chunker(onChunk, paceOf(cfg));
  node.port.onmessage = (e) => chunker.push(resampler.push(e.data));

  stream.getAudioTracks()[0]?.addEventListener("ended", () => {
    stop();
    toBg({ type: "captureEnded", reason: "ended" });
  });

  session = { stream, ctx, node, chunker, cfg };
  queue = [];
  pausedUntil = 0;
  onFallback = false;
}

function stop() {
  if (!session) return;
  const s = session;
  session = null;
  queue = [];
  onFallback = false;
  try {
    s.node.port.onmessage = null;
  } catch {}
  s.stream.getTracks().forEach((t) => t.stop());
  s.ctx.close().catch(() => {});
}

function onChunk(samples) {
  if (!session) return;
  if (Date.now() < pausedUntil) return;
  if (onFallback) {
    // The limit has reset: hand back from Chrome speech to Whisper.
    onFallback = false;
    toBg({ type: "groqResumed" });
  }
  queue.push(samples);
  if (queue.length > MAX_QUEUE) queue.shift(); // falling behind: drop the oldest, live subs must stay live
  pump();
}

async function pump() {
  if (busy || !queue.length || !session) return;
  busy = true;
  try {
    const wait = lastReqAt + MIN_GAP_MS - Date.now();
    if (wait > 0) await sleep(wait);
    const samples = queue.shift();
    if (samples && session && Date.now() >= pausedUntil) await transcribe(samples, session.cfg);
  } catch (e) {
    console.warn("transcribe failed", e);
  } finally {
    busy = false;
  }
  if (queue.length) pump();
}

function fmtWait(sec) {
  return sec > 120 ? `${Math.ceil(sec / 60)} min` : `${Math.ceil(sec)}s`;
}

async function transcribe(samples, cfg) {
  lastReqAt = Date.now();
  const sec = samples.length / TARGET_RATE;

  const fd = new FormData();
  fd.append("file", new Blob([encodeWav(samples)], { type: "audio/wav" }), "audio.wav");
  fd.append("model", cfg.model || "whisper-large-v3");
  fd.append("language", cfg.lang || "ko");
  fd.append("response_format", "verbose_json");
  fd.append("temperature", "0");
  if (cfg.whisperPrompt) fd.append("prompt", cfg.whisperPrompt);

  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), 30000);
  let res;
  try {
    res = await fetch(GROQ_URL, {
      method: "POST",
      headers: { Authorization: `Bearer ${cfg.groqKey}` },
      body: fd,
      signal: ac.signal,
    });
  } catch {
    status("Speech service unreachable — retrying", "warn");
    return;
  } finally {
    clearTimeout(timer);
  }

  toBg({ type: "usage", groqReq: 1, groqSec: sec });

  if (res.status === 401) {
    status("Groq API key is invalid — check it in the extension popup", "error");
    stop();
    toBg({ type: "captureEnded", reason: "badkey" });
    return;
  }
  if (res.status === 429) {
    const body = await res.text().catch(() => "");
    const ra = parseRetryAfter(res.headers.get("retry-after"), body, 30);
    pausedUntil = Date.now() + ra * 1000;
    queue = [];
    if (ra >= QUOTA_SEC) {
      // Free hourly/daily limit used up: let Chrome's free speech recognition cover until it resets.
      if (!onFallback) {
        onFallback = true;
        toBg({ type: "groqQuota", until: pausedUntil });
      }
    } else {
      status(`Speech limit reached — resuming in ${fmtWait(ra)}`, "warn");
    }
    return;
  }
  if (!res.ok) {
    console.warn("Groq error", res.status, await res.text().catch(() => ""));
    status(`Speech service error (${res.status})`, "warn");
    return;
  }

  const text = cleanTranscript(await res.json());
  if (text && !isPromptEcho(text, cfg.whisperPrompt)) toBg({ type: "transcript", text });
}
