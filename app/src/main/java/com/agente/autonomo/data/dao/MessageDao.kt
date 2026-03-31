package com.agente.autonomo.data.dao

import androidx.room.*
import com.agente.autonomo.data.entity.Message
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface MessageDao {
    
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<Message>>
    
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    fun getMessagesByConversation(conversationId: String): Flow<List<Message>>
    
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(conversationId: String, limit: Int): List<Message>
    
    @Query("SELECT * FROM messages WHERE agent_id = :agentId ORDER BY timestamp DESC")
    fun getMessagesByAgent(agentId: String): Flow<List<Message>>
    
    @Query("SELECT * FROM messages WHERE sender_type = :senderType ORDER BY timestamp DESC")
    fun getMessagesBySender(senderType: Message.SenderType): Flow<List<Message>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<Message>)
    
    @Update
    suspend fun updateMessage(message: Message)
    
    @Delete
    suspend fun deleteMessage(message: Message)
    
    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Long)
    
    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteMessagesByConversation(conversationId: String)
    
    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
    
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Int
    
    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun getMessageCountByConversation(conversationId: String): Int
    
    @Query("SELECT * FROM messages WHERE timestamp > :since ORDER BY timestamp ASC")
    suspend fun getMessagesSince(since: Date): List<Message>
    
    @Query("UPDATE messages SET is_synced = 1 WHERE id = :messageId")
    suspend fun markAsSynced(messageId: Long)
    
    @Query("SELECT * FROM messages WHERE is_synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedMessages(): List<Message>
    
    @Query("DELETE FROM messages WHERE timestamp < :before")
    suspend fun deleteMessagesBefore(before: Date)
    
    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchMessages(query: String): List<Message>
}
