package com.agente.autonomo.data.dao

import androidx.room.*
import com.agente.autonomo.data.entity.Agent
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface AgentDao {
    
    @Query("SELECT * FROM agents ORDER BY priority DESC, name ASC")
    fun getAllAgents(): Flow<List<Agent>>
    
    @Query("SELECT * FROM agents WHERE is_active = 1 ORDER BY priority DESC, name ASC")
    fun getActiveAgents(): Flow<List<Agent>>
    
    @Query("SELECT * FROM agents WHERE type = :type ORDER BY priority DESC")
    fun getAgentsByType(type: Agent.AgentType): Flow<List<Agent>>
    
    @Query("SELECT * FROM agents WHERE id = :agentId LIMIT 1")
    suspend fun getAgentById(agentId: String): Agent?
    
    @Query("SELECT * FROM agents WHERE type = :type AND is_active = 1 LIMIT 1")
    suspend fun getActiveAgentByType(type: Agent.AgentType): Agent?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgent(agent: Agent)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgents(agents: List<Agent>)
    
    @Update
    suspend fun updateAgent(agent: Agent)
    
    @Delete
    suspend fun deleteAgent(agent: Agent)
    
    @Query("DELETE FROM agents WHERE id = :agentId")
    suspend fun deleteAgentById(agentId: String)
    
    @Query("DELETE FROM agents")
    suspend fun deleteAllAgents()
    
    @Query("UPDATE agents SET is_active = :isActive WHERE id = :agentId")
    suspend fun setAgentActive(agentId: String, isActive: Boolean)
    
    @Query("UPDATE agents SET last_used = :timestamp, usage_count = usage_count + 1 WHERE id = :agentId")
    suspend fun updateAgentUsage(agentId: String, timestamp: Date = Date())
    
    @Query("UPDATE agents SET system_prompt = :prompt WHERE id = :agentId")
    suspend fun updateAgentPrompt(agentId: String, prompt: String)
    
    @Query("SELECT COUNT(*) FROM agents")
    suspend fun getAgentCount(): Int
    
    @Query("SELECT COUNT(*) FROM agents WHERE is_active = 1")
    suspend fun getActiveAgentCount(): Int
    
    @Query("SELECT * FROM agents ORDER BY usage_count DESC LIMIT :limit")
    suspend fun getMostUsedAgents(limit: Int): List<Agent>
    
    @Query("SELECT * FROM agents WHERE capabilities LIKE '%' || :capability || '%'")
    suspend fun getAgentsByCapability(capability: String): List<Agent>
}
