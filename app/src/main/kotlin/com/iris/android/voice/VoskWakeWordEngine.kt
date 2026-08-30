package com.iris.android.voice

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Genuinely continuous, fully offline wake-word detection using Vosk — no account, no API key,
 * no network dependency once the model is downloaded once. Grammar is constrained to a tiny
 * vocabulary (just the wake word plus a catch-all "unknown" token) so it runs efficiently as a
 * true always-on background listener, unlike repeatedly restarting Android's SpeechRecognizer.
 */
class VoskWakeWordEngine(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onError: (String) -> Unit
) {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    fun start(wakeWord: String) {
        if (speechService != null) return

        if (!VoskModelManager.isModelReady(context)) {
            onError("The offline speech model hasn't been downloaded yet — go to Settings → Voice and tap \"Download offline wake-word model\" first.")
            return
        }

        try {
            val word = wakeWord.trim().lowercase().ifBlank { "iris" }
            model = Model(VoskModelManager.modelDir(context).absolutePath)
            // Constraining the grammar to just the wake word (+ an "unknown" catch-all for
            // everything else) keeps this lightweight enough to run continuously.
            val grammar = "[\"$word\", \"[unk]\"]"
            recognizer = Recognizer(model, 16000.0f, grammar)

            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    checkForWakeWord(hypothesis, word)
                }
                override fun onResult(hypothesis: String?) {
                    checkForWakeWord(hypothesis, word)
                }
                override fun onFinalResult(hypothesis: String?) {
                    checkForWakeWord(hypothesis, word)
                }
                override fun onError(exception: Exception?) {
                    onError("Vosk error: ${exception?.message}")
                }
                override fun onTimeout() {
                    // Continuous mode — nothing to do, listening keeps going
                }
            })
        } catch (e: Exception) {
            onError("Wake word engine failed to start: ${e.message}")
            stop()
        }
    }

    private fun checkForWakeWord(hypothesisJson: String?, wakeWord: String) {
        if (hypothesisJson == null) return
        try {
            val json = JSONObject(hypothesisJson)
            val text = (json.optString("text", "") + " " + json.optString("partial", "")).lowercase()
            if (text.contains(wakeWord)) {
                onWakeWordDetected()
            }
        } catch (e: Exception) {
            // malformed/unexpected JSON shape — ignore this cycle, next one will come shortly
        }
    }

    fun stop() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            // ignore
        }
        speechService = null
        try {
            recognizer?.close()
        } catch (e: Exception) {
            // ignore
        }
        recognizer = null
        model = null
    }

    fun isRunning(): Boolean = speechService != null
}
