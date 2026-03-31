package com.agente.autonomo.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.Date

@Entity(tableName = "audit_logs")
data class AuditLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Date = Date(),
    
    @ColumnInfo(name = "type")
    val type: String = "ACTION",
    
    @ColumnInfo(name = "agentId")
    val agentId: String? = null,
    
    @ColumnInfo(name = "agentName")
    val agentName: String? = null,
    
    @ColumnInfo(name = "action")
    val action: String,
    
    @ColumnInfo(name = "details")
    val details: String? = null,
    
    @ColumnInfo(name = "inputData")
    val inputData: String? = null,
    
    @ColumnInfo(name = "outputData")
    val outputData: String? = null,
    
    @ColumnInfo(name = "status")
    val status: String = "SUCCESS",
    
    @ColumnInfo(name = "errorMessage")
    val errorMessage: String? = null,
    
    @ColumnInfo(name = "durationMs")
    val durationMs: Long? = null,
    
    @ColumnInfo(name = "correlationId")
    val correlationId: String? = null,
    
    @ColumnInfo(name = "isSynced")
    val isSynced: Boolean = false
)
