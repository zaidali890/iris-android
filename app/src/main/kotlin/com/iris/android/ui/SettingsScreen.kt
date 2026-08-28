package com.iris.android.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.android.data.IrisSettings
import com.iris.android.data.LlmProvider
import com.iris.android.data.SettingsRepository
import com.iris.android.data.TtsProvider
import com.iris.android.services.IrisAccessibilityService
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: IrisSettings,
    repo: SettingsRepository,
    onOpenPermissions: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Settings", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        SectionCard("Brain (LLM)") {
            listOf(
                LlmProvider.GROK to "Grok (xAI)",
                LlmProvider.GROQ to "Groq (fast, free-tier open models)",
                LlmProvider.ANTHROPIC to "Anthropic",
                LlmProvider.OPENAI to "OpenAI",
                LlmProvider.OLLAMA to "Ollama (local network)"
            ).forEach { (provider, label) ->
                RadioRow(label, settings.llmProvider == provider) {
                    scope.launch { repo.setLlmProvider(provider) }
                }
            }

            Spacer(Modifier.height(10.dp))
            when (settings.llmProvider) {
                LlmProvider.GROK -> {
                    LabeledField("Grok API key (from console.x.ai)", settings.grokApiKey, secret = true) {
                        scope.launch { repo.setGrokKey(it) }
                    }
                    LabeledField("Model", settings.grokModel) { scope.launch { repo.setGrokModel(it) } }
                }
                LlmProvider.GROQ -> {
                    LabeledField("Groq API key (from console.groq.com)", settings.groqApiKey, secret = true) {
                        scope.launch { repo.setGroqKey(it) }
                    }
                    LabeledField("Model", settings.groqModel) { scope.launch { repo.setGroqModel(it) } }
                }
                LlmProvider.ANTHROPIC -> {
                    LabeledField("Anthropic API key", settings.anthropicApiKey, secret = true) {
                        scope.launch { repo.setAnthropicKey(it) }
                    }
                    LabeledField("Model", settings.anthropicModel) { scope.launch { repo.setAnthropicModel(it) } }
                }
                LlmProvider.OPENAI -> {
                    LabeledField("OpenAI API key", settings.openaiApiKey, secret = true) {
                        scope.launch { repo.setOpenAiKey(it) }
                    }
                    LabeledField("Model", settings.openaiModel) { scope.launch { repo.setOpenAiModel(it) } }
                }
                LlmProvider.OLLAMA -> {
                    LabeledField("Base URL", settings.ollamaBaseUrl) { scope.launch { repo.setOllamaUrl(it) } }
                    LabeledField("Model", settings.ollamaModel) { scope.launch { repo.setOllamaModel(it) } }
                }
            }
        }

        SectionCard("Voice") {
            listOf(
                TtsProvider.DEVICE to "Device built-in (free, offline)",
                TtsProvider.FISH_AUDIO to "Fish Audio (nicer voice, needs a key)"
            ).forEach { (provider, label) ->
                RadioRow(label, settings.ttsProvider == provider) {
                    scope.launch { repo.setTtsProvider(provider) }
                }
            }
            if (settings.ttsProvider == TtsProvider.FISH_AUDIO) {
                LabeledField("Fish Audio API key", settings.fishAudioApiKey, secret = true) {
                    scope.launch { repo.setFishKey(it) }
                }
                LabeledField("Voice ID", settings.fishAudioVoiceId) { scope.launch { repo.setFishVoice(it) } }
            }
            ToggleRow("Speak IRIS's replies out loud", settings.speakAgentReplies) {
                scope.launch { repo.setSpeakAgentReplies(it) }
            }
            ToggleRow(
                "\"Wake up IRIS\" — listen in background for a wake word",
                settings.wakeWordEnabled
            ) { scope.launch { repo.setWakeWordEnabled(it) } }
            if (settings.wakeWordEnabled) {
                Text(
                    "Say \"wake up IRIS\", \"hi IRIS\", or just \"IRIS\" — it'll reply \"Yes boss\" and " +
                        "then listen for your command. Uses more battery since the mic stays active.",
                    color = TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
            }
        }

        SectionCard("Notifications") {
            ToggleRow("Speak allowed notifications aloud", settings.autoSpeakNotifications) {
                scope.launch { repo.setAutoSpeak(it) }
            }
            LabeledField(
                "Apps to read aloud (comma-separated package names)",
                settings.notifAllowedPackages
            ) { scope.launch { repo.setNotifAllowedPackages(it) } }
            Text(
                "Default is WhatsApp only, so IRIS doesn't narrate every app on your phone. " +
                    "Package names, not app names — e.g. com.whatsapp",
                color = TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        SectionCard("Persona") {
            LabeledField("Name", settings.personaName) { scope.launch { repo.setPersonaName(it) } }
            LabeledField("Style", settings.personaStyle) { scope.launch { repo.setPersonaStyle(it) } }
        }

        SectionCard("Safety & Permissions") {
            ToggleRow("Confirm before destructive actions", settings.requireConfirmForDestructive) {
                scope.launch { repo.setConfirmDestructive(it) }
            }
            ToggleRow("Auto-answer phone calls", settings.autoAnswerCalls) {
                scope.launch { repo.setAutoAnswer(it) }
            }

            val accessibilityOn = IrisAccessibilityService.isEnabled()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("WhatsApp send automation", color = TextSecondary, fontSize = 13.sp)
                    Text(
                        if (accessibilityOn) "Enabled — IRIS can tap Send for you" else "Off — you'll need to tap Send yourself",
                        color = if (accessibilityOn) Accent else TextMuted,
                        fontSize = 10.sp
                    )
                }
                if (!accessibilityOn) {
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentDim)
                    ) {
                        Text("Enable", color = Accent, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Button(
                onClick = onOpenPermissions,
                colors = ButtonDefaults.buttonColors(containerColor = Panel),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Review system permissions", color = Accent, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0x1A00FF41) else Color.Transparent)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = TextMuted)
        )
        Text(label, color = TextPrimary, fontSize = 13.sp)
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent)
        )
    }
}

@Composable
private fun LabeledField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = TextMuted, fontSize = 10.sp)
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(it)
            },
            singleLine = true,
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = Accent,
                unfocusedBorderColor = TextMuted
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
        )
    }
}
