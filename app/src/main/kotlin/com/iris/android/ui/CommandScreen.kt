package com.iris.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.android.agent.AgentEvent
import java.util.UUID

data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val toolName: String? = null
) {
    enum class Role { USER, ASSISTANT, TOOL, ERROR }
}

@Composable
fun CommandScreen(
    messages: List<UiMessage>,
    isThinking: Boolean,
    isListening: Boolean,
    onSend: (String) -> Unit,
    onMicToggle: () -> Unit,
    permissionRequest: PermissionUiState?,
    onPermissionResponse: (Boolean) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, isThinking) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            if (messages.isEmpty() && !isThinking) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "Say or type a command — \"open WhatsApp\", \"turn on flashlight\", \"read my notifications\"",
                        color = TextMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(messages, key = { it.id }) { msg -> Bubble(msg) }
                    if (isThinking) {
                        item {
                            Text("THINKING…", color = Accent, fontSize = 10.sp, modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Panel)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a command…", color = TextMuted, fontSize = 13.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )
                IconButton(onClick = {
                    if (input.isNotBlank()) {
                        onSend(input)
                        input = ""
                    }
                }) {
                    Icon(Icons.Filled.Send, contentDescription = "Send", tint = Accent)
                }
                IconButton(onClick = onMicToggle) {
                    Icon(
                        if (isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = "Mic",
                        tint = if (isListening) Red else Accent
                    )
                }
            }
        }

        permissionRequest?.let {
            PermissionModal(it, onPermissionResponse)
        }
    }
}

@Composable
private fun Bubble(msg: UiMessage) {
    when (msg.role) {
        UiMessage.Role.TOOL -> Column(
            Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(14.dp))
                .background(Cyan.copy(alpha = 0.08f))
                .padding(10.dp)
        ) {
            Text(msg.toolName ?: "tool", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(msg.text, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        UiMessage.Role.ERROR -> Box(
            Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(14.dp))
                .background(Red.copy(alpha = 0.08f))
                .padding(10.dp)
        ) {
            Text(msg.text, color = Color(0xFFFCA5A5), fontSize = 12.sp)
        }
        else -> {
            val isUser = msg.role == UiMessage.Role.USER
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                Box(
                    Modifier
                        .fillMaxWidth(0.85f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isUser) AccentDim else Panel)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(msg.text, color = TextPrimary, fontSize = 14.sp)
                }
            }
        }
    }
}

data class PermissionUiState(val toolName: String, val summary: String, val detail: String?)

@Composable
fun PermissionModal(request: PermissionUiState, onRespond: (Boolean) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(20.dp))
                .background(Panel)
                .padding(20.dp)
        ) {
            Text("IRIS wants permission", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                request.toolName,
                color = Amber,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            Text(request.summary, color = TextSecondary, fontSize = 13.sp)
            request.detail?.let {
                Text(it, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = { onRespond(false) }, modifier = Modifier.weight(1f)) {
                    Text("Deny", color = TextSecondary)
                }
                Button(
                    onClick = { onRespond(true) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber)
                ) {
                    Text("Allow Once", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

fun AgentEvent.toUiMessages(existing: List<UiMessage>): List<UiMessage> = when (this) {
    is AgentEvent.ToolCall -> existing + UiMessage(
        id = id,
        role = UiMessage.Role.TOOL,
        text = "Running $name…",
        toolName = name
    )
    is AgentEvent.ToolResult -> existing.map {
        if (it.id == id) it.copy(text = result, role = if (error) UiMessage.Role.ERROR else UiMessage.Role.TOOL) else it
    }
    is AgentEvent.Final -> existing + UiMessage(role = UiMessage.Role.ASSISTANT, text = text)
    is AgentEvent.Error -> existing + UiMessage(role = UiMessage.Role.ERROR, text = message)
    AgentEvent.Thinking -> existing
}
