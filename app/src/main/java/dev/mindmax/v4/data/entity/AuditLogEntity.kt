package dev.mindmax.v4.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "audit_logs",
    indices = [
        Index("timestamp"),
        Index("type"),
        Index("status"),
        Index("correlation_id"),
    ],
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Date,
    val type: AuditType,
    @ColumnInfo(name = "agent_id") val agentId: String? = null,
    @ColumnInfo(name = "agent_name") val agentName: String? = null,
    val action: String,
    val details: String? = null,
    @ColumnInfo(name = "input_data") val inputData: String? = null,
    @ColumnInfo(name = "output_data") val outputData: String? = null,
    val status: AuditStatus,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "correlation_id") val correlationId: String? = null,
    @ColumnInfo(name = "is_synced", defaultValue = "0") val isSynced: Boolean = false,
)

enum class AuditType {
    REQUEST, RESPONSE, ACTION, ERROR, SYSTEM, SECURITY, USER_ACTION, AGENT_DECISION,
}

enum class AuditStatus { SUCCESS, WARNING, ERROR, PENDING }
