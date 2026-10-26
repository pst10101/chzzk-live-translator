// Pure audio helpers (no browser APIs) so they can be unit-tested in Node.

export const TARGET_RATE = 16000;
const FRAME = 1600; // 100 ms at 16 kHz

/** Windowed-sinc (Blackman) low-pass FIR taps. */
function lowpassTaps(cutoffHz, rate, taps = 41) {
  const fc = cutoffHz / rate;
  const mid = (taps - 1) / 2;
  const h = new Float32Array(taps);
  let sum = 0;
  for (let i = 0; i < taps; i++) {
    const x = i - mid;
    const sinc = x === 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
    const w = 0.42 - 0.5 * Math.cos((2 * Math.PI * i) / (taps - 1)) + 0.08 * Math.cos((4 * Math.PI * i) / (taps - 1));
    h[i] = sinc * w;
    sum += h[i];
  }
  for (let i = 0; i < taps; i++) h[i] /= sum;
  return h;
}

/**
 * Streaming resampler: any rate -> 16 kHz mono.
 * Low-passes at 7 kHz first (so music/hiss above 8 kHz doesn't fold back into the speech band),
 * then averages the input samples each output sample covers.
 */
export class Resampler {
  constructor(srcRate, dstRate = TARGET_RATE) {
    this.ratio = srcRate / dstRate;
    this.pos = 0;
    this.left = new Float32Array(0);
    this.h = srcRate > dstRate ? lowpassTaps(dstRate * 0.44, srcRate) : null;
    this.hist = this.h ? new Float32Array(this.h.length - 1) : null;
  }

  _filter(input) {
    const h = this.h;
    const n = h.length;
    const data = new Float32Array(this.hist.length + input.length);
    data.set(this.hist, 0);
    data.set(input, this.hist.length);
    const out = new Float32Array(input.length);
    for (let i = 0; i < input.length; i++) {
      let s = 0;
      for (let k = 0; k < n; k++) s += h[k] * data[i + k];
      out[i] = s;
    }
    this.hist = data.slice(data.length - (n - 1));
    return out;
  }

  push(raw) {
    const input = this.h ? this._filter(raw) : raw;
    const data = new Float32Array(this.left.length + input.length);
    data.set(this.left, 0);
    data.set(input, this.left.length);

    const out = [];
    const r = this.ratio;
    while (this.pos + r <= data.length) {
      const start = Math.floor(this.pos);
      const end = Math.max(start + 1, Math.floor(this.pos + r));
      let sum = 0;
      for (let i = start; i < end; i++) sum += data[i];
      out.push(sum / (end - start));
      this.pos += r;
    }
    const keep = Math.floor(this.pos);
    this.left = data.slice(keep);
    this.pos -= keep;
    return Float32Array.from(out);
  }
}

function rmsOf(arr, from, to) {
  let s = 0;
  for (let i = from; i < to; i++) s += arr[i] * arr[i];
  return Math.sqrt(s / Math.max(1, to - from));
}

/**
 * Cuts a continuous 16 kHz stream into speech chunks.
 * - Cuts at a short pause once the chunk is at least minSec long.
 * - Never lets a chunk grow past maxSec: cuts at the quietest moment of the last 3 s.
 * - Drops chunks with almost no voice (saves quota, avoids Whisper hallucinations).
 * Works with background music by judging "silence" relative to recent loudness.
 */
export class Chunker {
  constructor(onChunk, opts = {}) {
    this.onChunk = onChunk;
    this.minFrames = Math.round((opts.minSec ?? 4) * 10);
    this.maxFrames = Math.round((opts.maxSec ?? 12) * 10);
    this.silenceFrames = opts.silenceFrames ?? 3; // 300 ms pause
    this.absSilence = opts.absSilence ?? 0.004;
    this.absVoice = opts.absVoice ?? 0.012;
    this.relSilence = opts.relSilence ?? 0.3;
    this.minVoicedFrames = opts.minVoicedFrames ?? 5; // 0.5 s of voice

    this.pending = new Float32Array(0); // samples not yet forming a whole frame
    this.frames = []; // { data: Float32Array(FRAME), rms, silent, voiced }
    this.peak = 0;
    this.silentRun = 0;
  }

  push(samples16k) {
    const data = new Float32Array(this.pending.length + samples16k.length);
    data.set(this.pending, 0);
    data.set(samples16k, this.pending.length);
    let off = 0;
    while (off + FRAME <= data.length) {
      this._frame(data.slice(off, off + FRAME));
      off += FRAME;
    }
    this.pending = data.slice(off);
  }

  _frame(frame) {
    const rms = rmsOf(frame, 0, frame.length);
    this.peak = Math.max(rms, this.peak * 0.98);
    const silent = rms < Math.max(this.absSilence, this.relSilence * this.peak);
    const voiced = !silent && rms > this.absVoice;
    this.frames.push({ data: frame, rms, silent, voiced });
    this.silentRun = silent ? this.silentRun + 1 : 0;

    // Nothing said yet: keep only a short pre-roll so chunks start near speech.
    if (!this.frames.some((f) => f.voiced) && this.frames.length > 20) {
      this.frames = this.frames.slice(-3);
      return;
    }

    const n = this.frames.length;
    if (n >= this.minFrames && this.silentRun >= this.silenceFrames) {
      this._emit(n);
    } else if (n >= this.maxFrames) {
      // Cut at the quietest frame in the last 3 seconds.
      let best = n - 1;
      for (let i = Math.max(this.minFrames, n - 30); i < n; i++) {
        if (this.frames[i].rms < this.frames[best].rms) best = i;
      }
      this._emit(best + 1);
    }
  }

  _emit(count) {
    const take = this.frames.slice(0, count);
    this.frames = this.frames.slice(count);
    this.silentRun = 0;
    const voiced = take.filter((f) => f.voiced).length;
    if (voiced < this.minVoicedFrames) return;
    const out = new Float32Array(take.length * FRAME);
    take.forEach((f, i) => out.set(f.data, i * FRAME));
    this.onChunk(out);
  }

  flush() {
    if (this.frames.length) this._emit(this.frames.length);
  }

  /** Change chunk length on the fly (e.g. when the user switches Fast / All day). */
  setLimits(minSec, maxSec) {
    this.minFrames = Math.round(minSec * 10);
    this.maxFrames = Math.round(maxSec * 10);
  }
}

/**
 * Chunk lengths per "Whisper timing" setting.
 * Groq's free tier allows 2,000 requests and 28,800 audio seconds a day, so ~14 s chunks
 * use up the whole 8 hours instead of running out of requests after 3–5 hours.
 */
export const PACES = {
  fast: { minSec: 4, maxSec: 12 },
  allday: { minSec: 13, maxSec: 20 },
};

/**
 * Seconds to wait after a 429, from the Retry-After header and/or Groq's message
 * ("... Please try again in 1h2m3.5s ..."). Returns the larger of the two, or `fallback`.
 */
export function parseRetryAfter(header, body, fallback = 30) {
  let sec = parseFloat(header);
  if (!Number.isFinite(sec)) sec = 0;
  const m = /try again in\s+((?:[\d.]+h)?\s*(?:[\d.]+m(?!s))?\s*(?:[\d.]+s)?\s*(?:[\d.]+ms)?)/i.exec(String(body || ""));
  if (m) {
    const part = (re) => parseFloat((re.exec(m[1]) || [])[1] || 0);
    const parsed = part(/([\d.]+)h/) * 3600 + part(/([\d.]+)m(?!s)/) * 60 + part(/([\d.]+)s/) + part(/([\d.]+)ms/) / 1000;
    sec = Math.max(sec, parsed);
  }
  return sec > 0 ? sec : fallback;
}

/** 16-bit PCM mono WAV. Returns an ArrayBuffer. */
export function encodeWav(samples, rate = TARGET_RATE) {
  const buf = new ArrayBuffer(44 + samples.length * 2);
  const v = new DataView(buf);
  const str = (o, s) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  str(0, "RIFF");
  v.setUint32(4, 36 + samples.length * 2, true);
  str(8, "WAVE");
  str(12, "fmt ");
  v.setUint32(16, 16, true);
  v.setUint16(20, 1, true); // PCM
  v.setUint16(22, 1, true); // mono
  v.setUint32(24, rate, true);
  v.setUint32(28, rate * 2, true);
  v.setUint16(32, 2, true);
  v.setUint16(34, 16, true);
  str(36, "data");
  v.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    v.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return buf;
}

// Phrases Whisper tends to invent on silence / music in Korean.
const HALLUCINATIONS = [
  /시청\s*해\s*주셔서\s*감사합니다/,
  /구독\s*(과|와)?\s*좋아요/,
  /좋아요\s*(와|과)?\s*구독/,
  /(MBC|KBS|SBS|YTN|JTBC)\s*뉴스/,
  /자막\s*(제공|by|BY|협찬)/,
  /한글\s*자막/,
  /다음\s*(영상|시간)에서\s*(만나요|뵙겠습니다)/,
];

/**
 * Whisper sometimes just repeats its prompt (our list of names) over music or silence.
 * True when the text is a comma-separated list made mostly of prompt terms.
 */
export function isPromptEcho(text, prompt) {
  if (!text || !prompt) return false;
  const terms = new Set(String(prompt).split(/[,，、]/).map((s) => s.trim()).filter(Boolean));
  const parts = String(text)
    .split(/[,，、]/)
    .map((s) => s.trim().replace(/[.!?~…]+$/, ""))
    .filter(Boolean);
  if (parts.length < 4) return false;
  return parts.filter((p) => terms.has(p)).length / parts.length >= 0.6;
}

export function isHallucination(text) {
  return HALLUCINATIONS.some((re) => re.test(text));
}

/**
 * Turns a Groq verbose_json transcription into clean text.
 * Drops segments Whisper itself thinks are non-speech or repetitive.
 */
export function cleanTranscript(resp) {
  let parts;
  if (Array.isArray(resp?.segments) && resp.segments.length) {
    parts = resp.segments
      .filter((s) => !(s.no_speech_prob > 0.6 && s.avg_logprob < -0.7))
      .filter((s) => !(s.compression_ratio > 2.4))
      .filter((s) => !(s.avg_logprob < -1.2))
      .map((s) => String(s.text || "").trim());
  } else {
    parts = [String(resp?.text || "").trim()];
  }
  parts = parts.filter((p) => p && !isHallucination(p));
  return parts.join(" ").replace(/\s+/g, " ").trim();
}
