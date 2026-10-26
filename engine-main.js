// FREE engine. Runs in the page's own JS world (like a normal web page script) so it can use
// Chrome's built-in speech recognition and built-in translator — no API keys, no quotas.
// Talks to content.js (isolated world) through window CustomEvents carrying JSON strings.
(() => {
  if (window.__ctrEngine) return;
  window.__ctrEngine = true;

  const emit = (type, data = {}) =>
    window.dispatchEvent(new CustomEvent("ctr:from-engine", { detail: JSON.stringify({ type, ...data }) }));

  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;

  // ---------------------------------------------------------------------------
  // Speech: the video's own audio track -> SpeechRecognition (continuous, live interim text)
  // ---------------------------------------------------------------------------

  let rec = null;
  let running = false;
  let lang = "ko-KR";
  let track = null;
  let trackVideo = null;
  let restartTimer = null;
  let failures = 0;

  function findVideo() {
    let best = null;
    let bestArea = -1;
    for (const v of document.querySelectorAll("video")) {
      const r = v.getBoundingClientRect();
      if (r.width * r.height > bestArea) {
        bestArea = r.width * r.height;
        best = v;
      }
    }
    return best;
  }

  async function getTrack() {
    const video = findVideo();
    if (!video) throw new Error("NO_VIDEO");
    if (track && track.readyState === "live" && trackVideo === video) return track;

    const capture = video.captureStream || video.mozCaptureStream;
    if (!capture) throw new Error("NO_CAPTURE");
    const stream = capture.call(video);
    let t = stream.getAudioTracks()[0];
    if (!t) {
      t = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("NO_AUDIO")), 6000);
        stream.addEventListener("addtrack", (e) => {
          if (e.track.kind === "audio") {
            clearTimeout(timer);
            resolve(e.track);
          }
        });
      });
    }
    track = t;
    trackVideo = video;
    return t;
  }

  async function launch() {
    const t = await getTrack();
    const r = new SR();
    r.lang = lang;
    r.continuous = true;
    r.interimResults = true;
    r.maxAlternatives = 1;

    r.onresult = (e) => {
      failures = 0;
      let interim = "";
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const res = e.results[i];
        const text = (res[0]?.transcript || "").trim();
        if (res.isFinal) {
          if (text) emit("final", { text });
        } else {
          interim += ` ${text}`;
        }
      }
      emit("interim", { text: interim.trim() });
    };

    r.onerror = (e) => {
      if (e.error === "not-allowed" || e.error === "service-not-allowed") {
        running = false;
        emit("error", { code: "NOT_ALLOWED" });
      } else if (e.error === "language-not-supported") {
        running = false;
        emit("error", { code: "SPEECH_LANG" });
      } else if (e.error !== "no-speech" && e.error !== "aborted") {
        emit("warn", { code: e.error });
      }
    };

    r.onend = () => {
      if (rec === r) rec = null;
      if (running) scheduleRestart(250); // Chrome ends sessions now and then — just keep going
      else emit("speechState", { running: false });
    };

    rec = r;
    r.start(t);
  }

  function scheduleRestart(ms) {
    clearTimeout(restartTimer);
    restartTimer = setTimeout(async () => {
      if (!running || rec) return;
      try {
        await launch();
      } catch (err) {
        failures++;
        emit("warn", { code: err.message || "RESTART_FAILED" });
        scheduleRestart(Math.min(10000, 1000 * failures));
      }
    }, ms);
  }

  async function startSpeech(newLang) {
    if (!SR) return emit("error", { code: "NO_SPEECH_API" });
    lang = newLang || lang;
    if (rec) {
      try {
        rec.onend = null;
        rec.abort();
      } catch {}
      rec = null;
    }
    running = true;
    failures = 0;
    try {
      await launch();
      emit("speechState", { running: true });
    } catch (err) {
      running = false;
      emit("error", { code: err.message || "START_FAILED" });
      emit("speechState", { running: false });
    }
  }

  function stopSpeech() {
    running = false;
    clearTimeout(restartTimer);
    if (rec) {
      try {
        rec.abort();
      } catch {}
    } else {
      emit("speechState", { running: false });
    }
  }

  // ---------------------------------------------------------------------------
  // Translation: Chrome's built-in on-device translator
  // ---------------------------------------------------------------------------

  const translators = new Map(); // "source>target" -> Promise<Translator>

  function createTranslator(target, source = "ko") {
    const key = `${source}>${target}`;
    const p = Translator.create({
      sourceLanguage: source,
      targetLanguage: target,
      monitor(m) {
        m.addEventListener("downloadprogress", (e) =>
          emit("download", { target, source, pct: Math.round(e.loaded * 100) })
        );
      },
    });
    translators.set(key, p);
    p.then(
      () => emit("translatorReady", { target, source }),
      () => translators.delete(key)
    );
    return p;
  }

  async function getTranslator(target, source = "ko") {
    if (!("Translator" in self)) throw new Error("NO_TRANSLATOR_API");
    const key = `${source}>${target}`;
    if (translators.has(key)) return translators.get(key);
    const avail = await Translator.availability({ sourceLanguage: source, targetLanguage: target });
    if (avail === "unavailable") throw new Error("LANG_UNAVAILABLE");
    if (avail === "downloadable" && !navigator.userActivation?.isActive) throw new Error("NEEDS_CLICK");
    try {
      return await createTranslator(target, source);
    } catch (e) {
      throw new Error(e?.name === "NotAllowedError" ? "NEEDS_CLICK" : e?.message || "TRANSLATOR_FAILED");
    }
  }

  // Called straight from a click so Chrome allows the one-time language pack download.
  function prepare(target, source = "ko") {
    if (!("Translator" in self)) return emit("error", { code: "NO_TRANSLATOR_API" });
    if (translators.has(`${source}>${target}`)) return;
    createTranslator(target, source).catch((e) =>
      emit("error", { code: e?.name === "NotAllowedError" ? "NEEDS_CLICK" : "TRANSLATOR_FAILED" })
    );
  }

  // ---------- language detection (foreign chat → Korean) ----------

  let detectorP = null;
  function getDetector() {
    if (!("LanguageDetector" in self)) return Promise.resolve(null);
    if (!detectorP) {
      detectorP = (async () => {
        const avail = await LanguageDetector.availability();
        if (avail === "unavailable") return null;
        if (avail !== "available" && !navigator.userActivation?.isActive) return null; // needs a click to download
        return LanguageDetector.create();
      })().catch(() => null);
      detectorP.then((d) => {
        if (!d) detectorP = null; // try again later (e.g. after a click)
      });
    }
    return detectorP;
  }

  // Language by script, no model needed (same as chat-utils.js scriptLang — that file runs in the other world).
  function SCRIPT_LANG(text) {
    if (/[\u3040-\u30ff\u31f0-\u31ff\uff66-\uff9f]/.test(text)) return "ja";
    if (/[\u4e00-\u9fff\u3400-\u4dbf]/.test(text)) return "zh";
    if (/[\u0400-\u04ff]/.test(text)) return "ru";
    if (/[\u0e00-\u0e7f]/.test(text)) return "th";
    if (/[\u0600-\u06ff]/.test(text)) return "ar";
    if (/[\u0900-\u097f]/.test(text)) return "hi";
    if (/[\u0370-\u03ff]/.test(text)) return "el";
    return null;
  }

  async function detectLang(text) {
    const byScript = SCRIPT_LANG(text);
    if (byScript) return byScript;
    const d = await getDetector();
    if (d) {
      try {
        const [top] = await d.detect(text);
        if (top && top.confidence >= 0.5 && top.detectedLanguage && top.detectedLanguage !== "und") {
          return top.detectedLanguage.split("-")[0] === "zh" ? top.detectedLanguage : top.detectedLanguage.split("-")[0];
        }
      } catch {}
    }
    return "en"; // short Latin-script chat on CHZZK is mostly English
  }

  const askedPairs = new Set();

  // Subtitles go first; chat is translated one message at a time behind them.
  let chatChain = Promise.resolve();

  async function translateTexts(texts, target, source = "ko") {
    if (source !== "auto") {
      const tr = await getTranslator(target, source);
      const out = [];
      for (const t of texts) {
        try {
          out.push((await tr.translate(t)).trim());
        } catch {
          out.push(null);
        }
      }
      return out;
    }
    // Each message may be in a different language.
    if (!("Translator" in self)) throw new Error("NO_TRANSLATOR_API");
    const out = [];
    for (const t of texts) {
      const src = await detectLang(t);
      if (src === target || src === "ko") {
        out.push(null);
        continue;
      }
      try {
        const tr = await getTranslator(target, src);
        out.push((await tr.translate(t)).trim());
      } catch (e) {
        if (e.message === "NEEDS_CLICK" && !askedPairs.has(src)) {
          askedPairs.add(src);
          emit("needsPair", { source: src, target });
        }
        out.push(null);
      }
    }
    return out;
  }

  function handleTranslate(msg) {
    const run = () => translateTexts(msg.texts, msg.target, msg.source || "ko");
    let job;
    if (msg.priority) {
      job = run();
    } else {
      job = chatChain.then(run);
      chatChain = job.catch(() => {});
    }
    job.then(
      (out) => emit("translated", { id: msg.id, out }),
      (err) => emit("translated", { id: msg.id, error: err?.message || "TRANSLATE_FAILED" })
    );
  }

  // ---------------------------------------------------------------------------

  window.addEventListener("ctr:to-engine", (e) => {
    let msg;
    try {
      msg = JSON.parse(e.detail);
    } catch {
      return;
    }
    switch (msg.cmd) {
      case "startSpeech":
        startSpeech(msg.lang);
        break;
      case "stopSpeech":
        stopSpeech();
        break;
      case "prepareTranslator":
        prepare(msg.target, msg.source || "ko");
        if (msg.source && msg.source !== "ko") askedPairs.delete(msg.source);
        getDetector(); // also fetch the language detector while we have the click
        break;
      case "translate":
        handleTranslate(msg);
        break;
      case "hello":
        emit("hello", { hasSpeech: !!SR, hasTranslator: "Translator" in self, running });
        break;
    }
  });

  emit("hello", { hasSpeech: !!SR, hasTranslator: "Translator" in self, running });
})();
