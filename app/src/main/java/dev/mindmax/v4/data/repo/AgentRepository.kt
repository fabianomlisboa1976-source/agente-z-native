package dev.mindmax.v4.data.repo

import dev.mindmax.v4.data.dao.AgentDao
import dev.mindmax.v4.data.entity.AgentEntity
import dev.mindmax.v4.data.entity.AgentType
import dev.mindmax.v4.data.db.DefaultAgents
import kotlinx.coroutines.flow.Flow
import java.util.Date

class AgentRepository(private val dao: AgentDao) {

    fun observeAll(): Flow<List<AgentEntity>> = dao.observeAll()

    suspend fun getById(id: String): AgentEntity? = dao.getById(id)

    suspend fun activeAgents(): List<AgentEntity> = dao.activeAgents()

    suspend fun byType(type: AgentType): List<AgentEntity> = dao.byType(type)

    suspend fun upsert(agent: AgentEntity) = dao.upsert(agent)

    suspend fun setActive(id: String, active: Boolean) = dao.setActive(id, active)

    suspend fun bumpUsage(id: String, now: Date = Date()) = dao.bumpUsage(id, now)

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun count(): Int = dao.count()

    /**
     * Seeds the 7 default agents the first time the app is launched. Called
     * from `ServiceLocator.init` on the application IO scope. Idempotent:
     * the empty check no-ops on every launch after the first.
     */
    suspend fun seedDefaultsIfEmpty(now: Date = Date()) {
        if (dao.count() == 0) {
            DefaultAgents.all(now).forEach { dao.upsert(it) }
        }
    }
}
