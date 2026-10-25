# CHZZK Live Translator

**English | [한국어](README.ko.md) | [日本語](README.ja.md)**

A Chrome extension that turns Korean speech on [CHZZK](https://chzzk.naver.com) live streams into **live subtitles in your language**, and **translates the chat** as it scrolls by.

> **Disclaimer**
> - This is an unofficial, fan-made, non-commercial project for personal and fan use. It is not affiliated with, endorsed by, or sponsored by NAVER, CHZZK, Stellive or any streamer. All names and trademarks belong to their owners.
> - It does not download, record, save or re-upload any stream. It only listens to the audio and reads the chat of the page you are watching (short audio pieces go to the speech service you choose, see Privacy) and shows translations on your own screen.
> - Use it at your own risk. Translations can be wrong, and it is provided "as is" without warranty (see [LICENSE](LICENSE)).
> - If a rights holder, NAVER/CHZZK or a streamer has a problem with this project, please open an issue and I will change or remove it promptly.

*Built with the help of [Claude](https://claude.ai) (Anthropic), an AI assistant.*

## Features

- Live subtitles over the video player, in English, Japanese, Chinese, Spanish and more
- Chat translation under each message (nicknames are left alone; copy-paste spam is translated once and shown as "×N")
- Two modes:

| Mode | Speech recognition | Translation | API keys | Limits |
|---|---|---|---|---|
| **Free** (default) | Chrome's built-in speech recognition | Chrome's built-in translator | None | None |
| **High accuracy** | Whisper large-v3 (Groq, free tier) | NVIDIA AI (optional) or Chrome's translator | Your own free Groq key; NVIDIA key optional | About 8 hours of Whisper per day, then Free mode takes over automatically |

Start with **Free**. If the subtitles miss too much (fast talking, game sound, unclear pronunciation), switch to **High accuracy**.

## Requirements

- Desktop Google Chrome, version 138 or newer (Free mode needs Chrome's built-in translator)
- Does not work on phones

## Install

This is a normal Chrome extension that you load by hand (no Chrome Web Store needed). It takes about a minute.

1. **Download**: open the [latest release](https://github.com/pst10101/chzzk-live-translator/releases/latest) and click **`chzzk-live-translator-v1.0.0.zip`** under *Assets*.
2. **Unzip**: right-click the zip → **Extract All** (Windows) or double-click it (Mac). Put the folder somewhere permanent, like `Documents`. Chrome reads the extension from this folder every time, so **do not delete or move it** afterwards.
3. Open **`chrome://extensions`** in Chrome (type it in the address bar).
4. Turn on **Developer mode** (switch in the top right).
5. Click **Load unpacked** (top left) and select the unzipped folder, the one that directly contains `manifest.json`. If you see only one more folder inside, open it and select that one.
6. **CHZZK Live Translator** now appears in the list. Click the puzzle icon in Chrome's toolbar and **pin** it so the icon is always visible.

> If a CHZZK tab was already open, refresh it once.

**Update later**: download the new zip, replace the old folder's files with the new ones, then press the circular arrow on the extension's card in `chrome://extensions`.

**Trouble?** "Manifest file is missing or unreadable" means you selected the wrong folder (go one level deeper or up). If *Load unpacked* is missing, Developer mode is off.

## Use (Free mode)

1. Open a CHZZK live stream and make sure it is playing with sound.
2. Click the extension icon → keep **Free** selected → choose a language in **Translate into**.
3. Click **Start live subtitles**.
4. The first time, Chrome may need to download its translator. If you see *"Click here to download the free … translator"* on the player, click it once.

While someone is talking, the Korean text appears in grey; when the sentence ends it turns into the translation. Chat is translated automatically (turn off **Translate chat** to stop it).

## Getting API keys (High accuracy mode)

Both keys are free and need no credit card. **Groq is the one that matters** — it is what makes recognition much more accurate. NVIDIA is optional.

### Groq key (speech recognition) — required for High accuracy

1. Go to **https://console.groq.com** and sign up with email, Google or GitHub.
2. In the left menu, open **API Keys**.
3. Click **Create API Key**, give it any name (for example `chzzk-translator`), and confirm.
4. **Copy the key right away** — it starts with `gsk_` and is shown only once. If you lose it, delete it and create a new one.

Free tier limits: 2,000 requests and 8 hours of audio per day, per account. The **Whisper timing** setting in the popup decides how you spend them:

| Whisper timing | Subtitle delay | Whisper per day |
|---|---|---|
| **All day** (default) | about 15 seconds | about 8 hours |
| **Fast** | about 5–10 seconds | about 3–5 hours |

When the free limit runs out, the extension **automatically switches to Chrome's free speech recognition** (Free mode) and switches back to Whisper when the limit resets. Subtitles never stop.

### NVIDIA key (AI translation) — optional

Without this key, Chrome's built-in translator is used. With it, translations handle slang and memes more naturally.

1. Go to **https://build.nvidia.com** and sign in, or create an NVIDIA account.
2. Open any model page (for example *llama-3.3-70b-instruct*) and click **Get API Key** → **Generate Key**.
3. The first time, NVIDIA asks you to **verify your phone number by SMS**. Some countries are not in the list — if yours is missing, just skip NVIDIA.
4. Copy the key — it starts with `nvapi-`.

Free tier limit: about 40 requests per minute. Subtitles are translated first; in very fast chats the newest messages are translated first.

### Put the keys in the extension

1. Click the extension icon → select **High accuracy**.
2. Open **API keys & models** and paste the Groq key (and the NVIDIA key if you have one).
3. Click **Test keys** — you should see ✓.
4. Click **Start live subtitles**.

### Keep your keys safe

- Keys are saved **only in your own Chrome** (`chrome.storage.local`). They are never in this project's code.
- **Never** put your key in the code, a commit, an issue, or a screenshot. Anyone who has it can use up your free limit.
- If a key leaks, delete it in the Groq console / NVIDIA site and create a new one.

## Tips

- **Names & terms** (under *API keys & models*): write streamer names and common words, one per line. Optionally add how to translate them:

  ```
  멤버이름 = Member Name
  자주 쓰는 말 = how to translate it
  ```

  This helps Whisper spell names correctly and keeps translations consistent.
- **Move the subtitles**: drag them anywhere on the video; the spot is remembered. In the popup, **Subtitle position → move** shows a sample line to drag while nobody is talking, and **reset** puts them back at the bottom.
- **Subtitle size** and **Show Korean under subtitles** are in the popup.

## FAQ

**Why does everyone need their own Groq key?**
Each Groq account has its own free limit. If you use your own key, your viewing uses only your limit — the project needs no server and costs nothing for anyone. A key shared by many people would run out within minutes.

**Is it really free?**
Yes. Free mode needs nothing. High accuracy uses Groq's and NVIDIA's free tiers; you never enter payment details in this extension.

**Can I use it on my phone?**
No. It is a desktop Chrome extension.

## Privacy

- Free mode: the stream's audio goes to Chrome's speech recognition service; translation happens on your computer.
- High accuracy mode: the stream's audio goes to Groq; text goes to NVIDIA if you added an NVIDIA key.
- Nothing is sent anywhere else, and the extension collects no data.

## Troubleshooting

| Problem | Fix |
|---|---|
| "Reload the CHZZK page once" | Refresh the CHZZK tab and start again |
| No subtitles in Free mode | Make sure the stream is playing; if the player is muted, raise the volume a little; update Chrome |
| "Click here to start free subtitles" | Click that message once |
| Only Korean, no translation | Free: click the translator download message, or pick another language. High accuracy: check the NVIDIA key or remove it to use Chrome's translator |
| Translation model not found | In *API keys & models*, click **load list** and pick another model |
| Chat is not translated | CHZZK may have changed its page layout — update `TEXT_SEL` in `content.js` |

## For developers

| File | What it does |
|---|---|
| `manifest.json` | Extension manifest (Manifest V3) |
| `popup.*` | Settings popup |
| `engine-main.js` | Free engine (runs in the page): video audio → Chrome speech recognition, Chrome translator |
| `content.*` | Subtitle overlay, chat translation, bridge to the engine |
| `background.js` | High accuracy: start/stop capture, NVIDIA translation, usage counter |
| `offscreen.*`, `pcm-worklet.js` | High accuracy: tab audio capture, cut at pauses, send to Groq |
| `audio-utils.js` | Resampling, speech chunking, WAV encoding, hallucination filter |
| `translate-utils.js` | Translation prompts and response parsing |

## License

[MIT](LICENSE)
