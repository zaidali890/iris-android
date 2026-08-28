package com.iris.android.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "allowed_contacts")
data class AllowedContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val number: String
)

@Dao
interface AllowedContactDao {
    @Insert
    suspend fun insert(contact: AllowedContactEntity): Long

    @Query("SELECT * FROM allowed_contacts ORDER BY name ASC")
    suspend fun getAll(): List<AllowedContactEntity>

    @Query("DELETE FROM allowed_contacts WHERE id = :id")
    suspend fun delete(id: Long)
}
