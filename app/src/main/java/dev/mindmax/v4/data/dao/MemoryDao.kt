package dev.mindmax.v4.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.mindmax.v4.data.entity.MemoryEntity
import dev.mindmax.v4.data.entity.MemoryType
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM memories WHERE is_archived = 1 OR (expires_at IS NOT NULL AND expires_at < :now)")
    suspend fun purgeExpired(now: java.util.Date)

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE `key` = :key AND is_archived = 0 LIMIT 1")
    suspend fun findByKey(key: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE type = :type AND is_archived = 0 ORDER BY importance DESC, updated_at DESC")
    fun observeByType(type: MemoryType): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE is_archived = 0 ORDER BY importance DESC, updated_at DESC LIMIT :limit")
    suspend fun topN(limit: Int): List<MemoryEntity>

    @Query("UPDATE memories SET access_count = access_count + 1, last_accessed = :now WHERE id = :id")
    suspend fun bumpAccess(id: String, now: java.util.Date)

    @Query(
        "SELECT * FROM memories WHERE is_archived = 0 AND " +
            "(category = :category OR category LIKE :categoryPrefix) " +
            "ORDER BY importance DESC LIMIT :limit"
    )
    suspend fun byCategory(category: String, categoryPrefix: String, limit: Int): List<MemoryEntity>
}
