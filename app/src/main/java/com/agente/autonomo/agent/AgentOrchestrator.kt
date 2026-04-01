package com.agente.autonomo.agent

import com.agente.autonomo.api.LLMClient
import com.agente.autonomo.api.model.Message
import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.data.entity.*
import com.agente.autonomo.utils.AuditLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Orquestrador de Agentes - coordena a execução entre múltiplos agentes
 * com auditoria cruzada, aprendizado contínuo e programação via conversa
 */
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
    private lateinit var conversationProgrammer: ConversationAgentProgrammer
    
    /**
     * Inicializa o cliente LLM com as configurações atuais
     */
    suspend fun initializeLLM() {
        val settings = database.settingsDao().getSettingsSync()
        if (settings != null && settings.apiKey.isNotBlank()) {
            llmClient = LLMClient(settings)
        }
        // Inicializar programador de agentes via conversa
        conversationProgrammer = ConversationAgentProgrammer(database, agentManager, auditLogger)
    }
    
    /**
     * Processa uma mensagem do usuário através do sistema multi-agente
     */
    suspend fun processUserMessage(
        userMessage: String,
        conversationId: String = "default"
    ): Result<String> = withContext(Dispatchers.IO) {
        val correlationId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        
        try {
            // 0. Verificar se é um comando de programação de agentes
            if (::conversationProgrammer.isInitialized) {
                val commandResult = conversationProgrammer.processMessage(userMessage)
                if (commandResult.isCommand) {
                    // Salvar mensagem do usuário e resposta do comando
                    saveUserMessage(userMessage, conversationId)
                    saveAgentMessage(commandResult.message, conversationId, "system", "Sistema")
                    
                    auditLogger.logAction(
                        action = "Comando de programação executado",
                        details = userMessage.take(100),
                        correlationId = correlationId
                    )
                    
                    return@withContext Result.success(commandResult.message)
                }
            }
            
            // 1. Salvar mensagem do usuário
            saveUserMessage(userMessage, conversationId)
            
            // 2. Obter contexto da conversa e memória relevante
            val contextMessages = getConversationContext(conversationId)
            val relevantMemories = getRelevantMemories(userMessage)
            
            // 3. Obter agente coordenador
            val coordinator = agentManager.getCoordinatorAgent()
                ?: return@withContext Result.failure(Exception("Agente coordenador não encontrado"))
            
            // 4. O coordenador analisa e decide qual(is) agente(s) deve(m) atuar
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
            
            // 5. Executar com os agentes selecionados
            val executionResult = executeWithAgents(
                agentDecision.selectedAgents,
                userMessage,
                contextMessages,
                correlationId
            )
            
            // 6. Auditoria cruzada (se habilitada)
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
            
            // 7. Salvar resposta
            val finalResponse = executionResult.getOrThrow()
            saveAgentMessage(finalResponse, conversationId, coordinator.id, coordinator.name)
            
            // 8. Atualizar memória se necessário
            if (settings?.memoryEnabled == true) {
                updateMemory(userMessage, finalResponse, conversationId)
            }
            
            val duration = System.currentTimeMillis() - startTime
            auditLogger.logAction(
                action = "Mensagem processada",
                agentId = coordinator.id,
                details = "Duração: ${duration}ms, Tokens: ${getLastTokenUsage()}",
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
    
    /**
     * Executa uma tarefa específica
     */
    suspend fun executeTask(task: Task): String = withContext(Dispatchers.IO) {
        val agent = task.assignedAgent?.let { agentManager.getAgent(it) }
            ?: agentManager.getCoordinatorAgent()
            ?: throw Exception("Nenhum agente disponível")
        
        val messages = listOf(
            Message(role = "system", content = agent.systemPrompt),
            Message(role = "user", content = "Execute a seguinte tarefa: ${task.description}\n\n" +
                "Parâmetros: ${task.parameters ?: "Nenhum"}")
        )
        
        val response = callLLM(messages, agent.maxTokens, agent.temperature)
            ?: throw Exception("Falha ao executar tarefa")
        
        agentManager.recordAgentUsage(agent.id)
        
        response
    }
    
    /**
     * Decide quais agentes devem atuar em uma tarefa
     */
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
        
        val response = callLLM(messages, maxTokens = 500, temperature = 0.3)
            ?: return null
        
        return try {
            parseAgentDecision(response)
        } catch (e: Exception) {
            // Fallback: usar apenas o coordenador
            AgentDecision(
                selectedAgents = listOf(coordinator.id),
                reasoning = "Fallback para coordenador devido a erro no parsing",
                executionOrder = "sequential"
            )
        }
    }
    
    /**
     * Executa com os agentes selecionados
     */
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
    
    /**
     * Realiza auditoria cruzada da resposta
     */
    private suspend fun performCrossAudit(
        originalRequest: String,
        responseResult: Result<String>,
        correlationId: String
    ): Result<Unit> {
        if (responseResult.isFailure) {
            return Result.failure(responseResult.exceptionOrNull() ?: Exception("Resposta falhou"))
        }
        
        val auditor = agentManager.getAgentsByType(Agent.AgentType.AUDITOR).firstOrNull()
            ?: return Result.success(Unit) // Auditoria opcional
        
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
        
        val auditResponse = callLLM(messages, maxTokens = 500, temperature = 0.2)
        
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
    
    /**
     * Chama a API LLM
     */
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
    
    /**
     * Salva mensagem do usuário no banco
     */
    private suspend fun saveUserMessage(content: String, conversationId: String) {
        val message = Message(
            senderType = com.agente.autonomo.data.entity.Message.SenderType.USER,
            content = content,
            conversationId = conversationId
        )
        database.messageDao().insertMessage(message)
    }
    
    /**
     * Salva mensagem do agente no banco
     */
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
    
    /**
     * Obtém o contexto da conversa
     */
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
    
    /**
     * Obtém memórias relevantes para o contexto atual
     */
    private suspend fun getRelevantMemories(query: String): List<com.agente.autonomo.data.entity.Memory> {
        return try {
            // Buscar memórias por palavras-chave
            val keywords = query.lowercase()
                .split(" ")
                .filter { it.length > 3 }
                .take(5)
            
            val memories = mutableListOf<com.agente.autonomo.data.entity.Memory>()
            
            for (keyword in keywords) {
                val found = database.memoryDao().searchMemories(keyword)
                memories.addAll(found)
            }
            
            // Retornar memórias únicas ordenadas por importância
            memories.distinctBy { it.id }
                .sortedByDescending { it.importance }
                .take(5)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Atualiza a memória com nova informação
     */
    private suspend fun updateMemory(
        userMessage: String,
        response: String,
        conversationId: String
    ) {
        // Identificar fatos importantes para memorizar
        val memoryContent = "Usuário: $userMessage\nResposta: ${response.take(200)}"
        
        val memory = com.agente.autonomo.data.entity.Memory(
            id = UUID.randomUUID().toString(),
            type = com.agente.autonomo.data.entity.Memory.MemoryType.CONTEXT,
            key = "conversation_${conversationId}_${System.currentTimeMillis()}",
            value = memoryContent,
            conversationId = conversationId,
            importance = 5
        )
        
        database.memoryDao().insertMemory(memory)
    }
    
    /**
     * Obtém descrição dos agentes disponíveis
     */
    private suspend fun getAvailableAgentsDescription(): String {
        val agents = agentManager.getAllActiveAgents().first()
        return agents.joinToString("\n") { agent ->
            "- ${agent.id}: ${agent.name} (${agent.type.name}) - ${agent.description}"
        }
    }
    
    /**
     * Faz parse da decisão de agentes
     */
    private fun parseAgentDecision(json: String): AgentDecision {
        // Implementação simplificada - em produção usar Gson
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
    
    private fun getLastTokenUsage(): Int {
        // Implementar rastreamento de tokens
        return 0
    }
    
    // Data classes auxiliares
    data class AgentDecision(
        val selectedAgents: List<String>,
        val reasoning: String,
        val executionOrder: String
    )
}
