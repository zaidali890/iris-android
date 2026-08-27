package com.iris.android.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memory")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val tags: String, // comma-separated, kept simple on purpose
    val createdAt: Long
)

@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(entry: MemoryEntity): Long

    @Query("SELECT * FROM memory ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory ORDER BY createdAt DESC")
    suspend fun getAll(): List<MemoryEntity>

    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memory")
    suspend fun clear()
}
