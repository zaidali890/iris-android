package com.iris.android.tools

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.app.RemoteInput
import com.iris.android.agent.PermissionBroker
import com.iris.android.agent.ToolExecutor
import com.iris.android.data.AppDatabase
import com.iris.android.data.MemoryEntity
import com.iris.android.services.IrisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ToolExecutorImpl(
    private val context: Context,
    private val permissionBroker: PermissionBroker
) : ToolExecutor {

    private val memoryDao by lazy { AppDatabase.get(context).memoryDao() }
    private val notificationDao by lazy { AppDatabase.get(context).notificationDao() }

    override suspend fun execute(name: String, args: Map<String, Any?>): String {
        return when (name) {
            "open_app" -> openApp(args["appName"].toString())
            "list_installed_apps" -> listInstalledApps()
            "toggle_flashlight" -> toggleFlashlight((args["on"] as? Boolean) ?: true)
            "set_volume" -> setVolume((args["percent"] as? Number)?.toInt() ?: 50)
            "toggle_do_not_disturb" -> toggleDnd((args["on"] as? Boolean) ?: true)
            "open_quick_toggle_panel" -> openTogglePanel(args["setting"].toString())
            "take_screenshot" -> ScreenshotManager.capture(context)
            "read_file" -> readFile(args["uri"].toString())
            "write_note_file" -> writeNoteFile(args["filename"].toString(), args["content"].toString())
            "remember" -> remember(args["text"].toString(), args["tags"])
            "recall" -> recall(args["query"].toString())
            "create_reminder" -> ReminderScheduler.schedule(
                context,
                args["title"].toString(),
                args["body"]?.toString() ?: "",
                (args["minutesFromNow"] as? Number)?.toDouble() ?: 1.0
            )
            "get_recent_notifications" -> getRecentNotifications((args["limit"] as? Number)?.toInt() ?: 10)
            "reply_to_notification" -> replyToNotification(
                args["notificationKey"].toString(),
                args["message"].toString()
            )
            "send_whatsapp_message" -> sendWhatsAppMessage(args["contact"].toString(), args["message"].toString())
            "call_contact" -> callContact(args["contact"].toString())
            "get_device_status" -> getDeviceStatus()
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
    }

    // -----------------------------------------------------------------
    private fun openApp(appName: String): String {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(launchIntent, 0)
        val match = activities.firstOrNull {
            it.loadLabel(pm).toString().equals(appName, ignoreCase = true)
        } ?: activities.firstOrNull {
            it.loadLabel(pm).toString().contains(appName, ignoreCase = true)
        }
        if (match == null) return "Couldn't find an installed app matching \"$appName\"."
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "Found \"$appName\" but couldn't build a launch intent for it."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Opened ${match.loadLabel(pm)}."
    }

    private fun listInstalledApps(): String {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(launchIntent, 0)
        return activities.map { it.loadLabel(pm).toString() }.distinct().sorted().joinToString("\n")
    }

    // -----------------------------------------------------------------
    private fun toggleFlashlight(on: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This device doesn't report a flash unit."
            cameraManager.setTorchMode(cameraId, on)
            "Flashlight turned ${if (on) "on" else "off"}."
        } catch (e: Exception) {
            "Couldn't toggle the flashlight: ${e.message}"
        }
    }

    private fun setVolume(percent: Int): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent.coerceIn(0, 100) / 100.0).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return "Media volume set to $percent%."
    }

    private fun toggleDnd(on: Boolean): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            return "Do Not Disturb control needs one-time permission — open IRIS Settings and grant \"Notification Policy Access\"."
        }
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return "Do Not Disturb turned ${if (on) "on" else "off"}."
    }

    private fun openTogglePanel(setting: String): String {
        val intent = when (setting.lowercase()) {
            "wifi" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "airplane_mode" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            else -> return "Unknown toggle \"$setting\". Try wifi, bluetooth, or airplane_mode."
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Opened the $setting panel — Android requires you to tap it yourself for privacy reasons."
    }

    // -----------------------------------------------------------------
    private fun readFile(uriString: String): String {
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: "Couldn't open that file."
        } catch (e: Exception) {
            "Couldn't read that file: ${e.message}. It may need to be re-picked via the file picker in the app."
        }
    }

    private fun writeNoteFile(filename: String, content: String): String {
        val dir = File(context.getExternalFilesDir(null), "IRIS").apply { mkdirs() }
        val file = File(dir, filename)
        file.writeText(content)
        return "Saved ${content.length} characters to ${file.absolutePath}."
    }

    // -----------------------------------------------------------------
    private suspend fun remember(text: String, tagsRaw: Any?): String = withContext(Dispatchers.IO) {
        val tags = when (tagsRaw) {
            is JSONArray -> (0 until tagsRaw.length()).map { tagsRaw.getString(it) }
            is List<*> -> tagsRaw.map { it.toString() }
            else -> emptyList()
        }
        memoryDao.insert(MemoryEntity(text = text, tags = tags.joinToString(","), createdAt = System.currentTimeMillis()))
        "Remembered: \"$text\""
    }

    private suspend fun recall(query: String): String = withContext(Dispatchers.IO) {
        val terms = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        val all = memoryDao.getAll()
        val hits = if (terms.isEmpty()) all.take(5) else all.filter { entry ->
            terms.any { entry.text.lowercase().contains(it) }
        }.take(5)
        if (hits.isEmpty()) "No relevant memories found." else hits.joinToString("\n") { "- ${it.text}" }
    }

    // -----------------------------------------------------------------
    private suspend fun getRecentNotifications(limit: Int): String = withContext(Dispatchers.IO) {
        val all = notificationDao.getAll()
        if (all.isEmpty()) return@withContext "No notifications captured yet."
        all.take(limit).joinToString("\n") {
            "[${it.key}] ${it.appLabel}: ${it.title} — ${it.text}${if (it.hasReplyAction) " (reply available)" else ""}"
        }
    }

    private suspend fun replyToNotification(key: String, message: String): String {
        val approved = permissionBroker.requestApproval(
            "reply_to_notification", "Reply to a message notification", message
        )
        if (!approved) return "Permission denied by user. Reply was not sent."

        val action = com.iris.android.services.NotificationActionCache.get(key)
            ?: return "That notification is no longer available to reply to (it may have been dismissed)."

        return try {
            val intent = Intent()
            val bundle = android.os.Bundle()
            bundle.putCharSequence(action.remoteInput.resultKey, message)
            RemoteInput.addResultsToIntent(arrayOf(action.remoteInput), intent, bundle)
            action.pendingIntent.send(context, 0, intent)
            "Reply sent."
        } catch (e: Exception) {
            "Couldn't send the reply: ${e.message}"
        }
    }

    // -----------------------------------------------------------------
    private suspend fun sendWhatsAppMessage(contact: String, message: String): String {
        val approved = permissionBroker.requestApproval(
            "send_whatsapp_message", "Send a WhatsApp message to $contact", message
        )
        if (!approved) return "Permission denied by user. Message was not sent."

        val number = ContactResolver.resolve(context, contact)
            ?: return "Couldn't find a phone number for \"$contact\"."

        // WhatsApp's own documented click-to-chat URL — pre-fills the message, no automation needed for this part.
        val url = "https://api.whatsapp.com/send?phone=$number&text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        if (!IrisAccessibilityService.isEnabled()) {
            return "Opened WhatsApp with the message ready — enable the Accessibility automation permission in " +
                "Settings if you want IRIS to tap Send automatically. For now, please tap Send yourself."
        }

        val sent = IrisAccessibilityService.instance?.tapWhatsAppSend() ?: false
        return if (sent) "Message sent to $contact on WhatsApp." else
            "Opened WhatsApp with the message ready, but couldn't confirm the Send button was tapped — please check."
    }

    private fun callContact(contact: String): String {
        val number = ContactResolver.resolve(context, contact) ?: return "Couldn't find a phone number for \"$contact\"."
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Calling $contact."
    }

    // -----------------------------------------------------------------
    private fun getDeviceStatus(): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ringerMode = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        return JSONObject()
            .put("batteryPercent", level)
            .put("ringerMode", ringerMode)
            .toString()
    }
}
