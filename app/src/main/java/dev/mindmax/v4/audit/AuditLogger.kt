package dev.mindmax.v4.audit

import dev.mindmax.v4.core.di.ServiceLocator
import dev.mindmax.v4.data.entity.AuditLogEntity
import dev.mindmax.v4.data.entity.AuditStatus
import dev.mindmax.v4.data.entity.AuditType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

/**
 * Thin audit surface on top of the [AuditRepository]. Every LLM or agent action
 * the app performs should land here as an [AuditLogEntity]; the audit screen
 * surfaces them via the [observeRecent] Flow.
 *
 * The logger fires-and-forgets into the application IO scope. If the DB write
 * fails the entry is dropped on the floor with a System.err line — we never
 * throw into the caller's hot path. That's deliberate: an audit failure must
 * not break a chat.
 */
class AuditLogger {

    private val scope: CoroutineScope get() = ServiceLocator.scope
    private val repository get() = ServiceLocator.auditRepository

    fun observeRecent(limit: Int = 200): Flow<List<AuditLogEntity>> =
        repository.observeRecent(limit)

    fun observeErrorsSince(since: Date): Flow<Int> = repository.observeErrorsSince(since)

    fun newCorrelationId(): String = UUID.randomUUID().toString().take(12)

    fun log(
        type: AuditType,
        action: String,
        agentId: String? = null,
        agentName: String? = null,
        details: String? = null,
        status: AuditStatus = AuditStatus.SUCCESS,
        errorMessage: String? = null,
        durationMs: Long? = null,
        correlationId: String? = null,
    ) {
        val entry = AuditLogEntity(
            timestamp = Date(),
            type = type,
            agentId = agentId,
            agentName = agentName,
            action = action,
            details = details,
            status = status,
            errorMessage = errorMessage,
            durationMs = durationMs,
            correlationId = correlationId,
        )
        scope.launch { runCatching { repository.insert(entry) } }
    }

    fun logRequest(
        correlationId: String,
        agentName: String?,
        agentId: String?,
        prompt: String,
    ) = log(
        type = AuditType.REQUEST,
        action = "llm.request",
        agentId = agentId,
        agentName = agentName,
        details = prompt.take(MAX_AUDIT_DETAIL),
        correlationId = correlationId,
    )

    fun logResponse(
        correlationId: String,
        agentName: String?,
        agentId: String?,
        result: String,
        durationMs: Long?,
    ) = log(
        type = AuditType.RESPONSE,
        action = "llm.response",
        agentId = agentId,
        agentName = agentName,
        details = result.take(MAX_AUDIT_DETAIL),
        status = AuditStatus.SUCCESS,
        durationMs = durationMs,
        correlationId = correlationId,
    )

    fun logAgentDecision(
        correlationId: String,
        decision: String,
    ) = log(
        type = AuditType.AGENT_DECISION,
        action = "agent.decide",
        details = decision.take(MAX_AUDIT_DETAIL),
        correlationId = correlationId,
    )

    fun logError(
        correlationId: String?,
        action: String,
        agentName: String? = null,
        agentId: String? = null,
        error: Throwable,
    ) = log(
        type = AuditType.ERROR,
        action = action,
        agentId = agentId,
        agentName = agentName,
        status = AuditStatus.ERROR,
        errorMessage = error.message ?: error.javaClass.simpleName,
        correlationId = correlationId,
    )

    companion object {
        /** Audit entries get redacted to this many chars to keep the table light. */
        const val MAX_AUDIT_DETAIL = 2_000
    }
}
