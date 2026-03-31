package com.agente.autonomo.data.dao

import androidx.room.*
import com.agente.autonomo.data.entity.Memory
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface MemoryDao {
    
    @Query("SELECT * FROM memories WHERE is_archived = 0 ORDER BY importance DESC, updated_at DESC")
    fun getAllActiveMemories(): Flow<List<Memory>>
    
    @Query("SELECT * FROM memories ORDER BY updated_at DESC")
    fun getAllMemories(): Flow<List<Memory>>
    
    @Query("SELECT * FROM memories WHERE id = :memoryId LIMIT 1")
    suspend fun getMemoryById(memoryId: String): Memory?
    
    @Query("SELECT * FROM memories WHERE `key` = :key AND is_archived = 0 LIMIT 1")
    suspend fun getMemoryByKey(key: String): Memory?
    
    @Query("SELECT * FROM memories WHERE type = :type AND is_archived = 0 ORDER BY importance DESC")
    fun getMemoriesByType(type: Memory.MemoryType): Flow<List<Memory>>
    
    @Query("SELECT * FROM memories WHERE category = :category AND is_archived = 0 ORDER BY updated_at DESC")
    fun getMemoriesByCategory(category: String): Flow<List<Memory>>
    
    @Query("SELECT * FROM memories WHERE source_agent = :agentId ORDER BY updated_at DESC")
    fun getMemoriesByAgent(agentId: String): Flow<List<Memory>>
    
    @Query("SELECT * FROM memories WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    suspend fun getMemoriesByConversation(conversationId: String): List<Memory>
    
    @Query("SELECT * FROM memories WHERE value LIKE '%' || :query || '%' AND is_archived = 0 ORDER BY importance DESC")
    suspend fun searchMemories(query: String): List<Memory>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: Memory)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemories(memories: List<Memory>)
    
    @Update
    suspend fun updateMemory(memory: Memory)
    
    @Delete
    suspend fun deleteMemory(memory: Memory)
    
    @Query("DELETE FROM memories WHERE id = :memoryId")
    suspend fun deleteMemoryById(memoryId: String)
    
    @Query("UPDATE memories SET is_archived = 1 WHERE id = :memoryId")
    suspend fun archiveMemory(memoryId: String)
    
    @Query("UPDATE memories SET is_archived = 0 WHERE id = :memoryId")
    suspend fun unarchiveMemory(memoryId: String)
    
    @Query("DELETE FROM memories WHERE is_archived = 1")
    suspend fun deleteArchivedMemories()
    
    @Query("DELETE FROM memories WHERE expires_at IS NOT NULL AND expires_at < :now")
    suspend fun deleteExpiredMemories(now: Date = Date())
    
    @Query("UPDATE memories SET last_accessed = :now, access_count = access_count + 1 WHERE id = :memoryId")
    suspend fun updateAccess(memoryId: String, now: Date = Date())
    
    @Query("SELECT COUNT(*) FROM memories WHERE is_archived = 0")
    suspend fun getActiveMemoryCount(): Int
    
    @Query("SELECT COUNT(*) FROM memories")
    suspend fun getTotalMemoryCount(): Int
    
    @Query("SELECT * FROM memories WHERE importance >= :minImportance AND is_archived = 0 ORDER BY importance DESC LIMIT :limit")
    suspend fun getImportantMemories(minImportance: Int, limit: Int): List<Memory>
    
    @Query("SELECT * FROM memories WHERE updated_at > :since ORDER BY updated_at DESC")
    suspend fun getRecentlyUpdatedMemories(since: Date): List<Memory>
    
    @Query("SELECT * FROM memories WHERE access_count >= :minAccessCount ORDER BY access_count DESC LIMIT :limit")
    suspend fun getMostAccessedMemories(minAccessCount: Int, limit: Int): List<Memory>
    
    @Query("DELETE FROM memories")
    suspend fun deleteAllMemories()
}
