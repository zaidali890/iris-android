package com.iris.android

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.iris.android.agent.AgentEvent
import com.iris.android.data.IrisSettings
import com.iris.android.data.SettingsRepository
import com.iris.android.services.IrisForegroundService
import com.iris.android.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var service: IrisForegroundService? = null
    private var bound by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as IrisForegroundService.LocalBinder).getService()
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The foreground service declares foregroundServiceType="microphone", which on Android 14+
     * throws at startForeground() time if RECORD_AUDIO isn't granted yet. So this is safe to call
     * repeatedly — it only actually starts the service once the permission exists — and is called
     * both at launch (if already granted from a previous run) and right after onboarding grants it.
     */
    private fun startAndBindServiceIfReady() {
        if (bound || !hasMicPermission()) return
        val serviceIntent = Intent(this, IrisForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startAndBindServiceIfReady()

        val settingsRepository = SettingsRepository(applicationContext)

        setContent {
            IrisTheme {
                var showOnboarding by remember { mutableStateOf(!hasMicPermission()) }
                var tab by remember { mutableIntStateOf(0) }
                var messages by remember { mutableStateOf(listOf<UiMessage>()) }
                var isThinking by remember { mutableStateOf(false) }
                var isListening by remember { mutableStateOf(false) }
                var permissionUi by remember { mutableStateOf<PermissionUiState?>(null) }
                val settingsState by settingsRepository.settingsFlow.collectAsState(initial = IrisSettings())
                val scope = rememberCoroutineScope()

                LaunchedEffect(bound) {
                    val svc = service ?: return@LaunchedEffect
                    launch {
                        svc.events.collect { event ->
                            if (event is AgentEvent.Thinking) isThinking = true
                            if (event is AgentEvent.Final || event is AgentEvent.Error) isThinking = false
                            messages = event.toUiMessages(messages)
                        }
                    }
                    launch {
                        svc.permissionRequests.collect { req ->
                            permissionUi = PermissionUiState(req.toolName, req.summary, req.detail)
                        }
                    }
                }

                if (showOnboarding) {
                    PermissionsScreen(onDone = {
                        showOnboarding = false
                        startAndBindServiceIfReady()
                    })
                } else {
                    Scaffold(
                        containerColor = Bg,
                        bottomBar = {
                            NavigationBar(containerColor = Panel) {
                                NavigationBarItem(
                                    selected = tab == 0,
                                    onClick = { tab = 0 },
                                    icon = { Icon(Icons.Filled.Chat, contentDescription = "Command") },
                                    label = { Text("Command") },
                                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent)
                                )
                                NavigationBarItem(
                                    selected = tab == 1,
                                    onClick = { tab = 1 },
                                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                                    label = { Text("Settings") },
                                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent)
                                )
                            }
                        }
                    ) { padding ->
                        Box(Modifier.padding(padding).background(Bg)) {
                            when (tab) {
                                0 -> CommandScreen(
                                    messages = messages,
                                    isThinking = isThinking,
                                    isListening = isListening,
                                    onSend = { text ->
                                        messages = messages + UiMessage(role = UiMessage.Role.USER, text = text)
                                        service?.sendCommand(text)
                                    },
                                    onMicToggle = {
                                        val svc = service ?: return@CommandScreen
                                        if (isListening) {
                                            svc.stopListening()
                                            isListening = false
                                        } else {
                                            isListening = true
                                            svc.startListening(
                                                onResult = { text ->
                                                    isListening = false
                                                    messages = messages + UiMessage(role = UiMessage.Role.USER, text = text)
                                                    svc.sendCommand(text)
                                                },
                                                onError = {
                                                    isListening = false
                                                    messages = messages + UiMessage(role = UiMessage.Role.ERROR, text = it)
                                                }
                                            )
                                        }
                                    },
                                    permissionRequest = permissionUi,
                                    onPermissionResponse = { approved ->
                                        service?.respondToPermission(approved)
                                        permissionUi = null
                                    }
                                )
                                1 -> SettingsScreen(
                                    settings = settingsState,
                                    repo = settingsRepository,
                                    onOpenPermissions = { showOnboarding = true }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        super.onDestroy()
    }
}
