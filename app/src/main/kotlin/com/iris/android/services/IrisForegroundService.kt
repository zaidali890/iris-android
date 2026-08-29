package com.iris.android.services

import android.app.Service
import android.content.Intent
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
import com.iris.android.tools.ToolExecutorImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class IrisForegroundService : Service(), PermissionBroker {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    // Wake-word loop state — guards against overlapping listen sessions and lets us tell a
    // "no match, just restart the loop" result apart from "user gave a real one-shot command".
    private var wakeLoopWanted = false
    private var awaitingCommandAfterWake = false

    private val wakeAckReplies = listOf(
        "Yes boss, I'm listening.",
        "Yes boss! What can I do for you?",
        "I'm here, boss — go ahead.",
        "Yes boss, what do you need?"
    )

    /** True if the transcribed text contains the word "iris" on its own, ignoring punctuation —
     * catches "wake up iris", "hi iris", "hey, iris" (note the comma!), and plain "iris" alike.
     * The old version matched exact multi-word phrases like "hey iris" as a literal substring,
     * which silently failed the moment the recognizer inserted a comma — a very common transcription
     * artifact for a greeting + name pattern like this. */
    private fun containsWakeWord(heard: String): Boolean {
        val words = heard.lowercase().replace(Regex("[^a-z ]"), " ").split(" ").filter { it.isNotBlank() }
        return words.any { it == "iris" }
    }

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
                if (utteranceId == UTTERANCE_WAKE_ACK) startCommandListenAfterWake()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
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
                if (event is AgentEvent.Final && currentSettings.speakAgentReplies) {
                    speak(event.text)
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
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
    }

    /** Wake-loop sessions use a longer silence window than manual tap-to-talk, so the recognizer
     * doesn't consider "done speaking" so eagerly — this is the main lever available for reducing
     * how often the background listener has to restart (and re-beep). It can't be eliminated
     * entirely: Android's SpeechRecognizer always ends a session eventually and has to be restarted
     * for genuinely continuous listening — that's an OS-level constraint, not something toggleable
     * from here. A dedicated wake-word engine (e.g. Picovoice, free tier) is the real fix if
     * completely silent always-on listening matters more than staying free of extra dependencies. */
    /** Reliability over reduced-cycling: those non-standard "listen longer" extras are unofficial
     * Google-internal hints that many non-Google speech engines simply ignore or mishandle, which
     * can cause empty/garbled results on devices without full Google Play Services (no Chrome/Google
     * app on this build strongly suggests that's the case here) — so this uses the plain, standard
     * intent instead. It'll cycle a bit more often, but should actually detect the wake word again. */
    private fun buildWakeLoopRecognizerIntent() = buildRecognizerIntent()

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
    // Voice — background wake-word loop ("wake up iris" / "hi iris" / "iris")
    // -----------------------------------------------------------------
    fun startWakeWordLoop() {
        if (!currentSettings.wakeWordEnabled) return
        wakeLoopWanted = true
        if (_isWakeListening.value || awaitingCommandAfterWake) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        _isWakeListening.value = true
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    val heard = text.orEmpty()
                    if (containsWakeWord(heard)) {
                        _isWakeListening.value = false
                        awaitingCommandAfterWake = true
                        val ack = wakeAckReplies.random()
                        // Shown in the Command tab regardless of whether TTS audio actually plays —
                        // lets you tell "wake word wasn't detected" apart from "detected fine, but
                        // the speaker/TTS engine didn't say it out loud" if something's still off.
                        events.tryEmit(AgentEvent.Final(ack))
                        speak(ack, utteranceId = UTTERANCE_WAKE_ACK)
                    } else {
                        restartWakeLoopSoon()
                    }
                }
                override fun onError(error: Int) {
                    restartWakeLoopSoon()
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        speechRecognizer?.startListening(buildWakeLoopRecognizerIntent())
    }

    fun stopWakeWordLoop() {
        wakeLoopWanted = false
        awaitingCommandAfterWake = false
        _isWakeListening.value = false
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun restartWakeLoopSoon() {
        _isWakeListening.value = false
        if (!wakeLoopWanted) return
        scope.launch {
            delay(400) // short breather so back-to-back recognizer errors don't spin tightly
            startWakeWordLoop()
        }
    }

    private fun startCommandListenAfterWake() {
        if (!awaitingCommandAfterWake) return
        runSingleShotListen(
            onResult = { text ->
                awaitingCommandAfterWake = false
                sendCommand(text)
                if (wakeLoopWanted) startWakeWordLoop()
            },
            onError = {
                awaitingCommandAfterWake = false
                if (wakeLoopWanted) startWakeWordLoop()
            }
        )
    }

    // -----------------------------------------------------------------
    // TTS
    // -----------------------------------------------------------------
    private fun speak(text: String, utteranceId: String = "iris-utterance") {
        // Fish Audio (or any other cloud TTS) can be wired in here later; falling back to the
        // device's built-in engine keeps voice output fully free and working offline today.
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stopSpeaking() {
        tts?.stop()
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
                val allowed = currentSettings.notifAllowedPackages
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                if (allowed.isEmpty()) continue

                val unspoken = dao.getUnspoken()
                for (n in unspoken.filter { it.packageName in allowed }) {
                    val body = "${n.title}. ${n.text}".take(200)
                    speak("${n.appLabel} says: $body")
                }
                // Mark ALL unspoken notifications as handled, including ones from apps not in the
                // allow-list, so they don't pile up in the queue forever waiting to be spoken.
                unspoken.forEach { dao.markSpoken(it.key) }
            }
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val UTTERANCE_WAKE_ACK = "iris-wake-ack"
    }
}
