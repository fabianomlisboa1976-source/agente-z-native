package com.agente.autonomo.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.Date

/**
 * Entidade que representa um agente autônomo
 */
@Entity(tableName = "agents")
data class Agent(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "name")
    val name: String,
    
    @ColumnInfo(name = "description")
    val description: String,
    
    @ColumnInfo(name = "type")
    val type: AgentType,
    
    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String,
    
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    
    @ColumnInfo(name = "priority")
    val priority: Int = 0,
    
    @ColumnInfo(name = "capabilities")
    val capabilities: String = "", // JSON array de capacidades
    
    @ColumnInfo(name = "max_tokens")
    val maxTokens: Int = 2048,
    
    @ColumnInfo(name = "temperature")
    val temperature: Float = 0.7f,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date(),
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Date = Date(),
    
    @ColumnInfo(name = "last_used")
    val lastUsed: Date? = null,
    
    @ColumnInfo(name = "usage_count")
    val usageCount: Int = 0,
    
    @ColumnInfo(name = "color")
    val color: String = "#6366F1"
) {
    enum class AgentType {
        COORDINATOR,    // Coordena outros agentes
        PLANNER,        // Planejamento e organização
        RESEARCHER,     // Pesquisa e busca de informações
        EXECUTOR,       // Execução de tarefas
        AUDITOR,        // Auditoria e verificação
        MEMORY,         // Gerenciamento de memória
        COMMUNICATION,  // Comunicação externa
        CUSTOM          // Agente personalizado
    }
}
