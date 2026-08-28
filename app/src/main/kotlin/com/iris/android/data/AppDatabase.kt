package com.iris.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MemoryEntity::class, CapturedNotification::class, AllowedContactEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun notificationDao(): NotificationDao
    abstract fun contactDao(): AllowedContactDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "iris.db"
                )
                    // App is under active development — a destructive migration here just means
                    // memory/notification history resets on a schema bump, which is an acceptable
                    // tradeoff for a personal build versus hand-writing migrations at this stage.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
