package dev.mindmax.v4.data.repo

import dev.mindmax.v4.data.dao.MemoryDao
import dev.mindmax.v4.data.entity.MemoryEntity
import dev.mindmax.v4.data.entity.MemoryType
import kotlinx.coroutines.flow.Flow
import java.util.Date

class MemoryRepository(private val dao: MemoryDao) {

    fun observeByType(type: MemoryType): Flow<List<MemoryEntity>> = dao.observeByType(type)

    suspend fun findByKey(key: String): MemoryEntity? = dao.findByKey(key)

    suspend fun topN(limit: Int = 50): List<MemoryEntity> = dao.topN(limit)

    suspend fun byCategory(category: String, limit: Int = 50): List<MemoryEntity> =
        dao.byCategory(category, "$category%", limit)

    suspend fun upsert(memory: MemoryEntity) = dao.upsert(memory)

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun touch(id: String, now: Date = Date()) = dao.bumpAccess(id, now)

    suspend fun purgeExpired(now: Date = Date()) = dao.purgeExpired(now)

    suspend fun clearAll() = dao.clear()
}
