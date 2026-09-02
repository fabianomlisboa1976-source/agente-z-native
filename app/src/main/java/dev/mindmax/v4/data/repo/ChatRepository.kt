package dev.mindmax.v4.data.repo

import dev.mindmax.v4.data.dao.MessageDao
import dev.mindmax.v4.data.entity.MessageEntity
import dev.mindmax.v4.data.entity.SenderType
import kotlinx.coroutines.flow.Flow
import java.util.Date

/**
 * Persists chat history. Messages are scoped to a `conversationId` (string) so the
 * UI can support multiple conversations later without restructuring the table.
 */
class ChatRepository(private val dao: MessageDao) {

    fun observeConversation(conversationId: String): Flow<List<MessageEntity>> =
        dao.observeConversation(conversationId)

    fun observeAllConversations(): Flow<List<String>> = dao.observeConversationIds()

    suspend fun recentInConversation(
        conversationId: String,
        limit: Int = 20,
    ): List<MessageEntity> = dao.recentInConversation(conversationId, limit)

    suspend fun appendUserMessage(
        conversationId: String,
        content: String,
        now: Date = Date(),
    ): MessageEntity = MessageEntity(
        conversationId = conversationId,
        senderType = SenderType.USER,
        content = content,
        timestamp = now,
    ).also { dao.insert(it) }

    suspend fun appendAgentMessage(
        conversationId: String,
        content: String,
        agentId: String? = null,
        agentName: String? = null,
        tokensUsed: Int? = null,
        now: Date = Date(),
    ): MessageEntity = MessageEntity(
        conversationId = conversationId,
        senderType = SenderType.AGENT,
        agentId = agentId,
        agentName = agentName,
        content = content,
        timestamp = now,
        tokensUsed = tokensUsed,
    ).also { dao.insert(it) }

    suspend fun appendSystemMessage(
        conversationId: String,
        content: String,
        now: Date = Date(),
    ): MessageEntity = MessageEntity(
        conversationId = conversationId,
        senderType = SenderType.SYSTEM,
        content = content,
        timestamp = now,
    ).also { dao.insert(it) }

    suspend fun deleteConversation(conversationId: String) = dao.deleteConversation(conversationId)

    suspend fun pruneOlderThan(cutoff: Date) = dao.deleteOlderThan(cutoff)
}
