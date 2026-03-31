package com.agente.autonomo.utils

import com.agente.autonomo.data.dao.AuditLogDao
import com.agente.autonomo.data.entity.AuditLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Utilitário para registro de auditoria
 */
class AuditLogger(private val auditLogDao: AuditLogDao) {
    
    private val scope = CoroutineScope(Dispatchers.IO)
    
    fun log(
        type: AuditLog.AuditType,
        action: String,
        agentId: String? = null,
        agentName: String? = null,
        details: String? = null,
        inputData: String? = null,
        outputData: String? = null,
        status: AuditLog.Status = AuditLog.Status.SUCCESS,
        errorMessage: String? = null,
        durationMs: Long? = null,
        correlationId: String? = null
    ) {
        scope.launch {
            try {
                val log = AuditLog(
                    type = type,
                    action = action,
                    agentId = agentId,
                    agentName = agentName,
                    details = details,
                    inputData = inputData,
                    outputData = outputData,
                    status = status,
                    errorMessage = errorMessage,
                    durationMs = durationMs,
                    correlationId = correlationId ?: UUID.randomUUID().toString()
                )
                auditLogDao.insertLog(log)
            } catch (e: Exception) {
                // Silenciar erro de logging para não quebrar o fluxo
                e.printStackTrace()
            }
        }
    }
    
    fun logRequest(
        action: String,
        inputData: String? = null,
        agentId: String? = null,
        correlationId: String? = null
    ) {
        log(
            type = AuditLog.AuditType.REQUEST,
            action = action,
            agentId = agentId,
            inputData = inputData,
            correlationId = correlationId
        )
    }
    
    fun logResponse(
        action: String,
        outputData: String? = null,
        agentId: String? = null,
        durationMs: Long? = null,
        correlationId: String? = null
    ) {
        log(
            type = AuditLog.AuditType.RESPONSE,
            action = action,
            agentId = agentId,
            outputData = outputData,
            durationMs = durationMs,
            correlationId = correlationId
        )
    }
    
    fun logAction(
        action: String,
        agentId: String? = null,
        agentName: String? = null,
        details: String? = null,
        durationMs: Long? = null
    ) {
        log(
            type = AuditLog.AuditType.ACTION,
            action = action,
            agentId = agentId,
            agentName = agentName,
            details = details,
            durationMs = durationMs
        )
    }
    
    fun logError(
        action: String,
        error: String,
        agentId: String? = null,
        details: String? = null
    ) {
        log(
            type = AuditLog.AuditType.ERROR,
            action = action,
            agentId = agentId,
            details = details,
            status = AuditLog.Status.ERROR,
            errorMessage = error
        )
    }
    
    fun logSystem(
        action: String,
        details: String? = null
    ) {
        log(
            type = AuditLog.AuditType.SYSTEM,
            action = action,
            details = details
        )
    }
    
    fun logSecurity(
        action: String,
        details: String? = null,
        status: AuditLog.Status = AuditLog.Status.SUCCESS
    ) {
        log(
            type = AuditLog.AuditType.SECURITY,
            action = action,
            details = details,
            status = status
        )
    }
    
    fun logUserAction(
        action: String,
        details: String? = null
    ) {
        log(
            type = AuditLog.AuditType.USER_ACTION,
            action = action,
            details = details
        )
    }
    
    fun logAgentDecision(
        action: String,
        agentId: String,
        agentName: String,
        details: String? = null,
        outputData: String? = null,
        correlationId: String? = null
    ) {
        log(
            type = AuditLog.AuditType.AGENT_DECISION,
            action = action,
            agentId = agentId,
            agentName = agentName,
            details = details,
            outputData = outputData,
            correlationId = correlationId
        )
    }
    
    fun logWarning(
        action: String,
        details: String? = null,
        agentId: String? = null
    ) {
        log(
            type = AuditLog.AuditType.SYSTEM,
            action = action,
            agentId = agentId,
            details = details,
            status = AuditLog.Status.WARNING
        )
    }
}
