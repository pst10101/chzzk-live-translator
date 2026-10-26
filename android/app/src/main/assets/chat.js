// Runs inside the CHZZK page (m.chzzk.naver.com) in the Android app, after chat-utils.js.
// - Translates chat lines: Korean → your language, and (optional) other languages → Korean.
// - Reports where the video is, so the app can put subtitles on it.
// Talks to the app only through the origin-restricted "CTR" message channel.
(() => {
  if (window.top !== window || window.__ctrApp) return;
  window.__ctrApp = true;
  const Chat = globalThis.CtrChat;
  const port = window.CTR;
  if (!Chat || !port) return;

  // Desktop layout (role="log"), older desktop layout, and the phone layout (nickname button + text span).
  const TEXT_SEL = [
    '[role="log"] [class^="_text_"]',
    '[class^="live_chatting_message_text__"]',
    '[class^="live_chatting_donation_message_text__"]',
    '[class^="_username_"] ~ [class^="_text_"]',
  ].join(",");
  const OURS = "ctr-chat-tr";
  const MAX_BATCH = 30;
  const FLUSH_MS = 2500;

  let cfg = { out: false, ko: false, english: true, ready: false, lang: "" };
  const cache = new Map();
  const pending = [];
  const waiting = new Map(); // id -> { dir, items, texts, at }
  let seq = 0;

  const send = (o) => {
    try {
      port.postMessage(JSON.stringify(o));
    } catch {}
  };

  function addStyle() {
    if (document.getElementById("ctr-style")) return;
    const st = document.createElement("style");
    st.id = "ctr-style";
    st.textContent =
      `.${OURS}{display:block;margin-top:1px;font-size:.92em;font-weight:normal;color:#5fb0ff;overflow-wrap:anywhere}` +
      `.${OURS}.ctr-to-ko{color:#2ec27e}` +
      `.${OURS}.ctr-inline{display:inline;margin-left:6px}`;
    (document.head || document.documentElement).appendChild(st);
  }

  const cacheKey = (dir, unit) => `${dir}\u0001${unit}`;
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

  function render(el, tr, src, count, dir) {
    el.querySelector(`:scope > .${OURS}`)?.remove();
    if (!tr || tr === src) return;
    const span = document.createElement("span");
    span.className = OURS;
    if (dir === "ko") span.classList.add("ctr-to-ko");
    if (el.matches('[class^="_username_"] ~ [class^="_text_"]')) span.classList.add("ctr-inline");
    span.textContent = count > 1 ? `${tr} ×${count}` : tr;
    el.appendChild(span);
  }

  function handle(el) {
    if (!(cfg.out || cfg.ko) || el.closest("button")) return;
    const text = messageText(el);
    if (el.dataset.ctrSrc === text) return;
    el.dataset.ctrSrc = text;
    el.querySelector(`:scope > .${OURS}`)?.remove();
    const c = Chat.classify(text, { fromKorean: cfg.out, toKorean: cfg.ko, english: cfg.english });
    if (!c) return;
    if (c.local) return render(el, c.local, c.unit, c.count, Chat.HANGUL.test(text) ? "out" : "ko");
    const key = cacheKey(c.dir, c.unit);
    if (cache.has(key)) return render(el, cache.get(key), c.unit, c.count, c.dir);
    pending.push({ el, text, dir: c.dir, unit: c.unit, count: c.count });
    if (pending.length > MAX_BATCH * 2) pending.splice(0, pending.length - MAX_BATCH * 2);
  }

  function flush() {
    const now = Date.now();
    for (const [id, w] of waiting) if (now - w.at > 60000) waiting.delete(id); // lost reply
    if (waiting.size || !pending.length) return;
    if (!cfg.ready) {
      pending.length = 0;
      return;
    }
    // One direction per round, alternating, newest messages first.
    const prefer = seq % 2 === 0 ? "out" : "ko";
    const dir = pending.some((p) => p.dir === prefer) ? prefer : pending[0].dir;
    let items = [];
    for (let i = pending.length - 1; i >= 0; i--) if (pending[i].dir === dir) items.unshift(...pending.splice(i, 1));
    items = items.filter((p) => p.el.isConnected && p.el.dataset.ctrSrc === p.text).slice(-MAX_BATCH);
    if (!items.length) return;
    const texts = [...new Set(items.map((i) => i.unit))];
    const id = ++seq;
    waiting.set(id, { dir, items, texts, at: now });
    send({ type: "tr", id, dir, texts });
  }
  setInterval(flush, FLUSH_MS);

  function onTranslated(m) {
    const w = waiting.get(m.id);
    if (!w) return;
    waiting.delete(m.id);
    if (Array.isArray(m.out)) {
      w.texts.forEach((t, i) => m.out[i] && cacheSet(cacheKey(w.dir, t), m.out[i]));
      for (const it of w.items) {
        const tr = cache.get(cacheKey(w.dir, it.unit));
        if (tr && it.el.isConnected && it.el.dataset.ctrSrc === it.text) render(it.el, tr, it.unit, it.count, w.dir);
      }
    } else if (m.retry) {
      pending.unshift(...w.items.slice(-10)); // busy: try the newest again next round
    }
  }

  port.addEventListener("message", (e) => {
    let m;
    try {
      m = JSON.parse(e.data);
    } catch {
      return;
    }
    if (m.type === "cfg") {
      if (m.lang !== cfg.lang) cache.clear();
      cfg = m;
    } else if (m.type === "trDone") {
      onTranslated(m);
    }
  });

  // ---------- watch chat ----------
  const added = new Set();
  const changed = new Set();
  let scheduled = false;
  function processQueued() {
    scheduled = false;
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
    found.forEach(handle);
  }
  function start() {
    addStyle();
    new MutationObserver((muts) => {
      if (!(cfg.out || cfg.ko)) return;
      for (const m of muts) {
        if (m.type === "characterData") {
          if (m.target.parentElement) changed.add(m.target.parentElement);
          continue;
        }
        for (const n of m.addedNodes) {
          if (n.nodeType === 1) {
            if (!n.classList.contains(OURS)) added.add(n);
          } else if (n.nodeType === 3 && n.parentElement) {
            changed.add(n.parentElement);
          }
        }
      }
      if ((added.size || changed.size) && !scheduled) {
        scheduled = true;
        setTimeout(processQueued, 150);
      }
    }).observe(document.documentElement, { childList: true, subtree: true, characterData: true });
    // Lines already on screen (the phone layout only shows a few).
    setTimeout(() => document.querySelectorAll(TEXT_SEL).forEach(handle), 1500);
  }

  // ---------- where is the video? (subtitles are drawn by the app on top of it) ----------
  let lastRect = "";
  function reportVideo() {
    let best = null;
    let area = 0;
    for (const v of document.querySelectorAll("video")) {
      const r = v.getBoundingClientRect();
      if (r.width * r.height > area) {
        area = r.width * r.height;
        best = r;
      }
    }
    const fs = !!document.fullscreenElement || !!document.webkitFullscreenElement;
    const msg = best
      ? { type: "video", top: best.top, bottom: best.bottom, left: best.left, right: best.right, vw: innerWidth, vh: innerHeight, fs }
      : { type: "video", none: true, vw: innerWidth, vh: innerHeight, fs };
    const key = JSON.stringify(msg, (k, v) => (typeof v === "number" ? Math.round(v) : v));
    if (key !== lastRect) {
      lastRect = key;
      send(msg);
    }
  }
  let rafPending = false;
  const onScroll = () => {
    if (rafPending) return;
    rafPending = true;
    requestAnimationFrame(() => {
      rafPending = false;
      reportVideo();
    });
  };
  addEventListener("scroll", onScroll, { passive: true, capture: true });
  addEventListener("resize", onScroll);
  setInterval(reportVideo, 700);

  if (document.documentElement) start();
  else addEventListener("DOMContentLoaded", start, { once: true });
  send({ type: "hello" });
})();
