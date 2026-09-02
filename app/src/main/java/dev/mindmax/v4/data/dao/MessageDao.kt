package dev.mindmax.v4.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.mindmax.v4.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: java.util.Date)

    @Query("DELETE FROM messages")
    suspend fun clear()

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC LIMIT :limit")
    suspend fun recentInConversation(conversationId: String, limit: Int): List<MessageEntity>

    @Query("SELECT DISTINCT conversation_id FROM messages ORDER BY timestamp DESC")
    fun observeConversationIds(): Flow<List<String>>

    @Query("UPDATE messages SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int
}
