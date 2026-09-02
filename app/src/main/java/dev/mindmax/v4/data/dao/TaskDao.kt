package dev.mindmax.v4.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.mindmax.v4.data.entity.TaskEntity
import dev.mindmax.v4.data.entity.TaskStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status = :status ORDER BY priority DESC, created_at ASC")
    suspend fun byStatus(status: TaskStatus): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status = 'PENDING' OR status = 'SCHEDULED' OR status = 'RETRYING'")
    fun observePending(): Flow<List<TaskEntity>>

    @Query("UPDATE tasks SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: TaskStatus)

    @Query("UPDATE tasks SET status = 'RUNNING', started_at = :startedAt WHERE id = :id")
    suspend fun markRunning(id: String, startedAt: java.util.Date)

    @Query(
        "UPDATE tasks SET status = 'COMPLETED', completed_at = :completedAt, result = :result WHERE id = :id"
    )
    suspend fun markCompleted(id: String, completedAt: java.util.Date, result: String?)

    @Query(
        "UPDATE tasks SET status = 'FAILED', completed_at = :completedAt, error = :error, retry_count = retry_count + 1 WHERE id = :id"
    )
    suspend fun markFailed(id: String, completedAt: java.util.Date, error: String?)

    @Query("DELETE FROM tasks WHERE status = 'COMPLETED' AND completed_at < :olderThan")
    suspend fun deleteCompletedOlderThan(olderThan: java.util.Date)

    @Query("DELETE FROM tasks")
    suspend fun clear()
}
