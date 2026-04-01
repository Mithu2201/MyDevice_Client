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
    data class MessageSyncResult(
        val newCount: Int,
        val latestPreview: String?
    )

    fun getAllMessages(): Flow<List<IncomingMessageEntity>> =
        messageDao.getAllMessages()

    fun getUnreadMessages(): Flow<List<IncomingMessageEntity>> =
        messageDao.getUnreadMessages()

    fun getUnreadCount(): Flow<Int> =
        messageDao.getUnreadCount()

    suspend fun saveMessage(msg: SignalRMessage) {
        val normalized = msg.normalized()
        messageDao.insert(
            IncomingMessageEntity(
                id = normalized.id.toString(),
                sendBy = normalized.sendBy,
                description = normalized.description,
                sentAt = normalized.sentAt,
                receivedAt = normalized.receivedAt
            )
        )
    }

    suspend fun markAsRead(messageId: String) =
        messageDao.markAsRead(messageId)

    suspend fun markAllAsRead() =
        messageDao.markAllAsRead()

    /**
     * REST fallback for environments where SignalR push is unreliable.
     * The backend endpoint fetches pending messages by device macAddress/local ID.
     * The response shape is not strongly documented, so this parser accepts
     * either a bare array or a wrapped object with data/items/results.
     */
    suspend fun syncMessages(macAddress: String): NetworkResult<MessageSyncResult> {
        if (macAddress.isBlank()) return NetworkResult.Success(MessageSyncResult(0, null))

        return safeApiCall {
            val raw = api.getDeviceMessagesRaw(macAddress)
            val messages = parseMessages(raw)
            var newCount = 0
            var latestPreview: String? = null
            messages.forEach { message ->
                val entity = message.toEntity()
                val exists = messageDao.exists(entity.id)
                messageDao.insert(entity)
                if (!exists) {
                    newCount++
                    if (latestPreview.isNullOrBlank()) {
                        latestPreview = entity.description.takeIf { it.isNotBlank() }
                    }
                }
            }
            MessageSyncResult(newCount = newCount, latestPreview = latestPreview)
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
                sendBy = item.optNullableString("sendBy"),
                description = item.optNullableString("description"),
                isSent = item.optBoolean("isSent", false),
                sentAt = item.optNullableString("sentAt"),
                receivedAt = item.optNullableString("receivedAt")
            ).normalized()
        }
        return messages
    }

    private fun SignalRMessage.toEntity(): IncomingMessageEntity {
        val normalized = normalized()
        return IncomingMessageEntity(
            id = normalized.id.toString(),
            sendBy = normalized.sendBy,
            description = normalized.description,
            sentAt = normalized.sentAt,
            receivedAt = normalized.receivedAt
        )
    }

    private fun SignalRMessage.normalized(): SignalRMessage {
        val normalizedSentAt = sentAt.cleanTimestamp().ifBlank { Instant.now().toString() }
        val normalizedReceivedAt = receivedAt.cleanTimestamp().ifBlank { normalizedSentAt }
        return copy(
            sendBy = sendBy.cleanText().ifBlank { "Admin" },
            description = description.cleanText(),
            sentAt = normalizedSentAt,
            receivedAt = normalizedReceivedAt
        )
    }

    private fun String.cleanText(): String {
        val value = trim()
        return if (value.equals("null", ignoreCase = true)) "" else value
    }

    private fun String.cleanTimestamp(): String {
        val value = cleanText()
        return if (value.startsWith("0001-01-01T00:00:00")) "" else value
    }

    private fun JSONObject.optNullableString(key: String): String =
        if (!has(key) || isNull(key)) "" else optString(key, "")
}
