package com.example.mydevice.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.mydevice.data.local.database.entity.IncomingMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IncomingMessageDao {

    @Query("SELECT * FROM incoming_messages ORDER BY receivedAt DESC")
    fun getAllMessages(): Flow<List<IncomingMessageEntity>>

    @Query("SELECT * FROM incoming_messages WHERE isRead = 0 ORDER BY receivedAt DESC")
    fun getUnreadMessages(): Flow<List<IncomingMessageEntity>>

    @Query("SELECT COUNT(*) FROM incoming_messages WHERE isRead = 0")
    fun getUnreadCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: IncomingMessageEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM incoming_messages WHERE id = :messageId)")
    suspend fun exists(messageId: String): Boolean

    @Query("UPDATE incoming_messages SET isRead = 1 WHERE id = :messageId")
    suspend fun markAsRead(messageId: String)

    @Query("UPDATE incoming_messages SET isRead = 1")
    suspend fun markAllAsRead()

    @Query("DELETE FROM incoming_messages")
    suspend fun deleteAll()
}
