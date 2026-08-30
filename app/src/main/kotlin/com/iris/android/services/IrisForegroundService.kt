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

    // Vosk runs independently of Android's SpeechRecognizer — genuinely continuous, offline,
    // no restart cycling or beeping. It's paused only while the single-shot command capture (which
    // still uses SpeechRecognizer, since that part was always working fine) needs the mic instead.
    private var wakeWordEngine: VoskWakeWordEngine? = null
    private var wakeLoopWanted = false
    private var awaitingCommandAfterWake = false

    private val wakeAckReplies = listOf(
        "Yes boss, I'm listening.",
        "Yes boss! What can I do for you?",
        "I'm here, boss — go ahead.",
        "Yes boss, what do you need?"
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
                if (utteranceId == UTTERANCE_WAKE_ACK) {
                    // TTS callbacks fire on an internal TTS thread, not the main thread — but
                    // SpeechRecognizer must be created/started on the main thread, so this was
                    // silently failing before. scope.launch (Dispatchers.Main) fixes that, and the
                    // short delay gives the audio system a moment to release the speaker before the
                    // mic tries to grab it, avoiding a race where the recognizer starts too early.
                    scope.launch {
                        delay(250)
                        startCommandListenAfterWake()
                    }
                }
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
        if (awaitingCommandAfterWake) return // command capture is using the mic right now
        wakeWordEngine?.start(currentSettings.wakeWord)
        _isWakeListening.value = wakeWordEngine?.isRunning() ?: false
    }

    fun stopWakeWordLoop() {
        wakeLoopWanted = false
        awaitingCommandAfterWake = false
        _isWakeListening.value = false
        wakeWordEngine?.stop()
    }

    private fun onWakeWordDetected() {
        // Vosk and the command-capture SpeechRecognizer can't use the mic at the same time —
        // pause wake detection while we listen for the actual command.
        wakeWordEngine?.stop()
        _isWakeListening.value = false
        awaitingCommandAfterWake = true
        val ack = wakeAckReplies.random()
        // Shown in the Command tab regardless of whether TTS audio actually plays — lets you tell
        // "wake word wasn't detected" apart from "detected fine, but the speaker/TTS engine didn't
        // say it out loud" if something's still off.
        events.tryEmit(AgentEvent.Final(ack))
        speak(ack, utteranceId = UTTERANCE_WAKE_ACK)
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
        wakeWordEngine?.stop()
        speechRecognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val UTTERANCE_WAKE_ACK = "iris-wake-ack"
    }
}
