# ORBIT AI

A JARVIS-inspired personal AI assistant for Android, in one app: chat and voice (Bangla and English), online and offline AI, file manager, phone intelligence, automation, productivity tools and personal memory. Built with Kotlin, Jetpack Compose and Material 3. Built by GitHub Actions, so you do **not** need Android Studio or a PC.

> **Honest status (v0.1.0):** all source files are written, and the command parser and backend proxy were tested with real runs. The Android code itself has **not been compiled yet**, because it was written in a workspace with no Android SDK. The first GitHub Actions build is its first real compile. If it fails, open the failed run, copy the red error lines and send them to your assistant to fix. Expect one or two rounds.

## What works and what does not (yet)

| Feature | Status |
|---|---|
| Home screen, animated orb, circular visualizer, dark/light/system theme | Written |
| Chat: history, search, delete, copy, regenerate, Markdown + code copy, image and PDF/text attachments | Written |
| Online AI via your backend proxy (Gemini, Groq, Claude) | Written; needs your proxy (below) |
| Offline AI with a local model (LiteRT-LM), download/import, RAM check | Written; needs a model file you provide |
| AUTO / ONLINE / OFFLINE routing with fallback | Written |
| Voice in/out, Bangla + English, speed, pitch, voice choice, volume, continuous mode | Written (uses the phone's speech services) |
| Wake word "ORBIT" (optional, visible notification, Stop button) | Written; battery-hungry, see limits |
| Files: media scan, folder access, search, filter, sort, duplicates, large files, storage analysis, rename, copy, move with preview + confirm, undo | Written |
| Phone Intelligence: storage, battery, device info, apps, usage stats, cleanup tips | Written |
| Automation: open apps, flashlight, timers, reminders, settings shortcuts, daily routines, custom commands, history | Written |
| Productivity: Study, PDF (offline text extraction + offline search), Document, Code, Image | Written |
| Web search with sources | **Not implemented** (marked in the app) |
| Streaming responses | **Not implemented** (replies arrive whole) |
| Undo after closing the app | **Not implemented** (undo lasts for the session) |
| App icon | Simple vector icon, replace for the store |

## Real limits you should know
- **Android blocks a lot.** ORBIT cannot see other apps' private files, cannot read WhatsApp/Facebook content, and cannot toggle Wi-Fi/Bluetooth/location itself; it opens the right settings page instead. PDFs, documents, APKs and archives are visible only in folders you add with "Add folder".
- **Wake word** uses Android's speech service in a loop. It is not low-power, can use noticeable battery, may need internet, and Android will not let the app pop open from the background; you get a notification to tap.
- **Offline AI**: a 12 GB phone does not guarantee every model runs. ORBIT checks free RAM first and tells you if a model is too big. Choose a small (1 to 2 GB) `.litertlm` model. Bangla quality is weak offline.
- **Deep male voice** depends on the voices installed on your phone. ORBIT lowers pitch and lets you pick a voice.
- **No secrets in the APK.** The AI keys live on your backend proxy.

## Step 1: put the project on GitHub (phone is fine)
1. Create a free account at github.com and a new **private** repository named `orbit-ai`.
2. Upload every file and folder from this project, keeping the folder structure (on a phone browser use "Add file > Upload files" in several batches, or use GitHub's mobile app; a computer makes this easier).
3. The workflow in `.github/workflows/android-build.yml` starts automatically.

## Step 2: get the APK
1. Repository > **Actions** tab > open the latest **Android Build** run.
2. When it shows a green tick, scroll to **Artifacts** and download **orbit-ai-debug-apk** (a zip containing `app-debug.apk`).
3. Unzip, open the APK on your phone, allow "install unknown apps" for your browser/files app, and install.
4. If the run is red: open it, open the failed step, copy the error lines, and send them to your assistant.

## Step 3: set up online AI (free options)
The app talks to a small proxy you own. A ready one is in `backend/` for Cloudflare Workers (free plan).
1. Get a free key: Gemini at aistudio.google.com (or Groq at console.groq.com, or Claude at console.anthropic.com, paid).
2. On a computer with Node.js: `cd backend`, then
   `npx wrangler login`, `npx wrangler deploy`,
   `npx wrangler secret put GEMINI_API_KEY` (paste the key),
   `npx wrangler secret put ORBIT_TOKEN` (make up a long random password).
   (Add `GROQ_API_KEY` / `ANTHROPIC_API_KEY` the same way if you want those providers.)
3. Wrangler prints your URL, like `https://orbit-ai-proxy.yourname.workers.dev`.
4. In ORBIT: **Settings > AI engine** > choose the provider, paste the URL and the token, tap **Save online settings**.
Model names default to `gemini-2.5-flash`, `openai/gpt-oss-120b` and `claude-haiku-5-5`; override with the `GEMINI_MODEL`, `GROQ_MODEL`, `ANTHROPIC_MODEL` variables if a name changes. Free tiers have limits and may use your prompts to improve their products: do not send private data.
No computer? Cloudflare's dashboard can also create a Worker by pasting `backend/worker.js` and adding the secrets in its settings.

## Step 4: set up the offline model (optional)
1. Get a small `.litertlm` model file (about 1 to 2 GB) from a source you trust, for example Google's LiteRT community models on Hugging Face. Check the licence.
2. In ORBIT: **Settings > Offline model** > **Import from phone storage** (or paste an https:// download URL; gated models need a Hugging Face token).
3. Tap **Use and load**. If memory is short you will see exactly why.
4. Set the mode to **Offline** (or leave **Auto**).

## Release build and signing (for Play Store later)
1. Create a keystore once (on any computer): `keytool -genkeypair -v -keystore orbit.jks -alias orbit -keyalg RSA -keysize 2048 -validity 10000`
2. Convert to text: `base64 -w0 orbit.jks` (Mac: `base64 -i orbit.jks`).
3. GitHub repository > Settings > Secrets and variables > Actions, add: `ORBIT_KEYSTORE_BASE64`, `ORBIT_KEYSTORE_PASSWORD`, `ORBIT_KEY_ALIAS`, `ORBIT_KEY_PASSWORD`.
4. Re-run the workflow: it also uploads a signed `orbit-ai-release` artifact (APK and AAB). **Back up the keystore safely; if you lose it you cannot update the app.**
5. Before publishing: host `PRIVACY_POLICY.md` (filled in) at a public URL, replace the icon, review `STORE_LISTING.md`, and complete Play Console's Data safety and permission declarations. Google Play may require a developer account, testing period and review; approval is never guaranteed.

## Testing checklist (on your Vivo phone)
- Launch, switch themes in Settings.
- Type "hello" with online set up; then airplane mode with a model installed.
- Tap mic, speak English, then Bangla (set language in Settings).
- Say "open youtube", "turn on the flashlight", "start a study timer for 45 minutes", "remind me in 2 minutes to test".
- Files: allow media, add Downloads folder, run "Organise downloads", read the preview, confirm, then Undo.
- Phone: open the Phone screen, grant Usage access if you want app stats.
- Enable the wake word, lock/unlock, check the notification and the Stop button.
- Vivo/Funtouch tip: if reminders or the wake word stop in the background, allow ORBIT to run in background / disable battery optimisation for it in system settings.

## Project layout
```
app/src/main/java/com/orbit/ai/
  ai/            engine interface, online engine, offline engine (LiteRT-LM), model manager, router
  voice/         speech recognition + TTS, wake-word foreground service
  files/         scanner (MediaStore + folder picker), file operations, duplicates
  intelligence/  storage, battery, device, apps, usage stats
  automation/    command parser, action executor, reminder worker
  productivity/  tool definitions, PDF/image helpers
  memory/        approved-memory repository
  database/      Room entities and DAOs
  settings/      DataStore settings, encrypted token store
  viewmodel/     MVVM view models
  ui/            theme, orb, components, screens
backend/         Cloudflare Worker proxy (keeps AI keys off the phone)
.github/workflows/android-build.yml
```
Run unit tests in CI (`gradle :app:testDebugUnitTest`): `CommandParserTest` covers the English and Bangla commands.
