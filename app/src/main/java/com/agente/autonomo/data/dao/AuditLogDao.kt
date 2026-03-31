package com.agente.autonomo.data.dao

import androidx.room.*
import com.agente.autonomo.data.entity.AuditLog
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface AuditLogDao {
    
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<AuditLog>>
    
    @Query("SELECT * FROM audit_logs WHERE type = :type ORDER BY timestamp DESC")
    fun getLogsByType(type: String): Flow<List<AuditLog>>
    
    @Query("SELECT * FROM audit_logs WHERE status = :status ORDER BY timestamp DESC")
    fun getLogsByStatus(status: String): Flow<List<AuditLog>>
    
    @Query("SELECT * FROM audit_logs WHERE agentId = :agentId ORDER BY timestamp DESC")
    fun getLogsByAgent(agentId: String): Flow<List<AuditLog>>
    
    @Query("SELECT * FROM audit_logs WHERE correlationId = :correlationId ORDER BY timestamp ASC")
    suspend fun getLogsByCorrelation(correlationId: String): List<AuditLog>
    
    @Query("SELECT * FROM audit_logs WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun getLogsSince(since: Date): Flow<List<AuditLog>>
    
    @Query("SELECT * FROM audit_logs WHERE timestamp >= :since AND timestamp <= :until ORDER BY timestamp DESC")
    fun getLogsBetween(since: Date, until: Date): Flow<List<AuditLog>>
    
    @Insert
    suspend fun insertLog(log: AuditLog): Long
    
    @Insert
    suspend fun insertLogs(logs: List<AuditLog>)
    
    @Update
    suspend fun updateLog(log: AuditLog)
    
    @Delete
    suspend fun deleteLog(log: AuditLog)
    
    @Query("DELETE FROM audit_logs WHERE id = :logId")
    suspend fun deleteLogById(logId: Long)
    
    @Query("DELETE FROM audit_logs WHERE timestamp < :before")
    suspend fun deleteLogsBefore(before: Date)
    
    @Query("DELETE FROM audit_logs")
    suspend fun deleteAllLogs()
    
    @Query("SELECT COUNT(*) FROM audit_logs")
    suspend fun getLogCount(): Int
    
    @Query("SELECT COUNT(*) FROM audit_logs WHERE status = 'ERROR'")
    suspend fun getErrorCount(): Int
    
    @Query("UPDATE audit_logs SET isSynced = 1 WHERE id = :logId")
    suspend fun markAsSynced(logId: Long)
    
    @Query("SELECT * FROM audit_logs WHERE isSynced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedLogs(): List<AuditLog>
}
