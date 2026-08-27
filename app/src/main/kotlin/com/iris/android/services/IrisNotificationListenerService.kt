package com.iris.android.services

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.iris.android.data.AppDatabase
import com.iris.android.data.CapturedNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** In-memory only — PendingIntents can't survive process death, which is fine since a reply only makes sense while the notification is still live. */
object NotificationActionCache {
    data class ReplyAction(val remoteInput: RemoteInput, val pendingIntent: android.app.PendingIntent, val intent: Intent)

    private val cache = mutableMapOf<String, ReplyAction>()

    fun put(key: String, action: ReplyAction) {
        cache[key] = action
    }

    fun get(key: String): ReplyAction? = cache[key]

    fun remove(key: String) {
        cache.remove(key)
    }
}

class IrisNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return // ignore our own notifications
        val notification = sbn.notification ?: return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return

        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        var hasReply = false
        notification.actions?.forEach { action ->
            val remoteInputs = action.remoteInputs
            if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                hasReply = true
                NotificationActionCache.put(
                    sbn.key,
                    NotificationActionCache.ReplyAction(remoteInputs[0], action.actionIntent, Intent())
                )
            }
        }

        scope.launch {
            val dao = AppDatabase.get(applicationContext).notificationDao()
            dao.upsert(
                CapturedNotification(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appLabel = appLabel,
                    title = title,
                    text = text,
                    postedAt = sbn.postTime,
                    hasReplyAction = hasReply
                )
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationActionCache.remove(sbn.key)
    }
}
