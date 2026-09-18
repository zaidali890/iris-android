# IRIS/Leeza Android — Project Handoff & Status

**Read this entire file before touching any code.** It exists so any AI (or a fresh session with
the same AI) can continue this project with zero lost context. It is kept up to date at the end of
every work session — if you are an AI picking this up, your first job after reading it is to keep
updating it as you go.

## What this project actually is

A native Android (Kotlin, Jetpack Compose) app that acts as a voice-controlled phone assistant,
persona-named **"Leeza"**, built for a specific user (this is not a generic product — decisions
below are tuned to their actual phone, language, and habits). It runs as a persistent foreground
service, uses an LLM (Grok/Groq/Anthropic/OpenAI, user's choice) as the "brain" with real
tool-calling to control the phone, and has a fully offline wake-word system (say "Leeza", it
responds and listens for a command).

## The user's actual requirements (do not silently drop these)

- **Wants the assistant to feel like a warm, encouraging, caring friend/assistant** — checks in,
  motivates, shows personality. **Explicitly declined by the AI so far and should stay declined**:
  a romantic-partner/"girlfriend" persona. The user asked for this once; the AI (Claude) explained
  why it wouldn't build that framing specifically (fostering emotional/romantic dependency via a
  persistent always-on assistant) and offered warm-but-platonic instead. The user accepted that
  redirection. Do not revisit this unless the user explicitly raises it again.
- **Default language: Urdu.** Switch to English automatically when the user speaks/writes English,
  back to Urdu otherwise. This applies to LLM replies AND speech recognition language.
- **Wake word: "Leeza"** (renamed from a placeholder "Iris" persona early in the project).
- Wants deep WhatsApp integration: reading message notifications aloud (with permission asked
  first, never auto-read), replying by voice, sending new messages, and now also announcing and
  handling incoming WhatsApp calls (accept/decline by voice).
- Wants incoming phone call announcing + accept/decline by voice, including "decline and tell them
  I'm busy" sending an automatic WhatsApp message to the caller.
- Cares about accuracy over feature-count at this point — has explicitly asked to slow down, list
  bugs, fix them properly, one root cause at a time, rather than keep guessing.
- Device context: an Android phone with **no Google apps/Chrome/Play-Services-heavy setup** —
  likely a budget/regional device or custom ROM. This matters a lot (see "Device-specific gotchas"
  below) — several bugs were caused by assuming Google's speech/ASR stack was present when it
  wasn't.
- User communicates in Roman Urdu/English mix; is a competent but non-developer user operating
  entirely through **Termux on the phone itself** (no PC, no Android Studio). All instructions given
  to them must be copy-pasteable Termux shell commands.

## Full architecture

```
app/src/main/kotlin/com/iris/android/
  IrisApplication.kt          Notification channel setup
  MainActivity.kt             Binds foreground service, hosts Command/Settings tabs (Compose)

  agent/
    AgentTypes.kt             AgentEvent sealed class, CanonicalMessage, ToolDef data classes
    AgentLoop.kt               The tool-calling loop + SYSTEM PROMPT (persona, language, all
                               behavioral rules live here — read this file to know current AI behavior)
    LlmClient.kt               Grok/Groq/OpenAI (shared OpenAI-compatible path) + Anthropic + Ollama
                               adapters, normalized into one ChatResult shape
    ToolDefs.kt                Every tool the LLM can call — MUST stay in 1:1 sync with
                               ToolExecutorImpl's `when(name)` dispatch (see note below)
    ToolExecutor.kt            PermissionBroker + ToolExecutor interfaces

  tools/
    ToolExecutorImpl.kt        Real implementations for every tool (apps, files, calls, WhatsApp...)
    ContactResolver.kt         Ranked matching against the user's curated contact allow-list, with
                               fuzzy-suggestion fallback when no confident match
    ReminderScheduler.kt       AlarmManager-based reminders
    ScreenshotManager.kt       MediaProjection-based screenshot (needs one-time consent)
    OemBackgroundSettings.kt   Best-effort launcher for manufacturer autostart/battery settings

  services/
    IrisForegroundService.kt   THE core service — agent loop host, TTS (device + Fish Audio), wake
                               word orchestration, call handling, notification auto-speak. This is
                               the largest and most-edited file in the project; read it fully before
                               changing anything in it.
    IrisNotificationListenerService.kt   Captures all notifications; detects WhatsApp call vs
                               message via Notification.CATEGORY_CALL; caches RemoteInput reply
                               actions in-memory (NotificationActionCache)
    IrisAccessibilityService.kt   Narrow, opt-in automation — taps WhatsApp's Send button and
                               (new) Accept/Decline call buttons. Best-effort, breaks if WhatsApp
                               changes button labels.
    IrisCallScreeningService.kt   Allows all calls through unchanged; exists ONLY to capture the
                               incoming caller's number (TelephonyCallback doesn't expose this on
                               modern Android) — see CallerInfo/lastIncomingCall
    ScreenshotService.kt       Foreground service that performs the actual MediaProjection capture

  voice/
    VoskWakeWordEngine.kt      Continuous offline wake-word detection via Vosk (see "Wake word
                               history" below for why this replaced two earlier approaches)
    VoskModelManager.kt        Downloads/caches the Vosk speech model (~40MB, one-time)

  data/
    SettingsRepository.kt      DataStore-backed settings — THE single source of truth for every
                               user-configurable value. Read this to see every current setting.
    AppDatabase.kt             Room DB (version 3), fallbackToDestructiveMigration() — safe to bump
                               version and add fields without writing migrations, given this is a
                               personal single-user app under active development
    Memory.kt, Notifications.kt, Contacts.kt   Entities/DAOs

  ui/
    PermissionsScreen.kt       Onboarding checklist — every permission explained, with live status
    SettingsScreen.kt          All settings screens (LLM, Voice, Notifications, Messaging Contacts,
                               Safety, Persona)
    CommandScreen.kt, Theme.kt Main chat UI
```

## Everything that works today (verified by the user on-device)

- Grok/Groq/Anthropic/OpenAI/Ollama LLM backends, switchable in Settings
- Full tool-calling: open apps, flashlight, volume, screenshots, file read/write, reminders,
  memory (remember/recall), device status, DND toggle, quick-toggle panels
- WhatsApp send (click-to-chat URL + accessibility-tap-send), with a curated contact allow-list
  (NOT the whole phone book — deliberate safety/accuracy design) and ranked + fuzzy-suggestion
  contact matching
- Notification reading with permission-ask-first flow, quick-reply sending via Android's real
  RemoteInput API
- Multi-turn wake-word conversation (wake → ack → command → reply → follow-up... continues until
  genuine silence, not just one exchange)
- Fish Audio TTS (real API integration, free `s2.1-pro-free` model — **the model must go in the
  HTTP header, not the JSON body**, that was a real bug that caused false 402 errors)
- Urdu-default bilingual system prompt + Urdu-locale speech recognition
- Chained-burst command listening (see "Wake word history" — works around a device limitation)
- Incoming phone call announce + accept (solid) + reject (best-effort, user explicitly accepted
  this tradeoff — see below)
- GitHub Actions CI builds a debug APK on every push — no Android Studio needed, works entirely
  from Termux

## Currently open bugs (as of last session, NOT yet fixed)

1. **Ringtone volume during call-announce listening**: the phone's actual ringtone plays at normal
   volume while Leeza is trying to listen for "accept"/"decline", drowning out the user's voice.
   Fix planned: temporarily lower `AudioManager.STREAM_RING` while listening, restore after.
2. **Can't find Accept/Decline buttons for calls**: hypothesis (unconfirmed) is that call UIs
   render as a separate overlay window, and `AccessibilityService.rootInActiveWindow` only sees the
   top/active window, not overlay layers. Fix planned: iterate `windows` (plural,
   `AccessibilityService.windows` property) instead of just `rootInActiveWindow` when searching for
   call buttons specifically.
3. **WhatsApp calls still sometimes announced as regular messages**: the `Notification.CATEGORY_CALL`
   check has a blind spot on this user's WhatsApp version/build. **Blocked on user providing the
   exact notification title+subtext text for an incoming WhatsApp call** — needed before guessing
   at a second detection method (e.g., checking for "incoming call"/"video call" substrings in the
   notification text as a fallback signal).

## Dead ends already tried — do not repeat these

- **Picovoice Porcupine for wake-word**: fully implemented, then blocked because Picovoice now
  requires a company email to sign up (rejected the user's Gmail, and Google/GitHub OAuth signup
  too). Abandoned in favor of Vosk. Do not suggest going back to Porcupine unless the user says they
  now have a way to sign up.
- **Android SpeechRecognizer restart-loop for wake-word** (before Vosk): fundamentally the wrong
  tool — needs network on most devices, beeps on every restart cycle, and Android's recognizer is
  built for one-shot dictation, not continuous listening. Replaced entirely by Vosk.
- **Hidden/undocumented RecognizerIntent timing extras**
  (`SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` etc.) to extend listening duration: confirmed
  **twice**, in two different flows, that this device's speech engine does not honor them at all.
  Do not try these again — the working fix is the chained-burst approach in
  `startCommandListenAfterWake()`.
- **Muting the recognizer's start/stop beep** via `AudioManager.adjustStreamVolume`: tried once,
  coincided with (may or may not have caused) a full detection regression, reverted. Never
  conclusively proven to work OR to have caused the regression — if revisited, change ONLY this one
  thing and test in isolation, don't bundle with other changes.
- **`EXTRA_PREFER_OFFLINE` hint**: added speculatively while debugging network-related wake failures,
  removed again when switching to Vosk (irrelevant now, Vosk is offline by default).

## Device-specific gotchas (this specific phone)

- No Google apps/Chrome installed — assume the speech/ASR stack is NOT Google's, has unknown/
  limited feature support. When in doubt, prefer standards-based, no-special-extras approaches.
- Manufacturer battery/autostart management is aggressive (typical of budget/regional Android
  builds) — `OemBackgroundSettings.kt` tries known manufacturer autostart activity names
  best-effort; user has been advised to also manually check their phone's own battery settings.
- Internet connectivity has been observed to drop out during testing sessions (a `git push` failed
  once with DNS resolution failure) — don't assume network errors are code bugs without checking
  connectivity first.

## Development workflow (the user has no PC — everything is Termux + GitHub Actions)

The user has `gh` (GitHub CLI) authenticated and a repo at `github.com/zaidali890/iris-android`.
**Every code delivery from the AI should end with this exact update routine** (the user has this
memorized now, but restate it for a new AI/session):

```bash
cd ~/IRIS-Android
LATEST_ZIP=$(ls -t ~/storage/downloads/IRIS-Android*.zip | head -1)
rm -rf ~/iris-update-tmp
unzip -o "$LATEST_ZIP" -d ~/iris-update-tmp
SRC_DIR=$(find ~/iris-update-tmp -maxdepth 2 -type d -name "app" | head -1 | xargs dirname)
cp -rf "$SRC_DIR"/. ~/IRIS-Android/
rm -rf ~/iris-update-tmp
git add -A
git status
```
Then commit/push, `gh run watch` (retry after a `sleep 10` if it says "no in progress runs" — just
a timing race, not an error), then:
```bash
rm -f app-debug.apk
gh run download --name iris-debug-apk
cp app-debug.apk ~/storage/downloads/
```

**Sandbox note for the AI**: whatever tool/sandbox environment you're using to edit code may reset
its filesystem between sessions. Before editing, always check whether the project files still
exist; if not, the most recent zip in your outputs (or ask the user to re-upload their current
`IRIS-Android.zip`) is the source of truth — NOT your memory of what you last wrote, since a reset
means your working copy could be stale or gone entirely.

## Practical process notes for whoever continues this

- The user wants bugs fixed one root-cause-at-a-time now, not more features bundled in with fixes.
  Verify fixes are confirmed working before adding new scope on top.
- Always cross-check `ToolDefs.kt` definitions against `ToolExecutorImpl.kt`'s dispatch `when` block
  after any tool change — there have been real instances of a tool being defined (so the LLM could
  call it) with zero implementation behind it, which throws at runtime. A quick Python
  regex diff of the two files' tool names catches this immediately.
- Balance-check braces/parens across all `.kt` files after any edit round (simple `str.count('(')`
  vs `str.count(')')` etc.) — cheap and has caught real mistakes before a build was even attempted.
- Be upfront about anything implemented from training knowledge without live verification (e.g.
  exact SDK method signatures, current API pricing/limits) — this has been the right call multiple
  times (Fish Audio's model-goes-in-header requirement was only found because the user pasted
  Fish Audio's own docs after a wrong guess).
