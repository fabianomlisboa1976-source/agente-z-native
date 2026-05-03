package com.agente.autonomo.agent

import android.content.Context
import android.util.Log
import com.agente.autonomo.api.FallbackLLMClient
import com.agente.autonomo.api.model.ApiProvider
import com.agente.autonomo.api.model.Message
import com.agente.autonomo.data.dao.AgentDao
import com.agente.autonomo.data.dao.AuditLogDao
import com.agente.autonomo.data.dao.MemoryDao
import com.agente.autonomo.data.dao.MessageDao
import com.agente.autonomo.data.dao.SettingsDao
import com.agente.autonomo.data.entity.AuditLog
import com.agente.autonomo.data.entity.Memory
import com.agente.autonomo.data.entity.SenderType
import com.agente.autonomo.memory.EmbeddingEngine
import com.agente.autonomo.memory.MemorySearchEngine
import com.agente.autonomo.utils.AuditLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID

/**
 * Central orchestrator for the multi-agent sequential pipeline.
 *
 * ## Pipeline stages (per [processUserMessage] invocation)
 *
 * ```
 * Stage 0 — Intercept special commands
 *       ↓
 * Stage 1 — Fetch recent messages (short-term context)
 *       ↓
 * Stage 2 — Semantic memory retrieval via [MemorySearchEngine] (long-term context)
 *       ↓
 * Stage 3 — Classify intent and select routing target
 *       ↓
 * Stage 4 — Route message to one or more agents and collect responses
 *       ↓
 * Stage 5 — Optional cross-audit by AUDITOR agent
 *       ↓
 * Stage 6 — Persist agent response to MessageDao
 *       ↓
 * Stage 7 — Optional memory update (store conversation turn)
 * ```
 *
 * All stages share a single [correlationId] for end-to-end audit tracing.
 *
 * ## Memory retrieval
 * Stage 2 delegates to [MemorySearchEngine.search] which performs:
 * 1. Vector cosine-similarity ranking against pre-computed ONNX embeddings.
 * 2. Transparent keyword fallback for legacy rows without embeddings.
 *
 * The returned memories are injected as a `role="system"` block into every
 * downstream agent LLM call so the model treats them as authoritative
 * background knowledge.
 */
class AgentOrchestrator(
    private val context: Context,
    private val messageDao: MessageDao,
    private val memoryDao: MemoryDao,
    private val agentDao: AgentDao,
    private val auditLogDao: AuditLogDao,
    private val settingsDao: SettingsDao,
    private val auditLogger: AuditLogger
) {

    companion object {
        private const val TAG = "AgentOrchestrator"

        /** Maximum number of recent messages included in short-term context. */
        private const val MAX_CONTEXT_MESSAGES = 10

        /** Maximum memories injected into each agent prompt (Stage 2). */
        private const val MAX_MEMORY_SNIPPETS = 5

        /** Intent confidence threshold above which a specialist agent is preferred. */
        private const val INTENT_THRESHOLD = 0.6f

        private val RESEARCH_KEYWORDS = setOf(
            "pesquisa", "busca", "encontra", "research", "find", "search", "procura"
        )
        private val PLANNING_KEYWORDS = setOf(
            "plano", "planejamento", "plan", "planning", "steps", "strategy", "etapas"
        )
        private val EXECUTION_KEYWORDS = setOf(
            "execute", "executar", "fazer", "run", "create", "build", "cria", "implement"
        )
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private var fallbackLLMClient: FallbackLLMClient? = null
    private lateinit var conversationProgrammer: ConversationAgentProgrammer
    private lateinit var agentManager: AgentManager

    /** Lazily created [MemorySearchEngine] — shares [memoryDao] with this class. */
    private val memorySearchEngine: MemorySearchEngine by lazy {
        MemorySearchEngine(memoryDao = memoryDao, context = context)
    }

    // ------------------------------------------------------------------
    // Initialisation
    // ------------------------------------------------------------------

    /**
     * Initialise the LLM client from persisted settings.  Must be called
     * before [processUserMessage].  Safe to call multiple times — each call
     * constructs a fresh [FallbackLLMClient] so API-key / provider changes
     * are picked up immediately.
     */
    suspend fun initializeLLM() {
        val settings = settingsDao.getSettingsSync() ?: return
        if (settings.apiKey.isBlank()) {
            Log.w(TAG, "API key is blank — LLM calls will fail")
            return
        }
        fallbackLLMClient = FallbackLLMClient(
            baseSettings = settings,
            healthRepository = com.agente.autonomo.api.ProviderHealthRepository(
                providerHealthDao = com.agente.autonomo.data.database.AppDatabase
                    .getDatabase(context).providerHealthDao()
            ),
            healthChecker = com.agente.autonomo.api.ProviderHealthChecker(),
            onAllProvidersFailed = {
                Log.e(TAG, "All LLM providers exhausted for this request")
            }
        )
        agentManager = AgentManager(agentDao, auditLogger)
        conversationProgrammer = ConversationAgentProgrammer(agentManager, memoryDao)
        Log.i(TAG, "LLM client initialised (provider=${settings.apiProvider})")
    }

    // ------------------------------------------------------------------
    // Pipeline entry point
    // ------------------------------------------------------------------

    /**
     * Process a user message through the full multi-agent pipeline.
     *
     * @param userMessage     Raw text typed by the user.
     * @param conversationId  Scopes short-term context and memory writes.
     * @return [Result.success] with the final agent response string, or
     *         [Result.failure] with a descriptive error.
     */
    suspend fun processUserMessage(
        userMessage: String,
        conversationId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val correlationId = UUID.randomUUID().toString()
        Log.d(TAG, "Pipeline start [correlationId=$correlationId]")

        try {
            // ── Stage 0: Intercept special commands ───────────────────────────
            if (::conversationProgrammer.isInitialized) {
                val cmdResult = conversationProgrammer.processMessage(userMessage)
                if (cmdResult.isCommand) {
                    saveUserMessage(userMessage, conversationId)
                    saveAgentMessage(cmdResult.response, conversationId, agentId = null)
                    auditLogger.logAction(
                        type = AuditLog.AuditLogType.SYSTEM,
                        action = "command_intercepted",
                        details = userMessage,
                        correlationId = correlationId
                    )
                    return@withContext Result.success(cmdResult.response)
                }
            }

            // ── Stage 1: Persist user message + fetch short-term context ──────
            saveUserMessage(userMessage, conversationId)
            val recentMessages = messageDao.getRecentMessages(conversationId, MAX_CONTEXT_MESSAGES)
            val historyMessages = recentMessages.map { msg ->
                Message(
                    role = when (msg.senderType) {
                        SenderType.USER -> "user"
                        SenderType.AGENT -> "assistant"
                        else -> "system"
                    },
                    content = msg.content
                )
            }

            // ── Stage 2: Semantic memory retrieval (long-term context) ────────
            // Uses vector cosine-similarity ranking; falls back to keyword LIKE
            // for legacy rows without embeddings.
            val memorySnippets: List<Memory> = getRelevantMemories(userMessage)
            val memorySystemBlock: Message? = if (memorySnippets.isNotEmpty()) {
                val memoryText = memorySnippets.joinToString("\n") { m ->
                    "[Memory] ${m.key}: ${m.value}"
                }
                Message(role = "system", content = "Relevant memories:\n$memoryText")
            } else null

            // ── Stage 3: Classify intent ──────────────────────────────────────
            val (intentLabel, intentScore) = classifyIntent(userMessage)
            Log.d(TAG, "Stage 3 — intent=$intentLabel score=$intentScore")

            // ── Stage 4: Route and execute agents ─────────────────────────────
            val client = fallbackLLMClient
                ?: return@withContext Result.failure(Exception("LLM client não inicializado"))

            val coordinator = agentDao.getAgentsByType("COORDINATOR").firstOrNull()
                ?: return@withContext Result.failure(Exception("Agente coordenador não encontrado"))

            // Build routing payload for coordinator
            val routingMessages = buildList {
                add(Message(role = "system", content = coordinator.systemPrompt))
                memorySystemBlock?.let { add(it) }
                addAll(historyMessages)
                add(Message(
                    role = "user",
                    content = buildRoutingPrompt(userMessage, intentLabel, intentScore)
                ))
            }

            val routingResponse = client.sendMessage(routingMessages).getOrNull()
                ?: return@withContext Result.failure(Exception("Falha na decisão de agentes"))

            auditLogger.logAction(
                type = AuditLog.AuditLogType.AGENT,
                action = "coordinator_routing",
                agentId = coordinator.id,
                agentName = coordinator.name,
                details = routingResponse.choices.firstOrNull()?.message?.content.orEmpty(),
                correlationId = correlationId
            )

            // Determine which agent IDs were selected by the coordinator
            val agentDecision = parseAgentDecision(
                routingResponse.choices.firstOrNull()?.message?.content.orEmpty(),
                coordinator
            )

            // Execute each selected agent sequentially
            val agentResponses = mutableListOf<String>()
            for (agentId in agentDecision.selectedAgents) {
                val agent = agentDao.getAgentById(agentId) ?: continue
                val agentMessages = buildList {
                    add(Message(role = "system", content = agent.systemPrompt))
                    memorySystemBlock?.let { add(it) }  // inject long-term memory
                    addAll(historyMessages)              // inject short-term context
                    add(Message(role = "user", content = userMessage))
                }
                val agentResponse = client.sendMessage(
                    messages = agentMessages
                ).getOrNull() ?: continue

                val text = agentResponse.choices.firstOrNull()?.message?.content ?: continue
                agentResponses.add(text)

                agentManager.recordAgentUsage(agentId)
                auditLogger.logAction(
                    type = AuditLog.AuditLogType.AGENT,
                    action = "agent_response",
                    agentId = agent.id,
                    agentName = agent.name,
                    details = text,
                    correlationId = correlationId
                )
            }

            if (agentResponses.isEmpty()) {
                return@withContext Result.failure(Exception("Nenhum agente conseguiu responder"))
            }

            val finalResponse = agentResponses.joinToString("\n\n---\n\n")

            // ── Stage 5: Optional cross-audit ─────────────────────────────────
            val settings = settingsDao.getSettingsSync()
            if (settings?.auditEnabled == true) {
                val auditor = agentDao.getAgentsByType("AUDITOR").firstOrNull()
                if (auditor != null) {
                    val auditMessages = listOf(
                        Message(role = "system", content = auditor.systemPrompt),
                        Message(
                            role = "user",
                            content = "Audite a seguinte resposta:\n$finalResponse"
                        )
                    )
                    val auditResponse = client.sendMessage(auditMessages).getOrNull()
                    val auditText = auditResponse?.choices?.firstOrNull()?.message?.content
                    if (auditText?.contains("REPROVADO") == true) {
                        Log.w(TAG, "[correlationId=$correlationId] Audit REPROVADO: $auditText")
                    }
                    auditLogger.logAction(
                        type = AuditLog.AuditLogType.SYSTEM,
                        action = "cross_audit",
                        details = auditText.orEmpty(),
                        correlationId = correlationId
                    )
                }
            }

            // ── Stage 6: Persist agent response ───────────────────────────────
            saveAgentMessage(finalResponse, conversationId, agentId = coordinator.id)

            // ── Stage 7: Optional memory update ───────────────────────────────
            if (settings?.memoryEnabled == true) {
                updateMemory(
                    userMessage = userMessage,
                    response = finalResponse,
                    conversationId = conversationId
                )
            }

            Log.d(TAG, "Pipeline complete [correlationId=$correlationId]")
            Result.success(finalResponse)

        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error [correlationId=$correlationId]", e)
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    // Stage 2 helper — semantic memory retrieval
    // ------------------------------------------------------------------

    /**
     * Retrieves up to [MAX_MEMORY_SNIPPETS] semantically relevant memories
     * for [query] using [MemorySearchEngine].
     *
     * This method is always non-throwing: any error from the search engine
     * or DAO is caught and an empty list is returned, preserving pipeline
     * continuity.  The agent system operates without memory context rather
     * than failing hard.
     */
    private suspend fun getRelevantMemories(query: String): List<Memory> {
        return try {
            memorySearchEngine.search(query = query, topK = MAX_MEMORY_SNIPPETS)
        } catch (e: Exception) {
            Log.w(TAG, "getRelevantMemories failed — returning empty list", e)
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Stage 3 helper — intent classification
    // ------------------------------------------------------------------

    private fun classifyIntent(text: String): Pair<String?, Float?> {
        val tokens = text.lowercase().split(" ")
        fun score(keywords: Set<String>): Float {
            val matched = tokens.count { it in keywords }.toFloat()
            return (matched / tokens.size.coerceAtLeast(1) * 5f).coerceAtMost(1f)
        }

        val scores = mapOf(
            "research" to score(RESEARCH_KEYWORDS),
            "planning" to score(PLANNING_KEYWORDS),
            "execution" to score(EXECUTION_KEYWORDS)
        )
        val best = scores.maxByOrNull { it.value } ?: return null to null
        return if (best.value > INTENT_THRESHOLD) best.key to best.value else null to null
    }

    // ------------------------------------------------------------------
    // Routing helpers
    // ------------------------------------------------------------------

    private fun buildRoutingPrompt(
        userMessage: String,
        intentLabel: String?,
        intentScore: Float?
    ): String {
        val intentHint = if (intentLabel != null && intentScore != null && intentScore > INTENT_THRESHOLD) {
            "\nIntent classificado: $intentLabel (score=$intentScore)"
        } else ""
        return """Mensagem do usuário: $userMessage$intentHint

Responda com JSON no formato:
{"selectedAgents":["agentId1"],"reasoning":"...","executionOrder":"sequential"}"""
    }

    private data class AgentDecision(
        val selectedAgents: List<String>,
        val reasoning: String,
        val executionOrder: String
    )

    private fun parseAgentDecision(
        text: String,
        coordinator: com.agente.autonomo.data.entity.Agent
    ): AgentDecision {
        return try {
            val gson = com.google.gson.Gson()
            gson.fromJson(text, AgentDecision::class.java)
                ?: AgentDecision(listOf(coordinator.id), "fallback", "sequential")
        } catch (_: Exception) {
            AgentDecision(listOf(coordinator.id), "parse_error", "sequential")
        }
    }

    // ------------------------------------------------------------------
    // Persistence helpers
    // ------------------------------------------------------------------

    private suspend fun saveUserMessage(content: String, conversationId: String) {
        val msg = com.agente.autonomo.data.entity.Message(
            conversationId = conversationId,
            agentId = null,
            senderType = SenderType.USER,
            content = content,
            timestamp = Date()
        )
        messageDao.insertMessage(msg)
    }

    private suspend fun saveAgentMessage(
        content: String,
        conversationId: String,
        agentId: String?
    ) {
        val msg = com.agente.autonomo.data.entity.Message(
            conversationId = conversationId,
            agentId = agentId,
            senderType = SenderType.AGENT,
            content = content,
            timestamp = Date()
        )
        messageDao.insertMessage(msg)
    }

    /**
     * Stage 7: Write a CONTEXT memory for this conversation turn.
     *
     * Also computes and persists an embedding for the new memory row so
     * future searches benefit from vector similarity immediately without
     * waiting for the back-fill worker.
     */
    private suspend fun updateMemory(
        userMessage: String,
        response: String,
        conversationId: String
    ) {
        try {
            val memoryText = "Usuário: $userMessage\nResposta: ${response.take(200)}"
            val memory = Memory(
                id = UUID.randomUUID().toString(),
                type = Memory.MemoryType.CONTEXT,
                key = "conversation_${conversationId}_${System.currentTimeMillis()}",
                value = memoryText,
                conversationId = conversationId,
                importance = 5
            )
            // Eagerly embed so the row is immediately retrievable by vector search
            val engine = try {
                EmbeddingEngine.getInstance(context)
            } catch (e: Exception) {
                Log.w(TAG, "EmbeddingEngine unavailable during memory write", e)
                null
            }
            val embeddedMemory = if (engine != null) {
                val vec = engine.embed(memory.key + ": " + memory.value)
                val blob = EmbeddingEngine.floatArrayToBytes(vec)
                memory.copy(embedding = blob)
            } else {
                memory
            }
            memoryDao.insertMemory(embeddedMemory)
        } catch (e: Exception) {
            // Stage 7 is best-effort — never propagate to caller
            Log.w(TAG, "updateMemory failed (non-fatal)", e)
        }
    }
}
