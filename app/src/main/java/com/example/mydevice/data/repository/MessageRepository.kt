package com.example.mydevice.data.repository

import com.example.mydevice.data.remote.api.MyDevicesApi
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.remote.api.safeApiCall
import com.example.mydevice.data.local.database.dao.IncomingMessageDao
import com.example.mydevice.data.local.database.entity.IncomingMessageEntity
import com.example.mydevice.data.remote.signalr.SignalRMessage
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Handles incoming messages from SignalR → local Room storage.
 *
 * Messages arrive via SignalR, get saved to Room, and the UI observes
 * them as a Flow. Acknowledgement is sent back via SignalR immediately.
 */
class MessageRepository(
    private val messageDao: IncomingMessageDao,
    private val api: MyDevicesApi
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
                id = msg.id.toString(),
                sendBy = msg.sendBy,
                description = msg.description,
                sentAt = msg.sentAt.ifBlank { Instant.now().toString() },
                receivedAt = msg.receivedAt.ifBlank { Instant.now().toString() }
            )
        )
    }

    suspend fun markAsRead(messageId: String) =
        messageDao.markAsRead(messageId)

    suspend fun markAllAsRead() =
        messageDao.markAllAsRead()

    /**
     * REST fallback for environments where SignalR push is unreliable.
     * The backend response shape is not strongly documented, so this parser
     * accepts either a bare array or a wrapped object with data/items/results.
     */
    suspend fun syncMessages(deviceServerId: Int): NetworkResult<Int> {
        if (deviceServerId <= 0) return NetworkResult.Success(0)

        return safeApiCall {
            val raw = api.getMessagesRaw(deviceServerId)
            val messages = parseMessages(raw)
            messages.forEach { messageDao.insert(it.toEntity()) }
            messages.size
        }
    }

    private fun parseMessages(raw: String): List<SignalRMessage> {
        return try {
            when {
                raw.trim().startsWith("[") -> {
                    parseMessageArray(JSONArray(raw))
                }
                raw.trim().startsWith("{") -> {
                    val root = JSONObject(raw)
                    when {
                        root.has("data") && root.opt("data") is JSONArray ->
                            parseMessageArray(root.getJSONArray("data"))
                        root.has("items") && root.opt("items") is JSONArray ->
                            parseMessageArray(root.getJSONArray("items"))
                        root.has("results") && root.opt("results") is JSONArray ->
                            parseMessageArray(root.getJSONArray("results"))
                        root.has("data") && root.opt("data") is JSONObject -> {
                            val data = root.getJSONObject("data")
                            when {
                                data.has("items") && data.opt("items") is JSONArray ->
                                    parseMessageArray(data.getJSONArray("items"))
                                data.has("results") && data.opt("results") is JSONArray ->
                                    parseMessageArray(data.getJSONArray("results"))
                                else -> emptyList()
                            }
                        }
                        else -> emptyList()
                    }
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseMessageArray(array: JSONArray): List<SignalRMessage> {
        val messages = mutableListOf<SignalRMessage>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            messages += SignalRMessage(
                id = item.optInt("id", 0),
                mobileStatusId = item.optInt("mobileStatusId", 0),
                sendBy = item.optString("sendBy", ""),
                description = item.optString("description", ""),
                isSent = item.optBoolean("isSent", false),
                sentAt = item.optString("sentAt", ""),
                receivedAt = item.optString("receivedAt", "")
            )
        }
        return messages
    }

    private fun SignalRMessage.toEntity(): IncomingMessageEntity {
        return IncomingMessageEntity(
            id = id.toString(),
            sendBy = sendBy,
            description = description,
            sentAt = sentAt.ifBlank { Instant.now().toString() },
            receivedAt = receivedAt.ifBlank { Instant.now().toString() }
        )
    }
}
