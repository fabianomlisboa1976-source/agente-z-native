package com.agente.autonomo.utils

import com.agente.autonomo.data.dao.AuditLogDao
import com.agente.autonomo.data.entity.AuditLog
import java.util.Date

class AuditLogger(private val dao: AuditLogDao) {
    
    suspend fun logAction(
        action: String,
        agentId: String? = null,
        agentName: String? = null,
        details: String? = null,
        inputData: String? = null,
        outputData: String? = null,
        durationMs: Long? = null,
        correlationId: String? = null
    ) {
        dao.insertLog(AuditLog(
            type = "ACTION",
            agentId = agentId,
            agentName = agentName,
            action = action,
            details = details,
            inputData = inputData,
            outputData = outputData,
            durationMs = durationMs,
            correlationId = correlationId,
            status = "SUCCESS"
        ))
    }
    
    suspend fun logError(
        action: String,
        error: String,
        agentId: String? = null,
        agentName: String? = null,
        inputData: String? = null,
        durationMs: Long? = null,
        correlationId: String? = null
    ) {
        dao.insertLog(AuditLog(
            type = "ERROR",
            agentId = agentId,
            agentName = agentName,
            action = action,
            inputData = inputData,
            errorMessage = error,
            durationMs = durationMs,
            correlationId = correlationId,
            status = "ERROR"
        ))
    }
    
    suspend fun logWarning(
        action: String,
        details: String? = null,
        agentId: String? = null,
        correlationId: String? = null
    ) {
        dao.insertLog(AuditLog(
            type = "ACTION",
            agentId = agentId,
            action = action,
            details = details,
            correlationId = correlationId,
            status = "WARNING"
        ))
    }
    
    suspend fun logSystem(
        message: String,
        correlationId: String? = null
    ) {
        dao.insertLog(AuditLog(
            type = "SYSTEM",
            action = message,
            correlationId = correlationId,
            status = "SUCCESS"
        ))
    }
    
    suspend fun logUserAction(
        action: String,
        details: String? = null,
        inputData: String? = null
    ) {
        dao.insertLog(AuditLog(
            type = "USER_ACTION",
            action = action,
            details = details,
            inputData = inputData,
            status = "SUCCESS"
        ))
    }
    
    suspend fun logAgentDecision(
        action: String,
        agentId: String?,
        agentName: String?,
        details: String?,
        outputData: String?,
        correlationId: String?
    ) {
        dao.insertLog(AuditLog(
            type = "AGENT_DECISION",
            agentId = agentId,
            agentName = agentName,
            action = action,
            details = details,
            outputData = outputData,
            correlationId = correlationId,
            status = "SUCCESS"
        ))
    }
    
    suspend fun logSecurity(
        action: String,
        details: String? = null
    ) {
        dao.insertLog(AuditLog(
            type = "SECURITY",
            action = action,
            details = details,
            status = "SUCCESS"
        ))
    }
    
    suspend fun logRequest(
        action: String,
        inputData: String?,
        correlationId: String?
    ) {
        dao.insertLog(AuditLog(
            type = "REQUEST",
            action = action,
            inputData = inputData,
            correlationId = correlationId,
            status = "PENDING"
        ))
    }
    
    suspend fun logResponse(
        action: String,
        outputData: String?,
        durationMs: Long?,
        correlationId: String?
    ) {
        dao.insertLog(AuditLog(
            type = "RESPONSE",
            action = action,
            outputData = outputData,
            durationMs = durationMs,
            correlationId = correlationId,
            status = "SUCCESS"
        ))
    }
}
