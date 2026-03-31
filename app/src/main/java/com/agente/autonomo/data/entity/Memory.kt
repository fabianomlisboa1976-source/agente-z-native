package com.agente.autonomo.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.Date

/**
 * Entidade para memória de longo prazo do sistema
 */
@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "type")
    val type: MemoryType,
    
    @ColumnInfo(name = "key")
    val key: String,
    
    @ColumnInfo(name = "value")
    val value: String,
    
    @ColumnInfo(name = "category")
    val category: String? = null,
    
    @ColumnInfo(name = "importance")
    val importance: Int = 5, // 1-10
    
    @ColumnInfo(name = "source_agent")
    val sourceAgent: String? = null,
    
    @ColumnInfo(name = "conversation_id")
    val conversationId: String? = null,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date(),
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Date = Date(),
    
    @ColumnInfo(name = "last_accessed")
    val lastAccessed: Date? = null,
    
    @ColumnInfo(name = "access_count")
    val accessCount: Int = 0,
    
    @ColumnInfo(name = "expires_at")
    val expiresAt: Date? = null,
    
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,
    
    @ColumnInfo(name = "metadata")
    val metadata: String? = null // JSON com metadados adicionais
) {
    enum class MemoryType {
        FACT,           // Fato conhecido
        PREFERENCE,     // Preferência do usuário
        CONTEXT,        // Contexto de conversa
        TASK_RESULT,    // Resultado de tarefa
        LEARNED,        // Aprendizado do sistema
        USER_PROFILE,   // Perfil do usuário
        SYSTEM_STATE    // Estado do sistema
    }
}
