package com.agente.autonomo.data.dao

import androidx.room.*
import com.agente.autonomo.data.entity.Task
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface TaskDao {
    
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<Task>>
    
    @Query("SELECT * FROM tasks WHERE status = :status ORDER BY priority DESC, createdAt ASC")
    fun getTasksByStatus(status: String): Flow<List<Task>>
    
    @Query("SELECT * FROM tasks WHERE status IN ('PENDING', 'IN_PROGRESS') ORDER BY priority DESC, dueDate ASC")
    suspend fun getPendingTasks(): List<Task>
    
    @Query("SELECT * FROM tasks WHERE assignedAgent = :agentId AND status = 'PENDING' ORDER BY priority DESC")
    suspend fun getPendingTasksForAgent(agentId: String): List<Task>
    
    @Query("SELECT * FROM tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: String): Task?
    
    @Query("SELECT * FROM tasks WHERE type = :type ORDER BY createdAt DESC")
    fun getTasksByType(type: String): Flow<List<Task>>
    
    @Query("SELECT * FROM tasks WHERE dueDate <= :now AND status = 'SCHEDULED' ORDER BY priority DESC")
    suspend fun getScheduledTasksDue(now: Date = Date()): List<Task>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<Task>)
    
    @Update
    suspend fun updateTask(task: Task)
    
    @Delete
    suspend fun deleteTask(task: Task)
    
    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteTaskById(taskId: String)
    
    @Query("DELETE FROM tasks WHERE status = 'COMPLETED' AND completedAt < :before")
    suspend fun deleteCompletedTasksBefore(before: Date)
    
    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()
    
    @Query("UPDATE tasks SET status = :status WHERE id = :taskId")
    suspend fun updateTaskStatus(taskId: String, status: String)
    
    @Query("UPDATE tasks SET status = 'RUNNING', startedAt = :now WHERE id = :taskId")
    suspend fun markTaskAsRunning(taskId: String, now: Date = Date())
    
    @Query("UPDATE tasks SET status = 'COMPLETED', completedAt = :now, result = :result WHERE id = :taskId")
    suspend fun markTaskAsCompleted(taskId: String, result: String?, now: Date = Date())
    
    @Query("UPDATE tasks SET status = 'FAILED', error = :error WHERE id = :taskId")
    suspend fun markTaskAsFailed(taskId: String, error: String)
    
    @Query("UPDATE tasks SET retryCount = retryCount + 1 WHERE id = :taskId")
    suspend fun incrementRetryCount(taskId: String)
    
    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun getTaskCount(): Int
    
    @Query("SELECT COUNT(*) FROM tasks WHERE status = :status")
    suspend fun getTaskCountByStatus(status: String): Int
    
    @Query("SELECT * FROM tasks WHERE parentTaskId = :parentId ORDER BY createdAt ASC")
    suspend fun getSubTasks(parentId: String): List<Task>
    
    @Query("SELECT * FROM tasks WHERE tags LIKE '%' || :tag || '%' ORDER BY createdAt DESC")
    suspend fun getTasksByTag(tag: String): List<Task>
    
    @Query("SELECT * FROM tasks WHERE dueDate < :now AND status NOT IN ('COMPLETED', 'CANCELLED') ORDER BY dueDate ASC")
    suspend fun getOverdueTasks(now: Date = Date()): List<Task>
}
