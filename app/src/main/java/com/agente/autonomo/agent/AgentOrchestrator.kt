package com.agente.autonomo.agent

import android.content.Context
import android.util.Log
import com.agente.autonomo.api.FallbackLLMClient
import com.agente.autonomo.api.LLMClient
import com.agente.autonomo.api.ProviderHealthRepository
import com.agente.autonomo.api.ProviderHealthChecker
import com.agente.autonomo.api.model.Message
import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.data.entity.Agent
import com.agente.autonomo.data.entity.AgentType
import com.agente.autonomo.data.entity.Memory
import com.agente.autonomo.data.entity.SenderType
import com.agente.autonomo.service.ProviderExhaustedNotifier
import com.agente.autonomo.utils.AuditLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Core orchestration class for the multi-agent pipeline.
 *
 * ## Pipeline overview
 * Every call to [processUserMessage] executes the following sequential steps:
 *
 * ### Step 0 – Guard conditions
 * - If [llmClient] is `null`, return `Result.failure` immediately.
 * - If [conversationProgrammer] is not initialized, skip the command-intercept
 *   step and continue with the full pipeline.
 *
 * ### Step 1 – Command intercept
 * [ConversationAgentProgrammer.processMessage] is called first.  If it
 * recognises a programming command (create/modify/delete agent or task) it
 * handles the request and this method returns early with the programmer's
 * result, bypassing the LLM pipeline.
 *
 * ### Step 2 – Persist user message
 * The raw user text is saved to [MessageDao] under the active
 * [conversationId].
 *
 * ### Step 3 – Context assembly
 * - Recent messages are loaded from [MessageDao.getRecentMessages].
 * - Relevant memories are retrieved once via [MemoryDao.searchMemories] and
 *   reused for every agent step in this pipeline run.
 *
 * ### Step 4 – COORDINATOR invocation
 * [AgentManager.getCoordinatorAgent] supplies the COORDINATOR agent entity.
 * [FallbackLLMClient.sendMessage] is called with the assembled context.  The
 * failover chain (Groq → OpenRouter → GitHub Models → Cloudflare) is
 * transparent to the orchestrator.
 *
 * ### Step 5 – Sub-agent routing
 * The COORDINATOR's response text is scanned for Portuguese/English routing
 * keywords.  Matching specialised agents are invoked sequentially via the
 * same [FallbackLLMClient].
 *
 * ### Step 6 – Audit cross-check (optional)
 * If `settings.auditEnabled == true`, the AUDITOR agent reviews the
 * aggregated response.
 *
 * ### Step 7 – Persist agent responses
 * Each agent response is saved to [MessageDao].  Every step is logged to
 * [AuditLogDao] via [AuditLogger] using the same [correlationId].
 *
 * @param context        Android [Context] used for DB access and notifications.
 * @param agentManager   Provides CRUD operations and lookup for agent entities.
 * @param auditLogger    Utility for writing structured audit log entries.
 * @param conversationId UUID string identifying the current conversation.
 */
class AgentOrchestrator(
    private val context: Context,
    private val agentManager: AgentManager,
    private val auditLogger: AuditLogger,
    private val conversationId: String = UUID.randomUUID().toString()
) {

    companion object {
        const val TAG = "AgentOrchestrator"
        const val MAX_CONTEXT_MESSAGES = 10

        // Sub-agent routing keyword lists
        val RESEARCHER_KEYWORDS = listOf(
            "pesquisa", "busca", "pesquisar", "buscar",
            "research", "search", "find", "lookup"
        )
        val PLANNER_KEYWORDS = listOf(
            "plano", "planejamento", "planejar", "organizar",
            "plan", "organize", "schedule", "breakdown"
        )
        val EXECUTOR_KEYWORDS = listOf(
            "executa", "implementa", "executar", "implementar",
            "execute", "implement", "run", "perform"
        )
        val COMMUNICATION_KEYWORDS = listOf(
            "comunica", "envia", "comunicar", "enviar",
            "communicate", "send", "notify", "message"
        )
    }

    // Nullable – pipeline short-circuits if null (no API key configured)
    private var llmClient: LLMClient? = null
    private var fallbackClient: FallbackLLMClient? = null

    private lateinit var conversationProgrammer: ConversationAgentProgrammer

    private val db by lazy { AppDatabase.getDatabase(context) }
    private val notifier by lazy { ProviderExhaustedNotifier(context) }

    // ---------------------------------------------------------------------------
    // Initialisation
    // ---------------------------------------------------------------------------

    /**
     * Initialises the [LLMClient] and [FallbackLLMClient] from persisted
     * settings.  Must be called before [processUserMessage].
     *
     * If the settings contain no API key, [llmClient] remains `null` and the
     * pipeline will return an error result on every invocation.
     *
     * @param settings Current [com.agente.autonomo.data.entity.Settings] row.
     */
    fun initialize(settings: com.agente.autonomo.data.entity.Settings) {
        if (settings.apiKey.isBlank()) {
            Log.w(TAG, "No API key configured – LLM pipeline disabled")
            return
        }
        llmClient = LLMClient(settings)

        val healthRepo = ProviderHealthRepository(db.providerHealthDao())
        val healthChecker = ProviderHealthChecker()

        fallbackClient = FallbackLLMClient(
            baseSettings = settings,
            healthRepository = healthRepo,
            healthChecker = healthChecker,
            onAllProvidersFailed = {
                Log.e(TAG, "All providers exhausted – posting notification")
                notifier.notifyAllProvidersExhausted()
            }
        )

        conversationProgrammer = ConversationAgentProgrammer(
            agentManager = agentManager,
            auditLogger = auditLogger
        )
        Log.i(TAG, "AgentOrchestrator initialised with provider: ${settings.apiProvider}")
    }

    // ---------------------------------------------------------------------------
    // Main pipeline entry point
    // ---------------------------------------------------------------------------

    /**
     * Processes a single user message through the full multi-agent pipeline.
     *
     * See the class-level KDoc for a detailed description of each step.
     *
     * @param userMessage    Raw text entered by the user.
     * @param conversationId Optional override for the conversation UUID;
     *                       defaults to the instance-level [conversationId].
     * @return [Result.success] containing the final agent response string, or
     *         [Result.failure] with a descriptive exception.
     */
    suspend fun processUserMessage(
        userMessage: String,
        conversationId: String = this.conversationId
    ): Result<String> = withContext(Dispatchers.IO) {

        // ----------------------------------------------------------------
        // Step 0 – Guard: require FallbackLLMClient (implies LLMClient too)
        // ----------------------------------------------------------------
        val client = fallbackClient ?: run {
            Log.w(TAG, "FallbackLLMClient not initialised – aborting pipeline")
            return@withContext Result.failure(
                IllegalStateException(
                    "LLM client not configured. Please set an API key in Settings."
                )
            )
        }

        val correlationId = UUID.randomUUID().toString()
        Log.d(TAG, "Pipeline start [correlationId=$correlationId]")

        // ----------------------------------------------------------------
        // Step 1 – Command intercept
        // ----------------------------------------------------------------
        if (::conversationProgrammer.isInitialized) {
            val programmerResult =
                conversationProgrammer.processMessage(userMessage, conversationId)
            if (programmerResult != null) {
                Log.d(TAG, "Command handled by ConversationAgentProgrammer")
                return@withContext programmerResult
            }
        }

        // ----------------------------------------------------------------
        // Step 2 – Persist user message
        // ----------------------------------------------------------------
        saveUserMessage(userMessage, conversationId)

        // ----------------------------------------------------------------
        // Step 3 – Context assembly
        // ----------------------------------------------------------------
        val contextMessages = getConversationContext(conversationId)
        val memories = getRelevantMemories(userMessage)

        // ----------------------------------------------------------------
        // Step 4 – COORDINATOR invocation via failover chain
        // ----------------------------------------------------------------
        val coordinatorAgent = agentManager.getCoordinatorAgent()
            ?: return@withContext Result.failure(
                IllegalStateException("No COORDINATOR agent found in database")
            )

        auditLogger.logAction(
            type = com.agente.autonomo.data.entity.AuditLogType.AGENT_ACTION,
            action = "COORDINATOR invoked",
            agentId = coordinatorAgent.id,
            agentName = coordinatorAgent.name,
            details = "correlationId=$correlationId",
            correlationId = correlationId
        )

        val coordinatorMessages = buildAgentMessages(
            agent = coordinatorAgent,
            userMessage = userMessage,
            contextMessages = contextMessages,
            memories = memories
        )

        val coordinatorResult = client.sendMessage(coordinatorMessages)
        if (coordinatorResult.isFailure) {
            val err = coordinatorResult.exceptionOrNull()?.message ?: "Unknown error"
            Log.e(TAG, "COORDINATOR failed: $err")
            auditLogger.logAction(
                type = com.agente.autonomo.data.entity.AuditLogType.ERROR,
                action = "COORDINATOR failed",
                agentId = coordinatorAgent.id,
                agentName = coordinatorAgent.name,
                details = "error=$err correlationId=$correlationId",
                correlationId = correlationId
            )
            return@withContext Result.failure(
                Exception("COORDINATOR agent failed: $err")
            )
        }

        val coordinatorResponse = coordinatorResult.getOrThrow()
            .choices.firstOrNull()?.message?.content
            ?: return@withContext Result.failure(
                Exception("Empty response from COORDINATOR")
            )

        saveAgentMessage(coordinatorResponse, coordinatorAgent.id, conversationId)
        auditLogger.logAction(
            type = com.agente.autonomo.data.entity.AuditLogType.AGENT_ACTION,
            action = "COORDINATOR responded",
            agentId = coordinatorAgent.id,
            agentName = coordinatorAgent.name,
            details = "length=${coordinatorResponse.length} correlationId=$correlationId",
            correlationId = correlationId
        )

        // ----------------------------------------------------------------
        // Step 5 – Sub-agent routing
        // ----------------------------------------------------------------
        val subAgentResponses = routeToSubAgents(
            coordinatorResponse = coordinatorResponse,
            userMessage = userMessage,
            contextMessages = contextMessages,
            memories = memories,
            correlationId = correlationId,
            conversationId = conversationId,
            client = client
        )

        val finalResponse = if (subAgentResponses.isEmpty()) {
            coordinatorResponse
        } else {
            subAgentResponses.joinToString("\n\n")
        }

        // ----------------------------------------------------------------
        // Step 6 – Audit cross-check (optional)
        // ----------------------------------------------------------------
        val settings = db.settingsDao().getSettingsSync()
        if (settings?.auditEnabled == true) {
            runAuditStep(
                response = finalResponse,
                userMessage = userMessage,
                contextMessages = contextMessages,
                memories = memories,
                correlationId = correlationId,
                conversationId = conversationId,
                client = client
            )
        }

        Log.i(TAG, "Pipeline complete [correlationId=$correlationId]")
        Result.success(finalResponse)
    }

    // ---------------------------------------------------------------------------
    // Sub-agent routing
    // ---------------------------------------------------------------------------

    /**
     * Scans [coordinatorResponse] for routing keywords and invokes the
     * appropriate specialised agents via [client].
     *
     * Routing is additive: multiple sub-agents may be triggered by a single
     * coordinator response.
     *
     * @param coordinatorResponse The COORDINATOR's response text.
     * @param userMessage         Original user input.
     * @param contextMessages     Recent conversation messages.
     * @param memories            Pre-fetched relevant memories.
     * @param correlationId       Shared trace ID for this pipeline run.
     * @param conversationId      Active conversation ID.
     * @param client              [FallbackLLMClient] to use for sub-agent calls.
     * @return List of response strings from each invoked sub-agent.
     */
    private suspend fun routeToSubAgents(
        coordinatorResponse: String,
        userMessage: String,
        contextMessages: List<com.agente.autonomo.data.entity.Message>,
        memories: List<Memory>,
        correlationId: String,
        conversationId: String,
        client: FallbackLLMClient
    ): List<String> {
        val lower = coordinatorResponse.lowercase()
        val responses = mutableListOf<String>()

        suspend fun trySubAgent(keywords: List<String>, agentType: AgentType) {
            if (keywords.any { lower.contains(it) }) {
                val agent = agentManager.getAgentByType(agentType) ?: return
                val msgs = buildAgentMessages(agent, userMessage, contextMessages, memories)
                auditLogger.logAction(
                    type = com.agente.autonomo.data.entity.AuditLogType.AGENT_ACTION,
                    action = "${agentType.name} invoked",
                    agentId = agent.id,
                    agentName = agent.name,
                    details = "correlationId=$correlationId",
                    correlationId = correlationId
                )
                val result = client.sendMessage(msgs)
                result.onSuccess { resp ->
                    val text = resp.choices.firstOrNull()?.message?.content ?: return
                    saveAgentMessage(text, agent.id, conversationId)
                    auditLogger.logAction(
                        type = com.agente.autonomo.data.entity.AuditLogType.AGENT_ACTION,
                        action = "${agentType.name} responded",
                        agentId = agent.id,
                        agentName = agent.name,
                        details = "length=${text.length} correlationId=$correlationId",
                        correlationId = correlationId
                    )
                    responses += text
                }.onFailure { err ->
                    Log.w(TAG, "${agentType.name} failed: ${err.message}")
                }
            }
        }

        trySubAgent(RESEARCHER_KEYWORDS, AgentType.RESEARCHER)
        trySubAgent(PLANNER_KEYWORDS, AgentType.PLANNER)
        trySubAgent(EXECUTOR_KEYWORDS, AgentType.EXECUTOR)
        trySubAgent(COMMUNICATION_KEYWORDS, AgentType.COMMUNICATION)

        return responses
    }

    // ---------------------------------------------------------------------------
    // Audit step
    // ---------------------------------------------------------------------------

    /**
     * Invokes the AUDITOR agent to cross-check [response] when
     * `settings.auditEnabled` is true.
     *
     * The AUDITOR's output is saved to [MessageDao] and logged but does not
     * replace the final response returned to the UI.
     *
     * @param response        The aggregated response to audit.
     * @param userMessage     Original user input.
     * @param contextMessages Recent conversation messages.
     * @param memories        Pre-fetched relevant memories.
     * @param correlationId   Shared trace ID for this pipeline run.
     * @param conversationId  Active conversation ID.
     * @param client          [FallbackLLMClient] to use for the audit call.
     */
    private suspend fun runAuditStep(
        response: String,
        userMessage: String,
        contextMessages: List<com.agente.autonomo.data.entity.Message>,
        memories: List<Memory>,
        correlationId: String,
        conversationId: String,
        client: FallbackLLMClient
    ) {
        val auditorAgent = agentManager.getAgentByType(AgentType.AUDITOR) ?: return
        val auditUserMessage = "Please audit the following response:\n\n$response"
        val msgs = buildAgentMessages(
            auditorAgent, auditUserMessage, contextMessages, memories
        )
        auditLogger.logAction(
            type = com.agente.autonomo.data.entity.AuditLogType.AGENT_ACTION,
            action = "AUDITOR invoked",
            agentId = auditorAgent.id,
            agentName = auditorAgent.name,
            details = "correlationId=$correlationId",
            correlationId = correlationId
        )
        client.sendMessage(msgs).onSuccess { resp ->
            val text = resp.choices.firstOrNull()?.message?.content ?: return
            saveAgentMessage(text, auditorAgent.id, conversationId)
            auditLogger.logAction(
                type = com.agente.autonomo.data.entity.AuditLogType.AGENT_ACTION,
                action = "AUDITOR responded",
                agentId = auditorAgent.id,
                agentName = auditorAgent.name,
                details = "length=${text.length} correlationId=$correlationId",
                correlationId = correlationId
            )
        }.onFailure { err ->
            Log.w(TAG, "AUDITOR failed: ${err.message}")
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun buildAgentMessages(
        agent: Agent,
        userMessage: String,
        contextMessages: List<com.agente.autonomo.data.entity.Message>,
        memories: List<Memory>
    ): List<Message> {
        val messages = mutableListOf<Message>()

        // System prompt
        val systemContent = buildString {
            append(agent.systemPrompt)
            if (memories.isNotEmpty()) {
                append("\n\n[Relevant memories]\n")
                memories.forEach { mem -> append("- ${mem.key}: ${mem.value}\n") }
            }
        }
        messages += Message(role = "system", content = systemContent)

        // Recent conversation context
        contextMessages.takeLast(MAX_CONTEXT_MESSAGES).forEach { msg ->
            val role = when (msg.senderType) {
                SenderType.USER -> "user"
                else -> "assistant"
            }
            messages += Message(role = role, content = msg.content)
        }

        // Current user message
        messages += Message(role = "user", content = userMessage)

        return messages
    }

    private suspend fun saveUserMessage(content: String, conversationId: String) {
        db.messageDao().insert(
            com.agente.autonomo.data.entity.Message(
                conversationId = conversationId,
                agentId = null,
                senderType = SenderType.USER,
                content = content,
                timestamp = java.util.Date()
            )
        )
    }

    private suspend fun saveAgentMessage(
        content: String,
        agentId: String?,
        conversationId: String
    ) {
        db.messageDao().insert(
            com.agente.autonomo.data.entity.Message(
                conversationId = conversationId,
                agentId = agentId,
                senderType = SenderType.AGENT,
                content = content,
                timestamp = java.util.Date()
            )
        )
    }

    private suspend fun getConversationContext(
        conversationId: String
    ): List<com.agente.autonomo.data.entity.Message> {
        return db.messageDao().getRecentMessages(
            conversationId = conversationId,
            limit = MAX_CONTEXT_MESSAGES
        )
    }

    private suspend fun getRelevantMemories(query: String): List<Memory> {
        return db.memoryDao().searchMemories(query)
    }
}
