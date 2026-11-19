# Google Play listing draft (edit before use)

**App name:** ORBIT AI
**Short description (80 chars):** Your personal AI assistant: voice, files, phone insights and automation.

**Full description:**
ORBIT AI is a personal assistant that lives in one app. Talk or type in English or Bangla, get answers from online AI or from a private on-device model, and let ORBIT handle everyday phone tasks.

• Chat and voice: natural conversation with spoken replies, push-to-talk, and an optional "ORBIT" wake word.
• Online or offline: choose Auto, Online or Offline. Offline mode runs a model you install on your phone.
• Smart files: find photos, videos, PDFs and large files, spot duplicates, and organise folders you choose, always with a preview and your confirmation. ORBIT never deletes files.
• Phone intelligence: storage, battery, device details and app usage (with your permission).
• Automation: open apps, timers, reminders, daily routines and your own voice commands.
• Study and work tools: explain, summarise, translate, quiz, rewrite, code help, PDF questions, and image understanding.
• Memory you control: ORBIT remembers only what you approve, and you can review, edit or delete it.
• Privacy first: no ads, no analytics. Online AI goes through a server you configure.

ORBIT AI is not affiliated with Marvel or Disney. AI can make mistakes; verify important information.

**Category:** Productivity   **Contains ads:** No

## Data safety (draft; verify against your final build and proxy)
- Data collected by the app developer: none, if you do not operate the proxy. If you operate it, declare messages and attachments as "App activity / Messages" processed on your server.
- Data shared with third parties: message text, recent chat, optional memories and attachments go to the AI provider you choose when online AI answers.
- Data on device only: chat history, memories, settings.
- Encryption in transit: yes (HTTPS only). Encrypted at rest: proxy token only.
- Deletion: in-app controls; uninstall removes all.

## Permission declarations to prepare
- Microphone (RECORD_AUDIO) and FOREGROUND_SERVICE_MICROPHONE: voice input and optional wake word; include a video demo of the persistent notification and Stop button.
- Usage access (PACKAGE_USAGE_STATS): optional app usage report; declare in the sensitive-permissions form.
- READ_MEDIA_*: Play may require the photo picker or a core-functionality justification; file management is a core feature of this app.
- POST_NOTIFICATIONS: reminders and timers.

## Testing
Provide Google reviewers with a test proxy URL and token, or note that Offline mode needs a model file.
