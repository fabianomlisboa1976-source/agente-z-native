package com.agente.autonomo.data.dao

import androidx.room.*
import com.agente.autonomo.data.entity.AuditLog
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface AuditLogDao {
    
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<AuditLog>>
    
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int): List<AuditLog>
    
    @Query("SELECT * FROM audit_logs WHERE type = :type ORDER BY timestamp DESC")
    fun getLogsByType(type: AuditLog.AuditType): Flow<List<AuditLog>>
    
    @Query("SELECT * FROM audit_logs WHERE agent_id = :agentId ORDER BY timestamp DESC")
    fun getLogsByAgent(agentId: String): Flow<List<AuditLog>>
    
    @Query("SELECT * FROM audit_logs WHERE status = :status ORDER BY timestamp DESC")
    fun getLogsByStatus(status: AuditLog.Status): Flow<List<AuditLog>>
    
    @Query("SELECT * FROM audit_logs WHERE timestamp BETWEEN :startDate AND :endDate ORDER BY timestamp DESC")
    suspend fun getLogsBetweenDates(startDate: Date, endDate: Date): List<AuditLog>
    
    @Query("SELECT * FROM audit_logs WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getLogsSince(since: Date): List<AuditLog>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AuditLog): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<AuditLog>)
    
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
    
    @Query("SELECT COUNT(*) FROM audit_logs WHERE type = :type")
    suspend fun getLogCountByType(type: AuditLog.AuditType): Int
    
    @Query("SELECT COUNT(*) FROM audit_logs WHERE status = :status")
    suspend fun getLogCountByStatus(status: AuditLog.Status): Int
    
    @Query("SELECT * FROM audit_logs WHERE correlation_id = :correlationId ORDER BY timestamp ASC")
    suspend fun getLogsByCorrelationId(correlationId: String): List<AuditLog>
    
    @Query("UPDATE audit_logs SET is_synced = 1 WHERE id = :logId")
    suspend fun markAsSynced(logId: Long)
    
    @Query("SELECT * FROM audit_logs WHERE is_synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedLogs(): List<AuditLog>
    
    @Query("SELECT * FROM audit_logs WHERE action LIKE '%' || :query || '%' OR details LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchLogs(query: String): List<AuditLog>
    
    @Query("SELECT AVG(duration_ms) FROM audit_logs WHERE duration_ms IS NOT NULL")
    suspend fun getAverageDuration(): Double?
}
