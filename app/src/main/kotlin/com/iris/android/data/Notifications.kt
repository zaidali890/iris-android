package com.iris.android.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "captured_notifications")
data class CapturedNotification(
    @PrimaryKey val key: String, // Android's StatusBarNotification#getKey — stable per-notification id
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val hasReplyAction: Boolean,
    val spoken: Boolean = false,
    // Distinguishes an incoming call notification (WhatsApp voice/video call, marked by Android's
    // own CATEGORY_CALL) from a regular message notification — without this, a WhatsApp call was
    // being announced with the generic "X sent a message" text instead of "X is calling."
    val isCallCategory: Boolean = false
)

@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CapturedNotification)

    @Query("SELECT * FROM captured_notifications ORDER BY postedAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<CapturedNotification>>

    @Query("SELECT * FROM captured_notifications ORDER BY postedAt DESC LIMIT 200")
    suspend fun getAll(): List<CapturedNotification>

    @Query("SELECT * FROM captured_notifications WHERE key = :key LIMIT 1")
    suspend fun getByKey(key: String): CapturedNotification?

    @Query("SELECT * FROM captured_notifications WHERE spoken = 0 ORDER BY postedAt ASC")
    suspend fun getUnspoken(): List<CapturedNotification>

    @Query("UPDATE captured_notifications SET spoken = 1 WHERE key = :key")
    suspend fun markSpoken(key: String)

    @Query("DELETE FROM captured_notifications WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM captured_notifications")
    suspend fun clear()
}
