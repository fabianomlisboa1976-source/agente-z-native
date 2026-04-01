package com.agente.autonomo.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.Date

/**
 * Entidade para logs de auditoria
 */
@Entity(tableName = "audit_logs")
data class AuditLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Date = Date(),
    
    @ColumnInfo(name = "type")
    val type: AuditType,
    
    @ColumnInfo(name = "agent_id")
    val agentId: String? = null,
    
    @ColumnInfo(name = "agent_name")
    val agentName: String? = null,
    
    @ColumnInfo(name = "action")
    val action: String,
    
    @ColumnInfo(name = "details")
    val details: String? = null,
    
    @ColumnInfo(name = "input_data")
    val inputData: String? = null,
    
    @ColumnInfo(name = "output_data")
    val outputData: String? = null,
    
    @ColumnInfo(name = "status")
    val status: Status = Status.SUCCESS,
    
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,
    
    @ColumnInfo(name = "correlation_id")
    val correlationId: String? = null,
    
    @ColumnInfo(name = "is_synced")
    val isSynced: Boolean = false
) {
    enum class AuditType {
        REQUEST,        // Requisição para API
        RESPONSE,       // Resposta da API
        ACTION,         // Ação executada
        ERROR,          // Erro ocorrido
        SYSTEM,         // Evento do sistema
        SECURITY,       // Evento de segurança
        USER_ACTION,    // Ação do usuário
        AGENT_DECISION  // Decisão de agente
    }
    
    enum class Status {
        SUCCESS,
        WARNING,
        ERROR,
        PENDING
    }
}
