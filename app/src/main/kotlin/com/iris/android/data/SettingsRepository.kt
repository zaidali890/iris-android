package com.iris.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "iris_settings")

enum class LlmProvider { GROK, GROQ, ANTHROPIC, OPENAI, OLLAMA }
enum class TtsProvider { DEVICE, FISH_AUDIO }

data class IrisSettings(
    val llmProvider: LlmProvider = LlmProvider.GROK,
    val grokApiKey: String = "",
    val grokModel: String = "grok-2-latest",
    val groqApiKey: String = "",
    val groqModel: String = "llama-3.3-70b-versatile",
    val anthropicApiKey: String = "",
    val anthropicModel: String = "claude-sonnet-4-6",
    val openaiApiKey: String = "",
    val openaiModel: String = "gpt-4o-mini",
    val ollamaBaseUrl: String = "http://192.168.1.100:11434",
    val ollamaModel: String = "llama3.1",
    val ttsProvider: TtsProvider = TtsProvider.DEVICE,
    val fishAudioApiKey: String = "",
    val fishAudioVoiceId: String = "",
    val personaName: String = "Leeza",
    val personaStyle: String = "warm, encouraging, and genuinely caring — checks in like a good friend, " +
        "notices your mood, and motivates you, while staying a helpful assistant rather than a romantic companion",
    val autoSpeakNotifications: Boolean = true,
    val notifAllowedPackages: String = "com.whatsapp,com.whatsapp.w4b",
    val wakeWordEnabled: Boolean = false,
    val wakeWord: String = "leeza",
    val sttLanguage: String = "ur-PK",
    val speakAgentReplies: Boolean = true,
    val autoReplyEnabled: Boolean = false,
    val autoAnswerCalls: Boolean = false,
    val announceIncomingCalls: Boolean = false,
    val accessibilityAutomationEnabled: Boolean = false,
    val requireConfirmForDestructive: Boolean = true
)

object Keys {
    val LLM_PROVIDER = stringPreferencesKey("llm_provider")
    val GROK_KEY = stringPreferencesKey("grok_key")
    val GROK_MODEL = stringPreferencesKey("grok_model")
    val GROQ_KEY = stringPreferencesKey("groq_key")
    val GROQ_MODEL = stringPreferencesKey("groq_model")
    val ANTHROPIC_KEY = stringPreferencesKey("anthropic_key")
    val ANTHROPIC_MODEL = stringPreferencesKey("anthropic_model")
    val OPENAI_KEY = stringPreferencesKey("openai_key")
    val OPENAI_MODEL = stringPreferencesKey("openai_model")
    val OLLAMA_URL = stringPreferencesKey("ollama_url")
    val OLLAMA_MODEL = stringPreferencesKey("ollama_model")
    val TTS_PROVIDER = stringPreferencesKey("tts_provider")
    val FISH_KEY = stringPreferencesKey("fish_key")
    val FISH_VOICE = stringPreferencesKey("fish_voice")
    val PERSONA_NAME = stringPreferencesKey("persona_name")
    val PERSONA_STYLE = stringPreferencesKey("persona_style")
    val AUTO_SPEAK = booleanPreferencesKey("auto_speak")
    val NOTIF_ALLOWED_PACKAGES = stringPreferencesKey("notif_allowed_packages")
    val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
    val WAKE_WORD = stringPreferencesKey("wake_word")
    val STT_LANGUAGE = stringPreferencesKey("stt_language")
    val SPEAK_AGENT_REPLIES = booleanPreferencesKey("speak_agent_replies")
    val AUTO_REPLY = booleanPreferencesKey("auto_reply")
    val AUTO_ANSWER = booleanPreferencesKey("auto_answer")
    val ANNOUNCE_CALLS = booleanPreferencesKey("announce_calls")
    val ACCESSIBILITY_AUTOMATION = booleanPreferencesKey("accessibility_automation")
    val CONFIRM_DESTRUCTIVE = booleanPreferencesKey("confirm_destructive")
    val REMOTE_PORT = intPreferencesKey("remote_port")
}

class SettingsRepository(private val context: Context) {

    val settingsFlow: Flow<IrisSettings> = context.dataStore.data.map { p ->
        IrisSettings(
            llmProvider = p[Keys.LLM_PROVIDER]?.let { runCatching { LlmProvider.valueOf(it) }.getOrNull() }
                ?: LlmProvider.GROK,
            grokApiKey = p[Keys.GROK_KEY] ?: "",
            grokModel = p[Keys.GROK_MODEL] ?: "grok-2-latest",
            groqApiKey = p[Keys.GROQ_KEY] ?: "",
            groqModel = p[Keys.GROQ_MODEL] ?: "llama-3.3-70b-versatile",
            anthropicApiKey = p[Keys.ANTHROPIC_KEY] ?: "",
            anthropicModel = p[Keys.ANTHROPIC_MODEL] ?: "claude-sonnet-4-6",
            openaiApiKey = p[Keys.OPENAI_KEY] ?: "",
            openaiModel = p[Keys.OPENAI_MODEL] ?: "gpt-4o-mini",
            ollamaBaseUrl = p[Keys.OLLAMA_URL] ?: "http://192.168.1.100:11434",
            ollamaModel = p[Keys.OLLAMA_MODEL] ?: "llama3.1",
            ttsProvider = p[Keys.TTS_PROVIDER]?.let { runCatching { TtsProvider.valueOf(it) }.getOrNull() }
                ?: TtsProvider.DEVICE,
            fishAudioApiKey = p[Keys.FISH_KEY] ?: "",
            fishAudioVoiceId = p[Keys.FISH_VOICE] ?: "",
            personaName = p[Keys.PERSONA_NAME] ?: "Leeza",
            personaStyle = p[Keys.PERSONA_STYLE]
                ?: "warm, encouraging, and genuinely caring — checks in like a good friend, notices your " +
                    "mood, and motivates you, while staying a helpful assistant rather than a romantic companion",
            autoSpeakNotifications = p[Keys.AUTO_SPEAK] ?: true,
            notifAllowedPackages = p[Keys.NOTIF_ALLOWED_PACKAGES] ?: "com.whatsapp,com.whatsapp.w4b",
            wakeWordEnabled = p[Keys.WAKE_WORD_ENABLED] ?: false,
            wakeWord = p[Keys.WAKE_WORD] ?: "leeza",
            sttLanguage = p[Keys.STT_LANGUAGE] ?: "ur-PK",
            speakAgentReplies = p[Keys.SPEAK_AGENT_REPLIES] ?: true,
            autoReplyEnabled = p[Keys.AUTO_REPLY] ?: false,
            autoAnswerCalls = p[Keys.AUTO_ANSWER] ?: false,
            announceIncomingCalls = p[Keys.ANNOUNCE_CALLS] ?: false,
            accessibilityAutomationEnabled = p[Keys.ACCESSIBILITY_AUTOMATION] ?: false,
            requireConfirmForDestructive = p[Keys.CONFIRM_DESTRUCTIVE] ?: true
        )
    }

    suspend fun setLlmProvider(v: LlmProvider) = context.dataStore.edit { it[Keys.LLM_PROVIDER] = v.name }
    suspend fun setGrokKey(v: String) = context.dataStore.edit { it[Keys.GROK_KEY] = v }
    suspend fun setGrokModel(v: String) = context.dataStore.edit { it[Keys.GROK_MODEL] = v }
    suspend fun setGroqKey(v: String) = context.dataStore.edit { it[Keys.GROQ_KEY] = v }
    suspend fun setGroqModel(v: String) = context.dataStore.edit { it[Keys.GROQ_MODEL] = v }
    suspend fun setAnthropicKey(v: String) = context.dataStore.edit { it[Keys.ANTHROPIC_KEY] = v }
    suspend fun setAnthropicModel(v: String) = context.dataStore.edit { it[Keys.ANTHROPIC_MODEL] = v }
    suspend fun setOpenAiKey(v: String) = context.dataStore.edit { it[Keys.OPENAI_KEY] = v }
    suspend fun setOpenAiModel(v: String) = context.dataStore.edit { it[Keys.OPENAI_MODEL] = v }
    suspend fun setOllamaUrl(v: String) = context.dataStore.edit { it[Keys.OLLAMA_URL] = v }
    suspend fun setOllamaModel(v: String) = context.dataStore.edit { it[Keys.OLLAMA_MODEL] = v }
    suspend fun setTtsProvider(v: TtsProvider) = context.dataStore.edit { it[Keys.TTS_PROVIDER] = v.name }
    suspend fun setFishKey(v: String) = context.dataStore.edit { it[Keys.FISH_KEY] = v }
    suspend fun setFishVoice(v: String) = context.dataStore.edit { it[Keys.FISH_VOICE] = v }
    suspend fun setPersonaName(v: String) = context.dataStore.edit { it[Keys.PERSONA_NAME] = v }
    suspend fun setPersonaStyle(v: String) = context.dataStore.edit { it[Keys.PERSONA_STYLE] = v }
    suspend fun setAutoSpeak(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_SPEAK] = v }
    suspend fun setNotifAllowedPackages(v: String) = context.dataStore.edit { it[Keys.NOTIF_ALLOWED_PACKAGES] = v }
    suspend fun setWakeWordEnabled(v: Boolean) = context.dataStore.edit { it[Keys.WAKE_WORD_ENABLED] = v }
    suspend fun setWakeWord(v: String) = context.dataStore.edit { it[Keys.WAKE_WORD] = v }
    suspend fun setSttLanguage(v: String) = context.dataStore.edit { it[Keys.STT_LANGUAGE] = v }
    suspend fun setSpeakAgentReplies(v: Boolean) = context.dataStore.edit { it[Keys.SPEAK_AGENT_REPLIES] = v }
    suspend fun setAutoReply(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_REPLY] = v }
    suspend fun setAutoAnswer(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_ANSWER] = v }
    suspend fun setAnnounceIncomingCalls(v: Boolean) = context.dataStore.edit { it[Keys.ANNOUNCE_CALLS] = v }
    suspend fun setAccessibilityAutomation(v: Boolean) =
        context.dataStore.edit { it[Keys.ACCESSIBILITY_AUTOMATION] = v }
    suspend fun setConfirmDestructive(v: Boolean) = context.dataStore.edit { it[Keys.CONFIRM_DESTRUCTIVE] = v }
}
