// Runs on chzzk.naver.com: draws subtitles over the player and translates chat messages.
// Two engines:
//   free — Chrome's built-in speech recognition + built-in translator (engine-main.js), no keys, no limits
//   keys — Groq Whisper + NVIDIA LLM through the background worker
(() => {
  if (window.__ctrLoaded) return;
  window.__ctrLoaded = true;

  const DEFAULTS = {
    engine: "free",
    targetLang: "English",
    chatEnabled: true,
    subsShowOriginal: true,
    subFontSize: 24,
    subPos: null,
    nvidiaKey: "",
  };
  let cfg = { ...DEFAULTS };

  chrome.storage.local.get(Object.keys(DEFAULTS)).then((s) => {
    cfg = { ...cfg, ...s };
    applyFont();
    markExistingChat();
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== "local") return;
    for (const k of Object.keys(changes)) if (k in DEFAULTS) cfg[k] = changes[k].newValue ?? DEFAULTS[k];
    if ("targetLang" in changes || "engine" in changes) {
      cache.clear();
      translatorBlocked = "";
    }
    if ("chatEnabled" in changes && cfg.chatEnabled) markExistingChat();
    if ("subsShowOriginal" in changes) lines.forEach(renderLine);
    if ("engine" in changes && cfg.engine !== "free" && freeRunning) toEngine({ cmd: "stopSpeech" });
    applyFont();
    if ("subPos" in changes) applyPos();
    // Size/position changed from the popup, or "move" was clicked: show a sample line to look at and drag.
    if ("subFontSize" in changes || "subPreviewAt" in changes || ("subPos" in changes && !cfg.subPos)) showPreview();
  });

  // Chrome built-in translator language codes.
  const LANG_CODES = {
    english: "en", japanese: "ja", "chinese (simplified)": "zh", chinese: "zh", "chinese (traditional)": "zh-Hant",
    spanish: "es", "portuguese (brazil)": "pt", portuguese: "pt", french: "fr", german: "de", italian: "it",
    russian: "ru", polish: "pl", turkish: "tr", arabic: "ar", hindi: "hi", indonesian: "id", vietnamese: "vi",
    thai: "th", filipino: "fil", dutch: "nl", swedish: "sv", ukrainian: "uk", czech: "cs", greek: "el",
    danish: "da", finnish: "fi", norwegian: "no", romanian: "ro", hungarian: "hu", bengali: "bn", tamil: "ta",
    telugu: "te", marathi: "mr", kannada: "kn", croatian: "hr", bulgarian: "bg", slovak: "sk", slovenian: "sl",
    lithuanian: "lt", hebrew: "iw",
  };
  function langCode(name) {
    const n = String(name || "").trim();
    if (LANG_CODES[n.toLowerCase()]) return LANG_CODES[n.toLowerCase()];
    if (/^[a-z]{2,3}(-[A-Za-z]{2,4})?$/.test(n)) return n;
    return null;
  }

  // =====================================================================
  // Subtitle overlay
  // =====================================================================

  let overlay = null;
  let linesEl = null;
  let statusEl = null;
  let floating = null;
  let statusTimer = null;
  let resizeObs = null;
  const lines = []; // { id, ko, tr, noTr, el, trEl, koEl, timer }
  const MAX_LINES = 2;
  const LINE_TTL = 9000;
  const PREVIEW_ID = "__preview";
  const PREVIEW_TTL = 30000;

  function findVideo() {
    let best = null;
    let bestArea = -1;
    for (const v of document.querySelectorAll("video")) {
      const r = v.getBoundingClientRect();
      const area = r.width * r.height;
      if (area > bestArea) {
        bestArea = area;
        best = v;
      }
    }
    return best;
  }

  function makeStatusEl() {
    if (!statusEl) {
      statusEl = document.createElement("div");
      statusEl.className = "ctr-status";
      statusEl.addEventListener("click", () => {
        const act = statusEl._action;
        if (!act) return;
        statusEl._action = null;
        statusEl.classList.remove("ctr-show", "ctr-action");
        act();
      });
    }
    return statusEl;
  }

  function ensureOverlay() {
    const video = findVideo();
    const host = video?.parentElement;
    if (!host) return null;
    if (overlay && overlay.parentElement === host) return overlay;

    if (!overlay) {
      overlay = document.createElement("div");
      overlay.className = "ctr-overlay";
      linesEl = document.createElement("div");
      linesEl.className = "ctr-lines";
      overlay.append(linesEl);
      enableDrag();
    }
    overlay.prepend(makeStatusEl());
    if (getComputedStyle(host).position === "static") host.style.position = "relative";
    host.appendChild(overlay);

    resizeObs?.disconnect();
    resizeObs = new ResizeObserver(() => {
      applyFont();
      applyPos();
    });
    resizeObs.observe(host);
    applyFont();
    applyPos();
    return overlay;
  }

  // ---------- subtitle position: drag the subtitles anywhere on the player ----------

  const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi);

  function applyPos() {
    if (!linesEl) return;
    linesEl.classList.toggle("ctr-custom", !!cfg.subPos);
    if (!cfg.subPos) {
      linesEl.style.left = linesEl.style.top = "";
      return;
    }
    fitPos();
  }

  /** Places the subtitles at cfg.subPos, nudged so a long line never sticks out of the player. Returns the spot used. */
  function fitPos() {
    const p = cfg.subPos;
    if (!p || !linesEl) return p;
    let { x, y } = p;
    const W = overlay?.clientWidth;
    const H = overlay?.clientHeight;
    const bw = linesEl.offsetWidth;
    const bh = linesEl.offsetHeight;
    if (W && H && bw) {
      const m = 6;
      x = clamp(x, (bw / 2 + m) / W, 1 - (bw / 2 + m) / W);
      y = clamp(y, (bh + m) / H, 1 - m / H);
    }
    linesEl.style.left = `${(x * 100).toFixed(2)}%`;
    linesEl.style.top = `${(y * 100).toFixed(2)}%`;
    return { x, y };
  }

  function enableDrag() {
    let drag = null;
    linesEl.addEventListener("pointerdown", (e) => {
      if (e.button !== 0 || !lines.length) return;
      const host = overlay.getBoundingClientRect();
      const box = linesEl.getBoundingClientRect();
      drag = {
        id: e.pointerId,
        host,
        // grab point relative to the bottom centre of the subtitles
        dx: e.clientX - (box.left + box.width / 2),
        dy: e.clientY - box.bottom,
        moved: false,
      };
      lines.forEach((l) => clearTimeout(l.timer)); // don't vanish while being dragged
      linesEl.setPointerCapture(e.pointerId);
      linesEl.classList.add("ctr-dragging");
      e.preventDefault();
      e.stopPropagation();
    });
    linesEl.addEventListener("pointermove", (e) => {
      if (!drag || e.pointerId !== drag.id) return;
      const { host } = drag;
      cfg.subPos = {
        x: clamp((e.clientX - drag.dx - host.left) / host.width, 0, 1),
        y: clamp((e.clientY - drag.dy - host.top) / host.height, 0, 1),
      };
      drag.moved = true;
      applyPos();
      e.stopPropagation();
    });
    const end = (e) => {
      if (!drag || e.pointerId !== drag.id) return;
      if (drag.moved) {
        cfg.subPos = fitPos();
        chrome.storage.local.set({ subPos: cfg.subPos }).catch(() => {});
      }
      drag = null;
      linesEl.classList.remove("ctr-dragging");
      if (!linesEl.matches(":hover")) lines.forEach(scheduleRemoval);
      e.stopPropagation();
    };
    linesEl.addEventListener("pointerup", end);
    linesEl.addEventListener("pointercancel", end);
    // Subtitles stay on screen while the mouse is over them (to read or grab them).
    linesEl.addEventListener("pointerenter", () => lines.forEach((l) => clearTimeout(l.timer)));
    linesEl.addEventListener("pointerleave", () => {
      if (!drag) lines.forEach(scheduleRemoval);
    });
    // Clicking the subtitles shouldn't pause the stream or toggle fullscreen.
    for (const t of ["click", "dblclick", "mousedown", "mouseup"]) {
      linesEl.addEventListener(t, (e) => e.stopPropagation());
    }
  }

  /** A sample line so the size and position can be checked (and dragged) while nobody is talking. */
  function showPreview() {
    if (lines.some((l) => l.id !== PREVIEW_ID)) return; // real subtitles are on screen already
    lines.filter((l) => l.id === PREVIEW_ID).forEach(removeLine);
    const line = addLine(PREVIEW_ID, "자막을 마우스로 끌어서 옮길 수 있어요");
    if (!line) return;
    line.tr = "Drag the subtitles to move them";
    line.ttl = PREVIEW_TTL;
    renderLine(line);
    scheduleRemoval(line);
  }

  function applyFont() {
    if (!overlay?.parentElement) return;
    const h = overlay.parentElement.clientHeight || 540;
    const scale = Math.min(2, Math.max(0.75, h / 600));
    overlay.style.setProperty("--ctr-font", `${Math.round((cfg.subFontSize || 24) * scale)}px`);
  }

  function renderLine(line) {
    if (line.tr) {
      line.el.classList.remove("ctr-pending");
      line.trEl.textContent = line.tr;
      line.koEl.textContent = line.ko;
      line.koEl.style.display = cfg.subsShowOriginal ? "" : "none";
    } else if (line.noTr) {
      // No translation available: Korean becomes the main line.
      line.el.classList.remove("ctr-pending");
      line.trEl.textContent = line.ko;
      line.koEl.style.display = "none";
    } else {
      line.el.classList.add("ctr-pending");
      line.trEl.textContent = "";
      line.koEl.textContent = line.ko;
      line.koEl.style.display = "";
    }
    if (line.el.isConnected) fitPos();
  }

  function scheduleRemoval(line) {
    clearTimeout(line.timer);
    if (linesEl?.matches(":hover")) return; // kept while the mouse is on the subtitles
    line.timer = setTimeout(() => removeLine(line), line.ttl || LINE_TTL);
  }

  function removeLine(line) {
    clearTimeout(line.timer);
    line.el.remove();
    const i = lines.indexOf(line);
    if (i !== -1) lines.splice(i, 1);
    fitPos();
  }

  function addLine(id, ko, { noTr = false } = {}) {
    if (!ensureOverlay()) return null;
    if (id !== PREVIEW_ID) lines.filter((l) => l.id === PREVIEW_ID).forEach(removeLine);
    const el = document.createElement("div");
    el.className = "ctr-line";
    const trEl = document.createElement("div");
    trEl.className = "ctr-tr";
    const koEl = document.createElement("div");
    koEl.className = "ctr-ko";
    el.append(trEl, koEl);
    const line = { id, ko, tr: "", noTr, el, trEl, koEl, timer: null };
    renderLine(line);
    linesEl.appendChild(el);
    lines.push(line);
    while (lines.length > MAX_LINES) removeLine(lines[0]);
    fitPos();
    scheduleRemoval(line);
    return line;
  }

  function setTranslation(id, tr) {
    const line = lines.find((l) => l.id === id);
    if (!line) return;
    line.tr = tr;
    renderLine(line);
    scheduleRemoval(line);
  }

  function markNoTranslation(line) {
    if (!line || line.tr) return;
    line.noTr = true;
    renderLine(line);
  }

  /** Small notice in the corner of the player. With an action it becomes clickable and stays until clicked. */
  function showStatus(text, level = "info", { stopped = false, action = null } = {}) {
    if (stopped) [...lines].forEach(removeLine);
    const el = makeStatusEl();
    if (!ensureOverlay()) {
      // Chat-only window: show the notice floating at the top right.
      if (!floating) {
        floating = document.createElement("div");
        floating.className = "ctr-floating";
        document.body.appendChild(floating);
      }
      floating.appendChild(el);
    }
    el.textContent = text;
    el.dataset.level = action ? "action" : level;
    el._action = action;
    el.classList.toggle("ctr-action", !!action);
    el.classList.add("ctr-show");
    clearTimeout(statusTimer);
    if (!action) {
      statusTimer = setTimeout(() => el.classList.remove("ctr-show"), level === "info" ? 4000 : 8000);
    }
  }

  let lastWarn = "";
  function warnOnce(text, level = "warn") {
    if (text === lastWarn) return;
    lastWarn = text;
    showStatus(text, level);
  }

  // =====================================================================
  // Free engine bridge (engine-main.js runs in the page world)
  // =====================================================================

  let engineHello = null; // { hasSpeech, hasTranslator }
  let freeRunning = false;
  let translatorBlocked = ""; // reason the free translator can't be used for the current language
  const waiters = new Map();
  let reqSeq = 0;

  function toEngine(msg) {
    window.dispatchEvent(new CustomEvent("ctr:to-engine", { detail: JSON.stringify(msg) }));
  }

  function engineTranslate(texts, target, priority) {
    return new Promise((resolve, reject) => {
      const id = ++reqSeq;
      const timer = setTimeout(() => {
        waiters.delete(id);
        reject(new Error("TIMEOUT"));
      }, 60000);
      waiters.set(id, { resolve, reject, timer });
      toEngine({ cmd: "translate", id, texts, target, priority });
    });
  }

  function askTranslatorDownload() {
    const code = langCode(cfg.targetLang);
    showStatus(`Click here to download the free ${cfg.targetLang} translator (one time)`, "action", {
      action: () => {
        toEngine({ cmd: "prepareTranslator", target: code });
        showStatus(`Downloading ${cfg.targetLang} translator…`, "info");
      },
    });
  }

  function handleTranslatorError(code) {
    switch (code) {
      case "NEEDS_CLICK":
        askTranslatorDownload();
        break;
      case "LANG_UNAVAILABLE":
        translatorBlocked = code;
        warnOnce(`Chrome's free translator doesn't support ${cfg.targetLang} — showing Korean`);
        break;
      case "NO_TRANSLATOR_API":
        translatorBlocked = code;
        warnOnce("Free translation needs desktop Chrome 138 or newer — showing Korean");
        break;
    }
  }

  const SPEECH_ERRORS = {
    NO_SPEECH_API: "This browser has no built-in speech recognition — use desktop Chrome",
    NO_VIDEO: "No video found — open a live stream first",
    NO_CAPTURE: "Can't read the video's sound in this browser",
    NO_AUDIO: "Couldn't hear the video — make sure the stream is playing, then start again",
    SPEECH_LANG: "Chrome can't recognise Korean speech here",
    START_FAILED: "Couldn't start subtitles — try again",
  };

  window.addEventListener("ctr:from-engine", (e) => {
    let m;
    try {
      m = JSON.parse(e.detail);
    } catch {
      return;
    }
    switch (m.type) {
      case "hello":
        engineHello = m;
        freeRunning = !!m.running;
        break;
      case "speechState":
        freeRunning = !!m.running;
        break;
      case "interim":
        onInterim(m.text);
        break;
      case "final":
        onFinal(m.text);
        break;
      case "translated": {
        const w = waiters.get(m.id);
        if (!w) break;
        waiters.delete(m.id);
        clearTimeout(w.timer);
        if (m.error) w.reject(new Error(m.error));
        else w.resolve(m.out);
        break;
      }
      case "download":
        showStatus(`Downloading ${cfg.targetLang} translator… ${m.pct}%`, "info");
        break;
      case "translatorReady":
        translatorBlocked = "";
        lastWarn = "";
        showStatus(`${cfg.targetLang} translator ready`, "info");
        break;
      case "error":
        if (m.code === "NOT_ALLOWED") {
          showStatus("Click here to start free subtitles", "action", {
            action: () => toEngine({ cmd: "startSpeech", lang: "ko-KR" }),
          });
        } else if (SPEECH_ERRORS[m.code]) {
          showStatus(SPEECH_ERRORS[m.code], "error");
        } else {
          handleTranslatorError(m.code);
        }
        break;
      case "warn":
        if (m.code === "network") warnOnce("Speech service connection hiccup — reconnecting");
        else console.debug("[CHZZK Translator] speech:", m.code);
        break;
    }
  });
  toEngine({ cmd: "hello" });

  // Live subtitles from the free engine: grey Korean while speaking, translation once the phrase ends.
  let openLine = null;
  let freeSeq = 0;

  function onInterim(text) {
    if (!text) return;
    if (openLine && lines.includes(openLine)) {
      openLine.ko = text;
      renderLine(openLine);
      scheduleRemoval(openLine);
    } else {
      openLine = addLine(`f${++freeSeq}`, text);
    }
  }

  function onFinal(text) {
    let line = openLine && lines.includes(openLine) ? openLine : null;
    openLine = null;
    if (line) {
      line.ko = text;
      renderLine(line);
      scheduleRemoval(line);
    } else {
      line = addLine(`f${++freeSeq}`, text);
    }
    if (line) translateFreeSubtitle(line);
  }

  async function translateFreeSubtitle(line) {
    const code = langCode(cfg.targetLang);
    if (translatorBlocked || !code) {
      if (!code) warnOnce(`Free translator: pick ${cfg.targetLang ? "a language from the list" : "a language"}`);
      return markNoTranslation(line);
    }
    try {
      const [tr] = await engineTranslate([line.ko], code, true);
      if (tr) setTranslation(line.id, tr);
      else markNoTranslation(line);
    } catch (e) {
      handleTranslatorError(e.message);
      markNoTranslation(line);
    }
  }

  let fallbackActive = false;

  function startFallback(until) {
    const when = until ? new Date(until).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : "";
    if (!engineHello?.hasSpeech) {
      showStatus(`Groq free limit used up — subtitles resume${when ? ` at ${when}` : " later"}`, "warn");
      return;
    }
    if (fallbackActive) return;
    fallbackActive = true;
    ensureOverlay();
    toEngine({ cmd: "startSpeech", lang: "ko-KR" });
    showStatus(
      `Groq free limit used up — using Chrome's free speech recognition${when ? ` until ${when}` : ""}`,
      "warn"
    );
  }

  function endFallback(announce) {
    if (!fallbackActive) return;
    fallbackActive = false;
    openLine = null;
    toEngine({ cmd: "stopSpeech" });
    if (announce) showStatus("Groq limit reset — back to Whisper", "info");
  }

  function startFree() {
    if (!engineHello) return { ok: false, error: "ENGINE_MISSING" };
    if (!engineHello.hasSpeech) return { ok: false, error: "NO_SPEECH_API" };
    ensureOverlay();
    lastWarn = "";
    toEngine({ cmd: "startSpeech", lang: "ko-KR" });
    showStatus("Listening… subtitles appear as people speak", "info");
    // Warm up the translator now so a one-time download prompt shows up right away.
    const code = langCode(cfg.targetLang);
    if (code && !translatorBlocked) engineTranslate(["안녕하세요"], code, true).catch((e) => handleTranslatorError(e.message));
    return { ok: true };
  }

  // =====================================================================
  // Messages from the popup and the background worker
  // =====================================================================

  chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    switch (msg?.type) {
      case "ctrPing":
        sendResponse({
          ok: true,
          hasVideo: !!findVideo(),
          freeRunning,
          fallbackActive,
          hasSpeech: !!engineHello?.hasSpeech,
          hasTranslator: !!engineHello?.hasTranslator,
        });
        break;
      case "freeStart":
        sendResponse(startFree());
        break;
      case "freeStop":
        toEngine({ cmd: "stopSpeech" });
        freeRunning = false;
        openLine = null;
        showStatus("Subtitles stopped", "info", { stopped: true });
        sendResponse({ ok: true });
        break;
      // keys engine (background worker)
      case "subtitle": {
        // High accuracy mode: Whisper text arrives here. Without an NVIDIA key,
        // Chrome's built-in translator translates it instead.
        const line = addLine(msg.id, msg.ko);
        if (line && !cfg.nvidiaKey) translateFreeSubtitle(line);
        break;
      }
      case "subtitleTr":
        setTranslation(msg.id, msg.tr);
        break;
      case "subStatus":
        if (msg.stopped) endFallback(false);
        showStatus(msg.text, msg.level, { stopped: msg.stopped });
        break;
      // Groq's free limit ran out: Chrome's free speech recognition covers until it resets.
      case "fallbackFree":
        startFallback(msg.until);
        break;
      case "fallbackEnd":
        endFallback(true);
        break;
    }
  });

  // =====================================================================
  // Chat translation
  // =====================================================================

  // Current CHZZK layout uses generated "_text_xxxx" classes inside role="log";
  // the older layout used "live_chatting_message_text__xxxx".
  const TEXT_SEL = [
    '[role="log"] [class^="_text_"]',
    '[class^="live_chatting_message_text__"]',
    '[class^="live_chatting_donation_message_text__"]',
  ].join(",");
  const OURS = "ctr-chat-tr";
  const HANGUL = /[ㄱ-ㆎ가-힣]/;
  const LAUGH = /^[ㅋㅎ\s.!?~]+$/;
  const CRY = /^[ㅠㅜ\s.!?~]+$/;
  const MAX_BATCH = 30;
  const FLUSH_MS = 2000;

  const cache = new Map();
  const pending = [];
  let inflight = false;

  function cacheSet(k, v) {
    cache.set(k, v);
    if (cache.size > 800) cache.delete(cache.keys().next().value);
  }

  function messageText(el) {
    let s = "";
    for (const n of el.childNodes) {
      if (n.nodeType === 1 && n.classList.contains(OURS)) continue;
      s += n.textContent;
    }
    return s.replace(/\s+/g, " ").trim();
  }

  function localTranslation(text) {
    const english = /^(english|en)$/i.test(cfg.targetLang || "");
    if (LAUGH.test(text)) return english ? "lol" : "ㅋㅋ";
    if (CRY.test(text)) return "T_T";
    return null;
  }

  function render(el, tr, src, count = 1) {
    el.querySelector(`:scope > .${OURS}`)?.remove();
    if (!tr || tr === src) return;
    const span = document.createElement("span");
    span.className = OURS;
    span.textContent = count > 1 ? `${tr} ×${count}` : tr;
    el.appendChild(span);
  }

  /**
   * Spam like "변깃물🚽호로록은💦 변깃물🚽호로록은💦 …" or "뿌👻‼️뿌👻‼️…" is the same phrase repeated:
   * translate it once and show "×N". Very short repeats ("네네네") are left as they are.
   */
  function collapseRepeats(text) {
    const norm = text.replace(/\s+/g, " ").trim();
    let best = { unit: norm, count: 1 };
    for (const cand of [norm, `${norm} `]) {
      const ch = Array.from(cand);
      const n = ch.length;
      if (n < 4) continue;
      const pi = new Array(n).fill(0);
      for (let i = 1; i < n; i++) {
        let k = pi[i - 1];
        while (k > 0 && ch[i] !== ch[k]) k = pi[k - 1];
        if (ch[i] === ch[k]) k++;
        pi[i] = k;
      }
      const period = n - pi[n - 1];
      const count = Math.floor(n / period);
      const unit = ch.slice(0, period).join("").trim();
      if (count >= 2 && count > best.count && unit && (Array.from(unit).length >= 3 || n >= 30)) {
        best = { unit, count };
      }
    }
    if (Array.from(best.unit).length > 200) best.unit = Array.from(best.unit).slice(0, 200).join("") + "…";
    return best;
  }

  function handleTextEl(el) {
    if (!cfg.chatEnabled) return;
    if (el.closest("button")) return; // nicknames share the "_text_" class but live inside a button
    const text = messageText(el);
    if (el.dataset.ctrSrc === text) return; // already handled
    el.dataset.ctrSrc = text;
    el.querySelector(`:scope > .${OURS}`)?.remove();
    if (!text || !HANGUL.test(text)) return;

    const local = localTranslation(text);
    if (local) {
      if (local !== text) render(el, local, text);
      return;
    }
    const { unit, count } = collapseRepeats(text);
    const unitLocal = count > 1 ? localTranslation(unit) : null;
    if (unitLocal) {
      render(el, unitLocal, unit, count);
      return;
    }
    if (cache.has(unit)) {
      render(el, cache.get(unit), unit, count);
      return;
    }
    pending.push({ el, text, key: unit, count });
    if (pending.length >= MAX_BATCH) flush();
  }

  // Messages already on screen when the page opens are not translated.
  function markExistingChat() {
    document.querySelectorAll(TEXT_SEL).forEach((el) => {
      el.dataset.ctrSrc = messageText(el);
    });
  }

  // Chrome's built-in translator is used in Free mode, and in High accuracy mode when there is no NVIDIA key.
  const useChromeTranslator = () => cfg.engine === "free" || !cfg.nvidiaKey;

  function chatReady() {
    if (!cfg.chatEnabled) return false;
    if (useChromeTranslator()) return !!engineHello?.hasTranslator && !translatorBlocked && !!langCode(cfg.targetLang);
    return !!cfg.nvidiaKey;
  }

  async function translateBatch(texts) {
    if (useChromeTranslator()) {
      try {
        return { ok: true, translations: await engineTranslate(texts, langCode(cfg.targetLang), false) };
      } catch (e) {
        handleTranslatorError(e.message);
        return { ok: false, error: e.message, quiet: true };
      }
    }
    return chrome.runtime.sendMessage({ target: "background", type: "translateChat", texts });
  }

  async function flush() {
    if (inflight || !pending.length) return;
    if (!chatReady()) {
      if (pending.length > MAX_BATCH) pending.splice(0, pending.length - MAX_BATCH);
      return;
    }
    let items = pending.splice(0).filter((p) => p.el.isConnected && p.el.dataset.ctrSrc === p.text);
    if (items.length > MAX_BATCH) items = items.slice(-MAX_BATCH); // fast chat: newest messages win
    if (!items.length) return;
    const texts = [...new Set(items.map((i) => i.key))];

    inflight = true;
    try {
      const res = await translateBatch(texts);
      if (res?.ok) {
        texts.forEach((t, i) => res.translations[i] && cacheSet(t, res.translations[i]));
        for (const it of items) {
          const tr = cache.get(it.key);
          if (tr && it.el.dataset.ctrSrc === it.text) render(it.el, tr, it.key, it.count);
        }
      } else if (res?.error === "BUSY" || res?.error === "NEEDS_CLICK") {
        pending.unshift(...items.slice(-10));
      } else if (res?.error && !res.quiet && res.error !== "NO_NVIDIA_KEY") {
        warnOnce(res.message || `Chat translation error (${res.error})`);
      }
    } catch {
      /* extension reloaded or worker asleep — next flush retries */
    } finally {
      inflight = false;
    }
  }

  setInterval(flush, FLUSH_MS);

  // Watch for new chat lines (and for React reusing a line with new text).
  const added = new Set(); // new subtrees: look for chat lines inside
  const changed = new Set(); // edited nodes: check the chat line they belong to
  let scanScheduled = false;

  function schedule() {
    if (scanScheduled) return;
    scanScheduled = true;
    setTimeout(processQueued, 150);
  }

  function processQueued() {
    scanScheduled = false;
    const found = new Set();
    for (const el of added) {
      if (!el.isConnected) continue;
      if (el.matches(TEXT_SEL)) found.add(el);
      el.querySelectorAll(TEXT_SEL).forEach((t) => found.add(t));
      const own = el.parentElement?.closest(TEXT_SEL);
      if (own) found.add(own);
    }
    for (const el of changed) {
      const own = el.isConnected && el.closest(TEXT_SEL);
      if (own) found.add(own);
    }
    added.clear();
    changed.clear();
    found.forEach(handleTextEl);
  }

  new MutationObserver((mutations) => {
    if (!cfg.chatEnabled) return;
    for (const m of mutations) {
      if (m.type === "characterData") {
        if (m.target.parentElement) changed.add(m.target.parentElement);
        continue;
      }
      for (const n of m.addedNodes) {
        if (n.nodeType === 1) {
          if (!n.classList.contains(OURS) && !n.classList.contains("ctr-overlay")) added.add(n);
        } else if (n.nodeType === 3 && n.parentElement) {
          changed.add(n.parentElement);
        }
      }
    }
    if (added.size || changed.size) schedule();
  }).observe(document.documentElement, { childList: true, subtree: true, characterData: true });
})();
