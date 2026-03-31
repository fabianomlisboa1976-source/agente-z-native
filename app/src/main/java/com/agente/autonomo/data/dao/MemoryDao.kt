package com.agente.autonomo.data.dao

import androidx.room.*
import com.agente.autonomo.data.entity.Memory
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface MemoryDao {
    
    @Query("SELECT * FROM memories ORDER BY importance DESC, createdAt DESC")
    fun getAllMemories(): Flow<List<Memory>>
    
    @Query("SELECT * FROM memories WHERE type = :type ORDER BY importance DESC, createdAt DESC")
    fun getMemoriesByType(type: String): Flow<List<Memory>>
    
    @Query("SELECT * FROM memories WHERE key = :key LIMIT 1")
    suspend fun getMemoryByKey(key: String): Memory?
    
    @Query("SELECT * FROM memories WHERE id = :memoryId LIMIT 1")
    suspend fun getMemoryById(memoryId: String): Memory?
    
    @Query("SELECT * FROM memories WHERE conversationId = :conversationId ORDER BY createdAt DESC")
    suspend fun getMemoriesByConversation(conversationId: String): List<Memory>
    
    @Query("SELECT * FROM memories WHERE value LIKE '%' || :query || '%' ORDER BY importance DESC LIMIT :limit")
    suspend fun searchMemories(query: String, limit: Int = 20): List<Memory>
    
    @Query("SELECT * FROM memories WHERE isArchived = 0 ORDER BY importance DESC, lastAccessed DESC LIMIT :limit")
    suspend fun getRecentMemories(limit: Int = 50): List<Memory>
    
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
    
    @Query("DELETE FROM memories WHERE type = :type")
    suspend fun deleteMemoriesByType(type: String)
    
    @Query("DELETE FROM memories")
    suspend fun deleteAllMemories()
    
    @Query("UPDATE memories SET lastAccessed = :timestamp, accessCount = accessCount + 1 WHERE id = :memoryId")
    suspend fun recordAccess(memoryId: String, timestamp: Date = Date())
    
    @Query("UPDATE memories SET importance = :importance WHERE id = :memoryId")
    suspend fun updateImportance(memoryId: String, importance: Int)
    
    @Query("UPDATE memories SET isArchived = 1 WHERE id = :memoryId")
    suspend fun archiveMemory(memoryId: String)
    
    @Query("SELECT COUNT(*) FROM memories")
    suspend fun getMemoryCount(): Int
    
    @Query("SELECT COUNT(*) FROM memories WHERE type = :type")
    suspend fun getMemoryCountByType(type: String): Int
    
    @Query("DELETE FROM memories WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun deleteExpiredMemories(now: Date = Date())
}
