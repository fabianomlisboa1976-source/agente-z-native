package dev.mindmax.v4.data.repo

import dev.mindmax.v4.data.dao.TaskDao
import dev.mindmax.v4.data.entity.TaskEntity
import dev.mindmax.v4.data.entity.TaskStatus
import kotlinx.coroutines.flow.Flow
import java.util.Date

class TaskRepository(private val dao: TaskDao) {

    fun observeRecent(limit: Int = 50): Flow<List<TaskEntity>> = dao.observeRecent(limit)

    fun observePending(): Flow<List<TaskEntity>> = dao.observePending()

    suspend fun getById(id: String): TaskEntity? = dao.getById(id)

    suspend fun byStatus(status: TaskStatus): List<TaskEntity> = dao.byStatus(status)

    suspend fun upsert(task: TaskEntity) = dao.upsert(task)

    suspend fun markRunning(id: String, now: Date = Date()) = dao.markRunning(id, now)

    suspend fun markCompleted(id: String, result: String?, now: Date = Date()) =
        dao.markCompleted(id, now, result)

    suspend fun markFailed(id: String, error: String?, now: Date = Date()) =
        dao.markFailed(id, now, error)

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun deleteCompletedOlderThan(cutoff: Date) = dao.deleteCompletedOlderThan(cutoff)
}
