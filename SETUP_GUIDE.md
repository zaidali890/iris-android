# IRIS for Android — Setup & Demo Guide

A native Kotlin app that runs entirely on your phone and controls it directly — no PC required.
Grok is the default brain, with Anthropic/OpenAI/Ollama as swappable alternatives, all free to
use with your own API key (Grok/Anthropic/OpenAI have free trial credit; Ollama over your home
Wi-Fi is fully free). Voice is free by default (Android's built-in engine), with Fish Audio as
an optional nicer-voice upgrade.

## 1. Get the code building

You need **Android Studio** (free) on a PC/Mac at least once — that's unavoidable for a real
native app; there's no "scan a QR code" shortcut like the Expo companion app had.

**Option A — Android Studio (recommended for your own build/debug)**
1. Install Android Studio (Hedgehog or newer).
2. File → Open → select the `IRIS-Android` folder.
3. Let it sync (it will auto-download the Gradle wrapper jar the first time — that's normal,
   this repo doesn't check in that binary file).
4. Plug in your phone (enable Developer Options → USB debugging) or use an emulator.
5. Click Run.

**Option B — GitHub Actions (no Android Studio needed at all)**
1. Push this folder to your own GitHub repo.
2. The included `.github/workflows/build-apk.yml` builds a debug APK automatically on every push
   — completely free (GitHub Actions' free tier covers this easily for a personal repo).
3. Go to the Actions tab → the latest run → download the `iris-debug-apk` artifact → install it
   on your phone (you'll need to allow "install from unknown sources" once).

If you want, tell me and I can help set up the GitHub repo and push this for you directly via a
GitHub connector, so Option B happens without you touching git.

## 2. First launch — permissions

On first open, IRIS walks you through every permission, explaining what each unlocks:

- **Microphone** — required. The whole assistant service can't start without it.
- **Phone & contacts** — for calling and answering calls.
- **Notifications (post)** — for IRIS's own status/reminder notifications.
- **Read & reply to notifications** — a system settings screen; find IRIS in the list and enable it.
- **Do Not Disturb control** — a system settings screen, one-time toggle.
- **Screen automation (optional)** — only needed for auto-sending WhatsApp messages. Shows a
  strong Android warning — that's normal for this permission category, not a bug.
- **Screenshot capability (optional)** — one-time consent, reused after that.

## 3. Set your brain (Grok by default)

Settings → Brain (LLM) → Grok is selected by default → paste your xAI API key
(https://x.ai — get one from the console). Anthropic/OpenAI/Ollama are there too if you'd rather
use those.

## 4. Try it

- "Open WhatsApp" / "Turn on the flashlight" / "Set volume to 40%"
- "Turn on Do Not Disturb" (after granting policy access)
- "What's my battery level?"
- "Read my recent notifications"
- "Reply to [contact]'s message saying I'll call them back" (uses the notification's own quick-reply)
- "Send a WhatsApp message to [contact] saying I'm on my way" — opens WhatsApp with the message
  ready; taps Send automatically if you've enabled Screen Automation, otherwise you tap it
- "Call [contact]"
- "Remind me to take a break in 20 minutes"
- "Remember that my landlord's name is [name]" → later: "What's my landlord's name?"

## 5. What's genuinely automatic vs. what still needs a tap

**Fully automatic, no caveats:**
Opening apps, flashlight, volume, screenshots, reminders, memory, device status, reading
notifications aloud, replying to notifications (via Android's real Quick Reply API), placing
calls, auto-answering calls (if you enable it in Settings).

**Works, but Android deliberately keeps a human in the loop:**
WiFi/Bluetooth/Airplane mode — IRIS opens the exact right panel, you tap once. This is an Android
privacy restriction since version 10, not something any app can route around.

**Works, but fragile — WhatsApp send automation:**
The message is pre-filled using WhatsApp's own official "click to chat" link (a real, documented
feature) — that part is solid. Only the final tap-to-send uses the opt-in Accessibility Service,
which can break if WhatsApp changes their button's label/position in a future update. If it stops
working, that's the first thing to check.

## 6. Project layout

```
app/src/main/kotlin/com/iris/android/
  IrisApplication.kt          notification channel setup
  MainActivity.kt             binds the service, hosts Command/Settings tabs
  agent/                      AgentLoop, LlmClient (Grok/Anthropic/OpenAI/Ollama), tool defs
  tools/                      real tool implementations (apps, toggles, files, calls, WhatsApp…)
  services/                   foreground service, notification listener, accessibility, screenshot
  data/                       Room database (memory, notifications) + DataStore settings
  ui/                         Compose screens: onboarding, command console, settings
```

## 7. Known limitations to mention in a demo

- Built and reviewed without running Gradle/Android Studio in the environment this was written in
  (no network access there) — do a real build-and-run before presenting live.
- The WhatsApp send flow depends on Accessibility Service finding a button labeled "Send" — if
  WhatsApp is in a language where that label differs, it needs a small tweak in
  `IrisAccessibilityService.kt`.
- Fish Audio integration is stubbed as a settings option but the actual API call isn't wired in
  yet (device TTS is used regardless of the setting) — happy to wire it up if you share their
  current API docs, since endpoints for TTS providers change often.
- `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` may prompt Android 12+ users for one more manual grant
  the first time a reminder is set; the code falls back to an inexact alarm if it's denied.
