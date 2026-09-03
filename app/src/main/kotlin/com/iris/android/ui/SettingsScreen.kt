package com.iris.android.ui

import android.content.Intent
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.iris.android.data.AllowedContactEntity
import com.iris.android.data.AppDatabase
import com.iris.android.data.IrisSettings
import com.iris.android.data.LlmProvider
import com.iris.android.data.SettingsRepository
import com.iris.android.data.TtsProvider
import com.iris.android.services.IrisAccessibilityService
import com.iris.android.tools.OemBackgroundSettings
import com.iris.android.voice.VoskModelManager
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: IrisSettings,
    repo: SettingsRepository,
    onOpenPermissions: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val contactDao = remember { AppDatabase.get(context).contactDao() }

    var contacts by remember { mutableStateOf(listOf<AllowedContactEntity>()) }
    fun refreshContacts() {
        scope.launch { contacts = contactDao.getAll() }
    }
    LaunchedEffect(Unit) { refreshContacts() }

    val pickContactLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val name = cursor.getString(nameIdx) ?: return@use
                val number = cursor.getString(numberIdx) ?: return@use
                scope.launch {
                    contactDao.insert(AllowedContactEntity(name = name, number = number))
                    refreshContacts()
                }
            }
        }
    }

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
            LabeledField(
                "Speech recognition language (e.g. ur-PK, ur-IN, en-US, en-IN) — applies to both " +
                    "the mic button and wake-word commands",
                settings.sttLanguage
            ) { scope.launch { repo.setSttLanguage(it) } }
            ToggleRow(
                "\"Wake up IRIS\" — genuinely continuous background listening",
                settings.wakeWordEnabled
            ) { scope.launch { repo.setWakeWordEnabled(it) } }
            if (settings.wakeWordEnabled) {
                Text(
                    "Uses Vosk — a free, open-source offline speech engine, not Android's built-in " +
                        "recognizer, so it doesn't need network and won't beep/restart every few " +
                        "seconds. Needs a one-time ~40MB model download below (internet required " +
                        "just for that download, fully offline afterward).",
                    color = TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )

                val modelReady = remember { mutableStateOf(VoskModelManager.isModelReady(context)) }
                var downloadProgress by remember { mutableIntStateOf(-1) }

                if (modelReady.value) {
                    Text("✓ Offline model ready", color = Accent, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
                } else if (downloadProgress in 0..99) {
                    Text("Downloading model… $downloadProgress%", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
                } else {
                    Button(
                        onClick = {
                            downloadProgress = 0
                            scope.launch {
                                val result = VoskModelManager.download(context) { p -> downloadProgress = p }
                                if (result.isSuccess) {
                                    modelReady.value = true
                                } else {
                                    downloadProgress = -1
                                    android.widget.Toast.makeText(
                                        context,
                                        "Download failed: ${result.exceptionOrNull()?.message}",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentDim),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Download offline wake-word model (~40MB)", color = Accent, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                LabeledField("Wake word to listen for", settings.wakeWord) {
                    scope.launch { repo.setWakeWord(it) }
                }
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

        SectionCard("Messaging Contacts") {
            Text(
                "IRIS can only send WhatsApp messages or place calls to contacts you add here — " +
                    "not your whole phone book. This keeps voice/text-to-speech mistakes (wrong name, " +
                    "similar spelling) from ever reaching the wrong person.",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            contacts.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(c.name, color = TextPrimary, fontSize = 13.sp)
                        Text(c.number, color = TextMuted, fontSize = 10.sp)
                    }
                    Text(
                        "✕",
                        color = Red,
                        fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                            scope.launch {
                                contactDao.delete(c.id)
                                refreshContacts()
                            }
                        }.padding(6.dp)
                    )
                }
            }
            if (contacts.isEmpty()) {
                Text(
                    "No contacts added yet.",
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    pickContactLauncher.launch(
                        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentDim),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+ Add from Phone Contacts", color = Accent, fontSize = 13.sp)
            }
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
            if (!settings.autoAnswerCalls) {
                ToggleRow(
                    "Announce incoming calls, accept/reject by voice",
                    settings.announceIncomingCalls
                ) { scope.launch { repo.setAnnounceIncomingCalls(it) } }
                if (settings.announceIncomingCalls) {
                    Text(
                        "Rejecting is best-effort — Android restricts non-default-dialer apps from " +
                            "hanging up calls, so this may not work on every device/Android version.",
                        color = TextMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                    )
                }
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

            Spacer(Modifier.height(12.dp))
            Text(
                "Wake word dying in the background?",
                color = Amber,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Your phone (${OemBackgroundSettings.manufacturerHint()}) likely has its own battery " +
                    "manager beyond Android's standard one — grant \"Ignore battery optimization\" above " +
                    "(Review system permissions), then also try this:",
                color = TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            Button(
                onClick = {
                    val opened = OemBackgroundSettings.tryOpen(context)
                    if (!opened) {
                        android.widget.Toast.makeText(
                            context,
                            "Couldn't find a known autostart manager for this device — check your phone's own Settings app for \"Autostart\" or \"Battery > App battery saver\" manually.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Panel),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Try opening autostart/background settings", color = Accent, fontSize = 12.sp)
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
