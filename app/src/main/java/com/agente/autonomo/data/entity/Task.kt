package com.agente.autonomo.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.Date

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "userId")
    val userId: String? = null,
    
    @ColumnInfo(name = "title")
    val title: String,
    
    @ColumnInfo(name = "description")
    val description: String? = null,
    
    @ColumnInfo(name = "type")
    val type: String = "CUSTOM",
    
    @ColumnInfo(name = "status")
    val status: String = "PENDING",
    
    @ColumnInfo(name = "priority")
    val priority: String = "MEDIUM",
    
    @ColumnInfo(name = "assignedAgent")
    val assignedAgent: String? = null,
    
    @ColumnInfo(name = "createdAt")
    val createdAt: Date = Date(),
    
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Date = Date(),
    
    @ColumnInfo(name = "scheduledAt")
    val scheduledAt: Date? = null,
    
    @ColumnInfo(name = "startedAt")
    val startedAt: Date? = null,
    
    @ColumnInfo(name = "completedAt")
    val completedAt: Date? = null,
    
    @ColumnInfo(name = "dueDate")
    val dueDate: Date? = null,
    
    @ColumnInfo(name = "parameters")
    val parameters: String? = null,
    
    @ColumnInfo(name = "result")
    val result: String? = null,
    
    @ColumnInfo(name = "error")
    val error: String? = null,
    
    @ColumnInfo(name = "retryCount")
    val retryCount: Int = 0,
    
    @ColumnInfo(name = "maxRetries")
    val maxRetries: Int = 3,
    
    @ColumnInfo(name = "parentTaskId")
    val parentTaskId: String? = null,
    
    @ColumnInfo(name = "tags")
    val tags: String? = null,
    
    @ColumnInfo(name = "category")
    val category: String? = null,
    
    @ColumnInfo(name = "isRecurring")
    val isRecurring: Boolean = false,
    
    @ColumnInfo(name = "recurrenceRule")
    val recurrenceRule: String? = null
)
