package com.iris.android.services

import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.iris.android.IrisApplication
import com.iris.android.MainActivity
import com.iris.android.R
import com.iris.android.agent.AgentEvent
import com.iris.android.agent.AgentLoop
import com.iris.android.agent.LlmClient
import com.iris.android.agent.PermissionBroker
import com.iris.android.data.AppDatabase
import com.iris.android.data.IrisSettings
import com.iris.android.data.SettingsRepository
import com.iris.android.data.TtsProvider
import com.iris.android.tools.ToolExecutorImpl
import com.iris.android.voice.VoskWakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class IrisForegroundService : Service(), PermissionBroker {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 32)
    val permissionRequests = MutableSharedFlow<PermissionRequestUi>(extraBufferCapacity = 8)
    private val permissionResponses = Channel<Boolean>()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _isWakeListening = MutableStateFlow(false)
    val isWakeListening: StateFlow<Boolean> = _isWakeListening

    private lateinit var settingsRepository: SettingsRepository
    private var currentSettings: IrisSettings = IrisSettings()
    private lateinit var agentLoop: AgentLoop
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaPlayer: MediaPlayer? = null

    // Vosk runs independently of Android's SpeechRecognizer — genuinely continuous, offline,
    // no restart cycling or beeping. It's paused only while the single-shot command capture (which
    // still uses SpeechRecognizer, since that part was always working fine) needs the mic instead.
    private var wakeWordEngine: VoskWakeWordEngine? = null
    private var wakeLoopWanted = false
    private var awaitingCommandAfterWake = false

    private val wakeAckReplies = listOf(
        "Ji boss, main sun rahi hoon.",
        "Ji boss, bataiye kya karna hai.",
        "Main hazir hoon boss, bataiye.",
        "Ji, boliye — main sun rahi hoon."
    )

    data class PermissionRequestUi(val toolName: String, val summary: String, val detail: String?)

    inner class LocalBinder : Binder() {
        fun getService(): IrisForegroundService = this@IrisForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        val toolExecutor = ToolExecutorImpl(applicationContext, this)
        agentLoop = AgentLoop(LlmClient(), toolExecutor, AppDatabase.get(applicationContext).memoryDao())

        wakeWordEngine = VoskWakeWordEngine(
            context = applicationContext,
            onWakeWordDetected = { onWakeWordDetected() },
            onError = { message -> events.tryEmit(AgentEvent.Error("Wake word engine: $message")) }
        )

        scope.launch {
            settingsRepository.settingsFlow.collect { new ->
                val wakeSettingChanged = new.wakeWordEnabled != currentSettings.wakeWordEnabled
                currentSettings = new
                if (wakeSettingChanged) {
                    if (new.wakeWordEnabled) startWakeWordLoop() else stopWakeWordLoop()
                }
            }
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            } else {
                events.tryEmit(
                    AgentEvent.Error(
                        "The device's text-to-speech engine failed to start (status $status) — IRIS will " +
                            "still work by text, but won't be able to speak replies out loud. Check " +
                            "Settings → System → Languages & input → Text-to-speech on your phone."
                    )
                )
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }
            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                onUtteranceFinished(utteranceId)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                onUtteranceFinished(utteranceId)
            }
        })

        startForeground(1, buildNotification())
        maybeRegisterCallAutoAnswer()
        startNotificationAutoSpeakLoop()
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, IrisApplication.CHANNEL_ASSISTANT)
            .setContentTitle(getString(R.string.assistant_service_notification_title))
            .setContentText(getString(R.string.assistant_service_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .build()

    // -----------------------------------------------------------------
    // Agent
    // -----------------------------------------------------------------
    fun sendCommand(text: String) {
        scope.launch {
            agentLoop.runTurn(currentSettings, text) { event ->
                events.emit(event)
                when (event) {
                    is AgentEvent.Final -> {
                        if (currentSettings.speakAgentReplies) {
                            speak(event.text, utteranceId = UTTERANCE_AGENT_REPLY)
                            // Wake loop resumes in onUtteranceFinished once this actually finishes
                            // speaking — see the fix note there for why that matters.
                        } else {
                            resumeWakeLoopAfterTurn()
                        }
                    }
                    is AgentEvent.Error -> resumeWakeLoopAfterTurn()
                    else -> {}
                }
            }
        }
    }

    fun resetConversation() = agentLoop.reset()

    override suspend fun requestApproval(toolName: String, summary: String, detail: String?): Boolean {
        permissionRequests.emit(PermissionRequestUi(toolName, summary, detail))
        return permissionResponses.receive()
    }

    fun respondToPermission(approved: Boolean) {
        scope.launch { permissionResponses.send(approved) }
    }

    // -----------------------------------------------------------------
    // Voice — manual (tap-to-talk) single-shot listening
    // -----------------------------------------------------------------
    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        stopWakeWordLoop() // manual mic always takes priority over the background wake loop
        runSingleShotListen(
            onResult = {
                onResult(it)
                resumeWakeLoopIfEnabled()
            },
            onError = {
                onError(it)
                resumeWakeLoopIfEnabled()
            }
        )
    }

    private fun resumeWakeLoopIfEnabled() {
        if (currentSettings.wakeWordEnabled) {
            wakeLoopWanted = true
            startWakeWordLoop()
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        resumeWakeLoopIfEnabled()
    }

    private fun runSingleShotListen(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onError("Speech recognition isn't available on this device.")
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(simpleListener(onResult, onError))
        }
        speechRecognizer?.startListening(buildRecognizerIntent())
    }

    private fun buildRecognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // This used to always be Locale.getDefault() (the phone's system language), which is why
        // Urdu speech was being force-matched against English sound patterns — "kaun hai" becoming
        // "core" is exactly what happens when a recognizer is told to expect English. Configurable
        // in Settings → Voice, defaults to Urdu (Pakistan).
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentSettings.sttLanguage)
    }

    private fun simpleListener(onResult: (String) -> Unit, onError: (String) -> Unit) =
        object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (text != null) onResult(text) else onError("Didn't catch that.")
            }
            override fun onError(error: Int) {
                onError("Speech recognition error ($error).")
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

    // -----------------------------------------------------------------
    // Voice — background wake-word detection (Vosk, offline, no account needed)
    // -----------------------------------------------------------------
    fun startWakeWordLoop() {
        if (!currentSettings.wakeWordEnabled) return
        wakeLoopWanted = true
        if (awaitingCommandAfterWake) return // command capture / reply is using the mic or speaker right now
        scope.launch {
            wakeWordEngine?.start(currentSettings.wakeWord)
            _isWakeListening.value = wakeWordEngine?.isRunning() ?: false
        }
    }

    fun stopWakeWordLoop() {
        wakeLoopWanted = false
        awaitingCommandAfterWake = false
        _isWakeListening.value = false
        wakeWordEngine?.stop()
    }

    private fun onWakeWordDetected() {
        announceAndListen(wakeAckReplies.random(), injectIntoHistory = false)
    }

    /** Speaks a message, then immediately listens for the user's spoken answer once done — instead
     * of going back to background listening and waiting to be re-woken. This is what was missing
     * for the "Leeza asks a question, user replies, nothing happens" bug: previously only the wake
     * word itself triggered a listen; a proactive announcement (like a new-message alert) just
     * spoke and went silent, so the user's answer had nowhere to go until they said the wake word
     * again — by which point the conversational context was gone too.
     *
     * @param injectIntoHistory true for announcements that carry information the user might refer
     *   back to (e.g. "a message arrived from X") — false for filler acknowledgments like "yes boss"
     *   that don't need to clutter the conversation history.
     */
    private fun announceAndListen(text: String, injectIntoHistory: Boolean) {
        wakeWordEngine?.stop()
        _isWakeListening.value = false
        awaitingCommandAfterWake = true
        if (injectIntoHistory) agentLoop.injectAssistantMessage(text)
        // Shown in the Command tab regardless of whether TTS audio actually plays — lets you tell
        // "wake word wasn't detected" apart from "detected fine, but the speaker/TTS engine didn't
        // say it out loud" if something's still off.
        events.tryEmit(AgentEvent.Final(text))
        speak(text, utteranceId = UTTERANCE_WAKE_ACK)
    }

    private fun startCommandListenAfterWake() {
        if (!awaitingCommandAfterWake) return
        runSingleShotListen(
            onResult = { text ->
                // NOTE: awaitingCommandAfterWake stays true here on purpose — it now only clears
                // once the agent has actually finished responding (resumeWakeLoopAfterTurn), not
                // the moment speech-to-text captured the words. That's the fix for the wake loop
                // reopening and interrupting the command while it was still being carried out.
                sendCommand(text)
            },
            onError = {
                awaitingCommandAfterWake = false
                if (wakeLoopWanted) startWakeWordLoop()
            }
        )
    }

    /** Called once an agent turn is fully done — including having finished SPEAKING the reply, not
     * just having computed it — so the wake loop can't reopen the mic mid-response anymore. */
    private fun resumeWakeLoopAfterTurn() {
        awaitingCommandAfterWake = false
        if (wakeLoopWanted) startWakeWordLoop()
    }

    // -----------------------------------------------------------------
    // TTS — device engine by default, Fish Audio if configured and reachable
    // -----------------------------------------------------------------
    private fun speak(text: String, utteranceId: String = "iris-utterance") {
        val cleaned = cleanForSpeech(text)
        if (currentSettings.ttsProvider == TtsProvider.FISH_AUDIO && currentSettings.fishAudioApiKey.isNotBlank()) {
            scope.launch {
                val played = trySpeakWithFishAudio(cleaned, utteranceId)
                if (!played) speakWithDeviceEngine(cleaned, utteranceId)
            }
        } else {
            speakWithDeviceEngine(cleaned, utteranceId)
        }
    }

    /** Strips markdown symbols and emoji before speaking — otherwise TTS engines either read them
     * out literally ("asterisk", "hash") or garble on them entirely. */
    private fun cleanForSpeech(text: String): String {
        var cleaned = text.replace(Regex("[*_`#~]"), "")
        cleaned = cleaned.replace(EMOJI_REGEX, "")
        return cleaned.replace(Regex(" {2,}"), " ").trim()
    }

    private fun speakWithDeviceEngine(text: String, utteranceId: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** Fish Audio TTS via their REST API. Uses their free s2.1-pro-free model specifically — per
     * Fish Audio's current pricing, that model is free during their current free-access period
     * (subject to fair use), while their other/paid models require API credit and can return a 402
     * "insufficient credit" error if used without it. This deliberately never requests a paid
     * model, and falls back to the device voice (with a clear error surfaced) on any failure rather
     * than silently doing nothing. */
    private suspend fun trySpeakWithFishAudio(text: String, utteranceId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // Per Fish Audio's own quickstart docs: the model selector goes in the HTTP
                // header, NOT the JSON body — that mismatch was the actual cause of the 402
                // (the server never saw which model was requested, so it silently fell through to
                // a different/paid one and rejected the call for lacking credit).
                val json = JSONObject()
                    .put("text", text)
                    .put("reference_id", currentSettings.fishAudioVoiceId)
                    .put("format", "mp3")
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://api.fish.audio/v1/tts")
                    .header("Authorization", "Bearer ${currentSettings.fishAudioApiKey}")
                    .header("model", "s2.1-pro-free")
                    .post(body)
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val message = if (response.code == 402) {
                            "Fish Audio returned 402 (insufficient credit). IRIS only requests the free " +
                                "s2.1-pro-free model, so this usually means that free-tier access has " +
                                "changed or a fair-use limit was hit — falling back to the device voice."
                        } else {
                            "Fish Audio request failed (${response.code}) — falling back to the device voice."
                        }
                        withContext(Dispatchers.Main) { events.tryEmit(AgentEvent.Error(message)) }
                        return@withContext false
                    }
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            events.tryEmit(AgentEvent.Error("Fish Audio returned no audio — falling back to the device voice."))
                        }
                        return@withContext false
                    }
                    val file = File(cacheDir, "fish-audio-${System.currentTimeMillis()}.mp3")
                    file.writeBytes(bytes)
                    withContext(Dispatchers.Main) { playAudioFile(file, utteranceId) }
                    true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    events.tryEmit(AgentEvent.Error("Fish Audio error: ${e.message} — falling back to the device voice."))
                }
                false
            }
        }

    private fun playAudioFile(file: File, utteranceId: String) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    _isSpeaking.value = true
                    it.start()
                }
                setOnCompletionListener { player ->
                    _isSpeaking.value = false
                    player.release()
                    file.delete()
                    onUtteranceFinished(utteranceId)
                }
                setOnErrorListener { player, _, _ ->
                    _isSpeaking.value = false
                    player.release()
                    file.delete()
                    onUtteranceFinished(utteranceId)
                    true
                }
                prepareAsync()
            } catch (e: Exception) {
                events.tryEmit(AgentEvent.Error("Couldn't play Fish Audio response: ${e.message}"))
                onUtteranceFinished(utteranceId)
            }
        }
    }

    /** Single place both TTS backends (device engine and Fish Audio) funnel into once they finish
     * speaking — this is what makes the wake-loop-resume timing fix work regardless of which voice
     * backend is active. Both the wake-ack AND a normal agent reply lead to listening again — that's
     * what keeps a multi-turn exchange (wake → "read it?" → "haan" → reads message → "reply?" →
     * "haan, say I'm on my way") going without needing to re-say the wake word at every step. The
     * conversation only drops back to background wake-word listening once a listen attempt actually
     * comes back empty (see startCommandListenAfterWake's onError) — i.e. the user actually went quiet. */
    private fun onUtteranceFinished(utteranceId: String?) {
        when (utteranceId) {
            UTTERANCE_WAKE_ACK, UTTERANCE_AGENT_REPLY -> {
                if (awaitingCommandAfterWake) {
                    // Short delay + main-thread dispatch: SpeechRecognizer must start on the main
                    // thread, and giving the audio system a moment to release the speaker avoids a
                    // race where the mic tries to grab it too early.
                    scope.launch {
                        delay(250)
                        startCommandListenAfterWake()
                    }
                }
            }
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        mediaPlayer?.let {
            try { it.stop() } catch (e: Exception) { /* ignore */ }
            it.release()
        }
        mediaPlayer = null
        _isSpeaking.value = false
    }

    // -----------------------------------------------------------------
    // Call auto-answer (real API: ANSWER_PHONE_CALLS + TelecomManager)
    // -----------------------------------------------------------------
    private fun maybeRegisterCallAutoAnswer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return // TelephonyCallback needs API 31+
        val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        try {
            telephonyManager.registerTelephonyCallback(
                mainExecutor,
                object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        if (state == TelephonyManager.CALL_STATE_RINGING && currentSettings.autoAnswerCalls) {
                            scope.launch {
                                delay(1200) // let the ringing screen settle before answering
                                try {
                                    val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                                    telecomManager.acceptRingingCall()
                                } catch (e: SecurityException) {
                                    // ANSWER_PHONE_CALLS not granted — silently skip, user will see the call ring normally
                                }
                            }
                        }
                    }
                }
            )
        } catch (e: SecurityException) {
            // READ_PHONE_STATE not granted yet — auto-answer simply won't activate until it is
        }
    }

    // -----------------------------------------------------------------
    // Notification auto-speak — only for apps the user allow-listed, and truncated
    // -----------------------------------------------------------------
    private fun startNotificationAutoSpeakLoop() {
        scope.launch {
            val dao = AppDatabase.get(applicationContext).notificationDao()
            while (true) {
                delay(4000)
                if (!currentSettings.autoSpeakNotifications) continue
                // Don't interrupt an in-progress conversation turn (wake ack, command capture, or
                // an earlier announcement still awaiting its answer) — wait for the next cycle
                // once Leeza is free again, instead of overlapping two questions at once.
                if (awaitingCommandAfterWake) continue
                val allowed = currentSettings.notifAllowedPackages
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                if (allowed.isEmpty()) continue

                val unspoken = dao.getUnspoken()
                val (relevant, ignored) = unspoken.partition { it.packageName in allowed }
                // Not in the allow-list — never going to be announced, so clear them out now
                // rather than re-checking them forever.
                ignored.forEach { dao.markSpoken(it.key) }

                // Only announce ONE at a time, even if several arrived at once — announcing them
                // all in a row would overlap into a confusing pile-up of questions. The rest stay
                // queued (still unspoken) and get picked up on a later cycle once this one's answered.
                val next = relevant.firstOrNull()
                if (next != null) {
                    dao.markSpoken(next.key)
                    val announcement = "Sir, ${next.appLabel} se ek naya message aaya hai ${next.title} ki " +
                        "taraf se — kya aap sunna chahenge?"
                    announceAndListen(announcement, injectIntoHistory = true)
                }
            }
        }
    }

    override fun onDestroy() {
        wakeWordEngine?.stop()
        speechRecognizer?.destroy()
        mediaPlayer?.release()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val UTTERANCE_WAKE_ACK = "iris-wake-ack"
        private const val UTTERANCE_AGENT_REPLY = "iris-agent-reply"
        // Covers most common emoji blocks, including surrogate-pair (supplementary plane) emoji.
        private val EMOJI_REGEX = Regex("[\u2600-\u27BF\u2190-\u21FF\u2300-\u23FF\uFE0F\uD83C-\uDBFF\uDC00-\uDFFF]+")
    }
}
