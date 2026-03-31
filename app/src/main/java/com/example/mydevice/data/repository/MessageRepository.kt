package com.example.mydevice.data.repository

import com.example.mydevice.data.local.database.dao.IncomingMessageDao
import com.example.mydevice.data.local.database.entity.IncomingMessageEntity
import com.example.mydevice.data.remote.signalr.SignalRMessage
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Handles incoming messages from SignalR → local Room storage.
 *
 * Messages arrive via SignalR, get saved to Room, and the UI observes
 * them as a Flow. Acknowledgement is sent back via SignalR immediately.
 */
class MessageRepository(
    private val messageDao: IncomingMessageDao
) {
    fun getAllMessages(): Flow<List<IncomingMessageEntity>> =
        messageDao.getAllMessages()

    fun getUnreadMessages(): Flow<List<IncomingMessageEntity>> =
        messageDao.getUnreadMessages()

    fun getUnreadCount(): Flow<Int> =
        messageDao.getUnreadCount()

    suspend fun saveMessage(msg: SignalRMessage) {
        messageDao.insert(
            IncomingMessageEntity(
                id = msg.id,
                sendBy = msg.sendBy,
                description = msg.description,
                sentAt = msg.sentAt,
                receivedAt = Instant.now().toString()
            )
        )
    }

    suspend fun markAsRead(messageId: String) =
        messageDao.markAsRead(messageId)

    suspend fun markAllAsRead() =
        messageDao.markAllAsRead()
}
