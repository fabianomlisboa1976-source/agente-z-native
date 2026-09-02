package dev.mindmax.v4.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.mindmax.v4.data.entity.AgentEntity
import dev.mindmax.v4.data.entity.AgentType
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(agent: AgentEntity)

    @Update
    suspend fun update(agent: AgentEntity)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM agents WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AgentEntity?

    @Query("SELECT * FROM agents ORDER BY priority DESC, name ASC")
    fun observeAll(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE is_active = 1 ORDER BY priority DESC, name ASC")
    suspend fun activeAgents(): List<AgentEntity>

    @Query("SELECT * FROM agents WHERE type = :type ORDER BY name ASC")
    suspend fun byType(type: AgentType): List<AgentEntity>

    @Query("UPDATE agents SET is_active = :active WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean)

    @Query("UPDATE agents SET last_used = :now, usage_count = usage_count + 1 WHERE id = :id")
    suspend fun bumpUsage(id: String, now: java.util.Date)

    @Query("SELECT COUNT(*) FROM agents")
    suspend fun count(): Int
}
