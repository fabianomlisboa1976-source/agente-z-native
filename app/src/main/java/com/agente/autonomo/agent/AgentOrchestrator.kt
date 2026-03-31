package com.agente.autonomo.agent

import com.agente.autonomo.api.LLMClient
import com.agente.autonomo.api.model.Message
import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.data.entity.*
import com.agente.autonomo.utils.AuditLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class AgentOrchestrator(
    private val database: AppDatabase,
    private val agentManager: AgentManager,
    private val auditLogger: AuditLogger
) {
    
    companion object {
        const val TAG = "AgentOrchestrator"
        const val MAX_CONTEXT_MESSAGES = 10
    }
    
    private var llmClient: LLMClient? = null
    
    suspend fun initializeLLM() {
        val settings = database.settingsDao().getSettingsSync()
        if (settings != null && settings.apiKey.isNotBlank()) {
            llmClient = LLMClient(settings)
        }
    }
    
    suspend fun processUserMessage(
        userMessage: String,
        conversationId: String = "default"
    ): Result<String> = withContext(Dispatchers.IO) {
        val correlationId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        
        try {
            saveUserMessage(userMessage, conversationId)
            
            val contextMessages = getConversationContext(conversationId)
            
            val coordinator = agentManager.getCoordinatorAgent()
                ?: return@withContext Result.failure(Exception("Agente coordenador não encontrado"))
            
            val agentDecision = decideAgentsForTask(coordinator, userMessage, contextMessages)
                ?: return@withContext Result.failure(Exception("Falha na decisão de agentes"))
            
            auditLogger.logAgentDecision(
                action = "Decisão de orquestração",
                agentId = coordinator.id,
                agentName = coordinator.name,
                details = "Agentes selecionados: ${agentDecision.selectedAgents.joinToString()}",
                outputData = agentDecision.reasoning,
                correlationId = correlationId
            )
            
            val executionResult = executeWithAgents(
                agentDecision.selectedAgents,
                userMessage,
                contextMessages,
                correlationId
            )
            
            val settings = database.settingsDao().getSettingsSync()
            if (settings?.crossAuditEnabled == true) {
                val auditResult = performCrossAudit(
                    userMessage,
                    executionResult,
                    correlationId
                )
                
                if (!auditResult.isSuccess) {
                    auditLogger.logWarning(
                        action = "Auditoria cruzada falhou",
                        details = auditResult.exceptionOrNull()?.message
                    )
                }
            }
            
            val finalResponse = executionResult.getOrThrow()
            saveAgentMessage(finalResponse, conversationId, coordinator.id, coordinator.name)
            
            if (settings?.memoryEnabled == true) {
                updateMemory(userMessage, finalResponse, conversationId)
            }
            
            val duration = System.currentTimeMillis() - startTime
            auditLogger.logAction(
                action = "Mensagem processada",
                agentId = coordinator.id,
                details = "Duração: ${duration}ms",
                durationMs = duration,
                correlationId = correlationId
            )
            
            Result.success(finalResponse)
            
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            auditLogger.logError(
                action = "Processar mensagem",
                error = e.message ?: "Erro desconhecido",
                durationMs = duration,
                correlationId = correlationId
            )
            Result.failure(e)
        }
    }
    
    suspend fun executeTask(task: Task): String = withContext(Dispatchers.IO) {
        val agent = task.assignedAgent?.let { agentManager.getAgent(it) }
            ?: agentManager.getCoordinatorAgent()
            ?: throw Exception("Nenhum agente disponível")
        
        val messages = listOf(
            Message(role = "system", content = agent.systemPrompt),
            Message(role = "user", content = "Execute a seguinte tarefa: ${task.description}")
        )
        
        val response = callLLM(messages, agent.maxTokens, agent.temperature)
            ?: throw Exception("Falha ao executar tarefa")
        
        agentManager.recordAgentUsage(agent.id)
        
        response
    }
    
    private suspend fun decideAgentsForTask(
        coordinator: Agent,
        userMessage: String,
        contextMessages: List<Message>
    ): AgentDecision? {
        val decisionPrompt = """Analise a solicitação do usuário e decida quais agentes devem atuar.

Agentes disponíveis:
${getAvailableAgentsDescription()}

Solicitação do usuário: "$userMessage"

Responda APENAS no seguinte formato JSON:
{
  "selectedAgents": ["id_agente1", "id_agente2"],
  "reasoning": "Explicação da decisão",
  "executionOrder": "parallel" ou "sequential"
}"""
        
        val messages = buildList {
            add(Message(role = "system", content = coordinator.systemPrompt))
            addAll(contextMessages)
            add(Message(role = "user", content = decisionPrompt))
        }
        
        val response = callLLM(messages, maxTokens = 500, temperature = 0.3f)
            ?: return null
        
        return try {
            parseAgentDecision(response)
        } catch (e: Exception) {
            AgentDecision(
                selectedAgents = listOf(coordinator.id),
                reasoning = "Fallback para coordenador devido a erro no parsing",
                executionOrder = "sequential"
            )
        }
    }
    
    private suspend fun executeWithAgents(
        agentIds: List<String>,
        userMessage: String,
        contextMessages: List<Message>,
        correlationId: String
    ): Result<String> {
        val results = mutableListOf<String>()
        
        for (agentId in agentIds) {
            val agent = agentManager.getAgent(agentId) ?: continue
            
            val messages = buildList {
                add(Message(role = "system", content = agent.systemPrompt))
                addAll(contextMessages)
                add(Message(role = "user", content = userMessage))
            }
            
            val response = callLLM(messages, agent.maxTokens, agent.temperature)
            
            if (response != null) {
                results.add(response)
                agentManager.recordAgentUsage(agent.id)
                
                auditLogger.logAction(
                    action = "Resposta do agente ${agent.name}",
                    agentId = agent.id,
                    agentName = agent.name,
                    outputData = response.take(500),
                    correlationId = correlationId
                )
            }
        }
        
        return if (results.isNotEmpty()) {
            Result.success(results.joinToString("\n\n---\n\n"))
        } else {
            Result.failure(Exception("Nenhum agente conseguiu responder"))
        }
    }
    
    private suspend fun performCrossAudit(
        originalRequest: String,
        responseResult: Result<String>,
        correlationId: String
    ): Result<Unit> {
        if (responseResult.isFailure) {
            return Result.failure(responseResult.exceptionOrNull() ?: Exception("Resposta falhou"))
        }
        
        val auditor = agentManager.getAgentsByType("AUDITOR").firstOrNull()
            ?: return Result.success(Unit)
        
        val response = responseResult.getOrThrow()
        
        val auditPrompt = """Você é o Auditor. Verifique a qualidade e consistência da resposta abaixo.

Solicitação original: "$originalRequest"

Resposta a ser auditada:
$response

Verifique:
1. A resposta atende à solicitação?
2. Há inconsistências ou erros?
3. A informação está completa?
4. Há questões de segurança ou privacidade?

Responda com:
- STATUS: [APROVADO/REPROVADO]
- PROBLEMAS: [lista ou "Nenhum"]
- SUGESTÕES: [melhorias ou "Nenhuma"]"""
        
        val messages = listOf(
            Message(role = "system", content = auditor.systemPrompt),
            Message(role = "user", content = auditPrompt)
        )
        
        val auditResponse = callLLM(messages, maxTokens = 500, temperature = 0.2f)
        
        auditLogger.logAction(
            action = "Auditoria cruzada",
            agentId = auditor.id,
            agentName = auditor.name,
            outputData = auditResponse,
            correlationId = correlationId
        )
        
        return if (auditResponse?.contains("REPROVADO") == true) {
            Result.failure(Exception("Auditoria reprovou a resposta"))
        } else {
            Result.success(Unit)
        }
    }
    
    private suspend fun callLLM(
        messages: List<Message>,
        maxTokens: Int? = null,
        temperature: Float? = null
    ): String? {
        if (llmClient == null) {
            initializeLLM()
        }
        
        val client = llmClient ?: return null
        
        return try {
            val result = client.sendMessage(messages, maxTokens = maxTokens, temperature = temperature)
            if (result.isSuccess) {
                result.getOrNull()?.choices?.firstOrNull()?.message?.content
            } else {
                null
            }
        } catch (e: Exception) {
            auditLogger.logError("Chamar LLM", e.message ?: "Erro desconhecido")
            null
        }
    }
    
    private suspend fun saveUserMessage(content: String, conversationId: String) {
        val message = com.agente.autonomo.data.entity.Message(
            senderType = com.agente.autonomo.data.entity.Message.SenderType.USER,
            content = content,
            conversationId = conversationId
        )
        database.messageDao().insertMessage(message)
    }
    
    private suspend fun saveAgentMessage(
        content: String,
        conversationId: String,
        agentId: String?,
        agentName: String?
    ) {
        val message = com.agente.autonomo.data.entity.Message(
            senderType = com.agente.autonomo.data.entity.Message.SenderType.AGENT,
            agentId = agentId,
            agentName = agentName,
            content = content,
            conversationId = conversationId
        )
        database.messageDao().insertMessage(message)
    }
    
    private suspend fun getConversationContext(conversationId: String): List<Message> {
        val recentMessages = database.messageDao()
            .getRecentMessages(conversationId, MAX_CONTEXT_MESSAGES)
        
        return recentMessages.map { msg ->
            when (msg.senderType) {
                com.agente.autonomo.data.entity.Message.SenderType.USER -> 
                    Message(role = "user", content = msg.content)
                com.agente.autonomo.data.entity.Message.SenderType.AGENT -> 
                    Message(role = "assistant", content = msg.content)
                com.agente.autonomo.data.entity.Message.SenderType.SYSTEM -> 
                    Message(role = "system", content = msg.content)
            }
        }
    }
    
    private suspend fun updateMemory(
        userMessage: String,
        response: String,
        conversationId: String
    ) {
        val memoryContent = "Usuário: $userMessage\nResposta: ${response.take(200)}"
        
        val memory = com.agente.autonomo.data.entity.Memory(
            id = UUID.randomUUID().toString(),
            type = "CONTEXT",
            key = "conversation_${conversationId}_${System.currentTimeMillis()}",
            value = memoryContent,
            importance = 5
        )
        
        database.memoryDao().insertMemory(memory)
    }
    
    private suspend fun getAvailableAgentsDescription(): String {
        val agents = agentManager.getAllActiveAgents().first()
        return agents.joinToString("\n") { agent ->
            "- ${agent.id}: ${agent.name} (${agent.type}) - ${agent.description}"
        }
    }
    
    private fun parseAgentDecision(json: String): AgentDecision {
        return try {
            val gson = com.google.gson.Gson()
            gson.fromJson(json, AgentDecision::class.java)
        } catch (e: Exception) {
            AgentDecision(
                selectedAgents = listOf("coordinator"),
                reasoning = "Erro no parsing, usando coordenador",
                executionOrder = "sequential"
            )
        }
    }
    
    data class AgentDecision(
        val selectedAgents: List<String>,
        val reasoning: String,
        val executionOrder: String
    )
}
