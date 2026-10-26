// Chat helpers shared by content.js (Chrome extension) and the Android app's chat script.
// Classic script (no modules): defines globalThis.CtrChat. Pure functions, unit-tested in Node.
(() => {
  if (globalThis.CtrChat) return;

  const HANGUL = /[ㄱ-ㆎ가-힣]/;
  const LAUGH = /^[ㅋㅎ\s.!?~]+$/;
  const CRY = /^[ㅠㅜ\s.!?~]+$/;
  // One "word" of laughter in other languages: lol, lmao, www, 草, 哈哈, xD, hahaha, jajaja, kkkk, 555 …
  // (checked word by word — a single regex over the whole line could backtrack badly on long "wwww…")
  const LAUGH_WORD = /^(?:l+o+l+|lmf?a+o+|rofl|w{2,}|ｗ+|草+|笑+|哈{2,}|x+d+|(?:ha){2,}h?|(?:he){2,}|(?:ja){2,}|k{3,}|5{3,})$/i;
  const URL = /https?:\/\/\S+/gi;

  /** Korean chat line that needs no model: laughter / crying. */
  function localFromKorean(text, english) {
    if (LAUGH.test(text)) return english ? "lol" : "ㅋㅋ";
    if (CRY.test(text)) return "T_T";
    return null;
  }

  /** Non-Korean chat line that needs no model (laughter). */
  function localToKorean(text) {
    const words = text.split(/[\s!.~?…,]+/).filter(Boolean);
    if (!words.length || words.length > 8) return null;
    return words.every((w) => LAUGH_WORD.test(w)) ? "ㅋㅋ" : null;
  }

  /**
   * A chat line in another language that is worth translating into Korean:
   * no Hangul, at least two letters, and not just "gg" / "ok" / a link.
   */
  function isForeign(text) {
    if (!text || HANGUL.test(text)) return false;
    const t = text.replace(URL, " ").trim();
    const letters = t.match(/\p{L}/gu) || [];
    if (letters.length < 2) return false;
    if (/^[\x00-\x7f]*$/.test(t)) {
      const latin = t.replace(/[^a-z]/gi, "");
      if (latin.length <= 2) return false; // gg, ok, w, xd
      if (/^(?:gg+|ggwp|wp|ok+|f+|o7|pog+|poggers|kekw|lul|omegalul)$/i.test(t.replace(/[\s!.?~]+/g, ""))) return false;
    }
    return true;
  }

  /** Language by script (no model needed). Returns a BCP-47 code or null for Latin etc. */
  function scriptLang(text) {
    if (/[぀-ヿㇰ-ㇿｦ-ﾟ]/.test(text)) return "ja";
    if (/[一-鿿㐀-䶿]/.test(text)) return "zh";
    if (/[Ѐ-ӿ]/.test(text)) return "ru";
    if (/[฀-๿]/.test(text)) return "th";
    if (/[؀-ۿ]/.test(text)) return "ar";
    if (/[ऀ-ॿ]/.test(text)) return "hi";
    if (/[Ͱ-Ͽ]/.test(text)) return "el";
    return null;
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

  /**
   * Decides what to do with one chat line.
   * Returns null (leave it), { local, unit, count } (show this, no model), or { dir, unit, count }
   * where dir is "out" (Korean → your language) or "ko" (other language → Korean).
   */
  function classify(text, { fromKorean, toKorean, english }) {
    if (!text) return null;
    const korean = HANGUL.test(text);
    if (korean) {
      if (!fromKorean) return null;
      const l = localFromKorean(text, english);
      if (l) return l === text ? null : { local: l, unit: text, count: 1 };
      const { unit, count } = collapseRepeats(text);
      const ul = count > 1 ? localFromKorean(unit, english) : null;
      if (ul) return { local: ul, unit, count };
      return { dir: "out", unit, count };
    }
    if (!toKorean) return null;
    const l = localToKorean(text);
    if (l) return { local: l, unit: text, count: 1 };
    if (!isForeign(text)) return null;
    const { unit, count } = collapseRepeats(text);
    const ul = count > 1 ? localToKorean(unit) : null;
    if (ul) return { local: ul, unit, count };
    return { dir: "ko", unit, count };
  }

  globalThis.CtrChat = { HANGUL, classify, isForeign, localToKorean, localFromKorean, scriptLang, collapseRepeats };
})();
