package dev.mindmax.v4.data.repo

import dev.mindmax.v4.data.dao.AuditDao
import dev.mindmax.v4.data.entity.AuditLogEntity
import dev.mindmax.v4.data.entity.AuditStatus
import dev.mindmax.v4.data.entity.AuditType
import kotlinx.coroutines.flow.Flow
import java.util.Date

class AuditRepository(private val dao: AuditDao) {

    fun observeRecent(limit: Int = 200): Flow<List<AuditLogEntity>> =
        dao.observeRecent(limit)

    fun observeFiltered(
        type: AuditType? = null,
        status: AuditStatus? = null,
        limit: Int = 200,
    ): Flow<List<AuditLogEntity>> = dao.observeFiltered(type, status, limit)

    fun observeErrorsSince(since: Date): Flow<Int> = dao.observeErrorCountSince(since)

    suspend fun getById(id: Long): AuditLogEntity? = dao.getById(id)

    suspend fun byCorrelation(correlationId: String): List<AuditLogEntity> =
        dao.byCorrelation(correlationId)

    suspend fun insert(entry: AuditLogEntity): Long = dao.insert(entry)

    suspend fun pruneOlderThan(cutoff: Date) = dao.deleteOlderThan(cutoff)

    suspend fun clear() = dao.clear()
}
