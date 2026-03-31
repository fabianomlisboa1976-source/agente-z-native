package com.agente.autonomo.agent

import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.data.entity.Agent
import com.agente.autonomo.data.entity.Settings
import com.agente.autonomo.utils.AuditLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.Date

/**
 * Gerenciador de agentes - responsável por criar, configurar e recuperar agentes
 */
class AgentManager(
    private val database: AppDatabase,
    private val auditLogger: AuditLogger
) {
    
    companion object {
        const val TAG = "AgentManager"
    }
    
    /**
     * Obtém todos os agentes ativos
     */
    fun getAllActiveAgents(): Flow<List<Agent>> {
        return database.agentDao().getActiveAgents()
    }
    
    /**
     * Obtém todos os agentes
     */
    fun getAllAgents(): Flow<List<Agent>> {
        return database.agentDao().getAllAgents()
    }
    
    /**
     * Obtém um agente pelo ID
     */
    suspend fun getAgent(agentId: String): Agent? {
        return database.agentDao().getAgentById(agentId)
    }
    
    /**
     * Obtém o agente coordenador
     */
    suspend fun getCoordinatorAgent(): Agent? {
        return database.agentDao().getActiveAgentByType(Agent.AgentType.COORDINATOR)
            ?: database.agentDao().getAgentById("coordinator")
    }
    
    /**
     * Obtém agentes por tipo
     */
    suspend fun getAgentsByType(type: Agent.AgentType): List<Agent> {
        return database.agentDao().getAgentsByType(type).first()
    }
    
    /**
     * Obtém agentes por capacidade
     */
    suspend fun getAgentsByCapability(capability: String): List<Agent> {
        return database.agentDao().getAgentsByCapability(capability)
    }
    
    /**
     * Cria um novo agente personalizado
     */
    suspend fun createAgent(
        name: String,
        description: String,
        systemPrompt: String,
        type: Agent.AgentType = Agent.AgentType.CUSTOM,
        capabilities: List<String> = emptyList(),
        maxTokens: Int = 2048,
        temperature: Float = 0.7f,
        color: String = "#6366F1"
    ): Result<Agent> {
        return try {
            val agent = Agent(
                id = generateAgentId(name),
                name = name,
                description = description,
                type = type,
                systemPrompt = systemPrompt,
                isActive = true,
                capabilities = capabilities.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" },
                maxTokens = maxTokens,
                temperature = temperature,
                color = color
            )
            
            database.agentDao().insertAgent(agent)
            
            auditLogger.logAction(
                action = "Agente criado",
                agentId = agent.id,
                agentName = agent.name,
                details = "Tipo: ${type.name}"
            )
            
            Result.success(agent)
        } catch (e: Exception) {
            auditLogger.logError(
                action = "Criar agente",
                error = e.message ?: "Erro desconhecido"
            )
            Result.failure(e)
        }
    }
    
    /**
     * Atualiza um agente existente
     */
    suspend fun updateAgent(agent: Agent): Result<Agent> {
        return try {
            val updatedAgent = agent.copy(updatedAt = Date())
            database.agentDao().updateAgent(updatedAgent)
            
            auditLogger.logAction(
                action = "Agente atualizado",
                agentId = agent.id,
                agentName = agent.name
            )
            
            Result.success(updatedAgent)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Atualiza o prompt de sistema de um agente
     */
    suspend fun updateAgentPrompt(agentId: String, newPrompt: String): Result<Unit> {
        return try {
            database.agentDao().updateAgentPrompt(agentId, newPrompt)
            
            auditLogger.logAction(
                action = "Prompt atualizado",
                agentId = agentId
            )
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Ativa/desativa um agente
     */
    suspend fun setAgentActive(agentId: String, active: Boolean): Result<Unit> {
        return try {
            database.agentDao().setAgentActive(agentId, active)
            
            auditLogger.logAction(
                action = if (active) "Agente ativado" else "Agente desativado",
                agentId = agentId
            )
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Exclui um agente
     */
    suspend fun deleteAgent(agentId: String): Result<Unit> {
        return try {
            database.agentDao().deleteAgentById(agentId)
            
            auditLogger.logAction(
                action = "Agente excluído",
                agentId = agentId
            )
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Registra o uso de um agente
     */
    suspend fun recordAgentUsage(agentId: String) {
        database.agentDao().updateAgentUsage(agentId)
    }
    
    /**
     * Obtém os agentes mais utilizados
     */
    suspend fun getMostUsedAgents(limit: Int = 5): List<Agent> {
        return database.agentDao().getMostUsedAgents(limit)
    }
    
    /**
     * Reseta todos os agentes para o padrão
     */
    suspend fun resetToDefaults(): Result<Unit> {
        return try {
            database.agentDao().deleteAllAgents()
            // Os agentes padrão serão recriados pelo callback do database
            
            auditLogger.logSystem("Agentes resetados para padrão")
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun generateAgentId(name: String): String {
        return name.lowercase()
            .replace(" ", "_")
            .replace(Regex("[^a-z0-9_]"), "") + "_" + System.currentTimeMillis()
    }
}
