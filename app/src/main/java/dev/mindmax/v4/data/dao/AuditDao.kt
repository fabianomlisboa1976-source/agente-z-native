package dev.mindmax.v4.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.mindmax.v4.data.entity.AuditLogEntity
import dev.mindmax.v4.data.entity.AuditStatus
import dev.mindmax.v4.data.entity.AuditType
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AuditLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<AuditLogEntity>): List<Long>

    @Query("DELETE FROM audit_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM audit_logs WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: java.util.Date)

    @Query("DELETE FROM audit_logs")
    suspend fun clear()

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditLogEntity>>

    @Query(
        "SELECT * FROM audit_logs " +
            "WHERE (:type IS NULL OR type = :type) AND (:status IS NULL OR status = :status) " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    fun observeFiltered(type: AuditType?, status: AuditStatus?, limit: Int): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): AuditLogEntity?

    @Query("SELECT * FROM audit_logs WHERE correlation_id = :correlationId ORDER BY timestamp ASC")
    suspend fun byCorrelation(correlationId: String): List<AuditLogEntity>

    @Query("UPDATE audit_logs SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM audit_logs WHERE timestamp >= :since")
    fun observeCountSince(since: java.util.Date): Flow<Int>

    @Query("SELECT COUNT(*) FROM audit_logs WHERE status = 'ERROR' AND timestamp >= :since")
    fun observeErrorCountSince(since: java.util.Date): Flow<Int>
}
