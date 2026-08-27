package com.iris.android.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.android.services.IrisAccessibilityService
import com.iris.android.tools.ScreenshotManager

private data class PermRow(
    val title: String,
    val description: String,
    val isGranted: (Context) -> Boolean,
    val onRequest: () -> Unit
)

@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshTick++ }

    val screenshotLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            ScreenshotManager.grant(result.resultCode, result.data!!)
        }
        refreshTick++
    }

    val rows = remember(refreshTick) {
        listOf(
            PermRow(
                "Microphone & voice",
                "Needed to hear your voice commands.",
                { c -> hasSelf(c, Manifest.permission.RECORD_AUDIO) },
                { runtimePermLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
            ),
            PermRow(
                "Phone & contacts",
                "Needed to place/answer calls and resolve contact names to numbers.",
                { c ->
                    hasSelf(c, Manifest.permission.READ_PHONE_STATE) &&
                        hasSelf(c, Manifest.permission.CALL_PHONE) &&
                        hasSelf(c, Manifest.permission.READ_CONTACTS) &&
                        hasSelf(c, Manifest.permission.ANSWER_PHONE_CALLS)
                },
                {
                    runtimePermLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.ANSWER_PHONE_CALLS
                        )
                    )
                }
            ),
            PermRow(
                "Notifications (post)",
                "Needed for IRIS's own persistent status and reminder notifications.",
                { c ->
                    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                        hasSelf(c, Manifest.permission.POST_NOTIFICATIONS)
                },
                {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        runtimePermLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                }
            ),
            PermRow(
                "Read & reply to notifications",
                "Lets IRIS read out WhatsApp/SMS/etc. notifications and send quick replies. Opens a system settings screen — find IRIS in the list and enable it.",
                { c -> isNotificationListenerEnabled(c) },
                { context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
            ),
            PermRow(
                "Do Not Disturb control",
                "Lets IRIS toggle Do Not Disturb when you ask. Opens a system settings screen.",
                { c -> (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted },
                { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            ),
            PermRow(
                "Screen automation (optional)",
                "Only needed for auto-sending WhatsApp messages (tapping Send for you). Opens a system settings screen — this shows a strong warning, which is normal for this permission.",
                { IrisAccessibilityService.isEnabled() },
                { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            ),
            PermRow(
                "Screenshot capability (optional)",
                "One-time consent so IRIS can take screenshots when you ask.",
                { ScreenshotManager.hasPermission() },
                {
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    screenshotLauncher.launch(manager.createScreenCaptureIntent())
                }
            )
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Set up IRIS", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Grant what you want IRIS to do. Everything is optional except the mic — you can skip the rest and grant them later in Settings.",
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp)
        )

        rows.forEach { row ->
            val granted = row.isGranted(context)
            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(row.description, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (granted) {
                        Text("✓", color = Accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    } else {
                        Button(onClick = row.onRequest, colors = ButtonDefaults.buttonColors(containerColor = AccentDim)) {
                            Text("Grant", color = Accent, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        val micGranted = hasSelf(context, Manifest.permission.RECORD_AUDIO)
        if (!micGranted) {
            Text(
                "Microphone access is required before continuing — IRIS's assistant service can't start without it.",
                color = Amber,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Button(
            onClick = onDone,
            enabled = micGranted,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = TextMuted)
        ) {
            Text("Continue", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

private fun hasSelf(context: Context, permission: String): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat?.contains(context.packageName) == true
}
