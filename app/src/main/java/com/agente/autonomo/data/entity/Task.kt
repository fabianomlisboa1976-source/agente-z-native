package com.agente.autonomo.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.Date

/**
 * Entidade para tarefas agendadas ou pendentes
 */
@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "title")
    val title: String,
    
    @ColumnInfo(name = "description")
    val description: String? = null,
    
    @ColumnInfo(name = "type")
    val type: TaskType,
    
    @ColumnInfo(name = "status")
    val status: TaskStatus = TaskStatus.PENDING,
    
    @ColumnInfo(name = "priority")
    val priority: TaskPriority = TaskPriority.MEDIUM,
    
    @ColumnInfo(name = "assigned_agent")
    val assignedAgent: String? = null,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date(),
    
    @ColumnInfo(name = "scheduled_at")
    val scheduledAt: Date? = null,
    
    @ColumnInfo(name = "started_at")
    val startedAt: Date? = null,
    
    @ColumnInfo(name = "completed_at")
    val completedAt: Date? = null,
    
    @ColumnInfo(name = "due_date")
    val dueDate: Date? = null,
    
    @ColumnInfo(name = "parameters")
    val parameters: String? = null, // JSON com parâmetros
    
    @ColumnInfo(name = "result")
    val result: String? = null,
    
    @ColumnInfo(name = "error")
    val error: String? = null,
    
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,
    
    @ColumnInfo(name = "max_retries")
    val maxRetries: Int = 3,
    
    @ColumnInfo(name = "parent_task_id")
    val parentTaskId: String? = null,
    
    @ColumnInfo(name = "tags")
    val tags: String? = null, // JSON array de tags
    
    @ColumnInfo(name = "is_recurring")
    val isRecurring: Boolean = false,
    
    @ColumnInfo(name = "recurrence_rule")
    val recurrenceRule: String? = null
) {
    enum class TaskType {
        API_CALL,
        DATA_PROCESSING,
        NOTIFICATION,
        REMINDER,
        RESEARCH,
        COMMUNICATION,
        FILE_OPERATION,
        CUSTOM
    }
    
    enum class TaskStatus {
        PENDING,
        SCHEDULED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
        RETRYING
    }
    
    enum class TaskPriority {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
}
