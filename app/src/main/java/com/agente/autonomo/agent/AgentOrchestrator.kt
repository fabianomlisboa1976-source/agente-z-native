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
 * AgentOrchestrator — Multi-Agent Message Routing Pipeline
 * =========================================================
 * Entry point for all user messages that require LLM processing.
 *
 * ## processUserMessage Pipeline Overview
 *
 * `processUserMessage(userMessage, conversationId)` orchestrates a sequential
 * multi-stage pipeline for every user turn.  All I/O is dispatched on
 * Dispatchers.IO; callers may invoke from any coroutine context.
 *
 * ### Pipeline Stages (in order)
 *
 * **Stage 0 — Special-Command Interception**
 *   Checks userMessage against a defined set of command patterns (e.g. /reset,
 *   /help, /status, and natural-language equivalents) via
 *   `detectSpecialCommand(userMessage): CommandMeta?`.
 *   Implementation: delegates to ConversationAgentProgrammer.processMessage().
 *   Guard: `::conversationProgrammer.isInitialized` must be true (set by
 *   initializeLLM()).  If the guard fails, command interception is silently
 *   bypassed and processing continues to Stage 1.
 *   On match (isCommand == true):
 *     - User message and command response are persisted to MessageDao.
 *     - An audit record is written with the shared correlationId.
 *     - Result.success(commandResponse) is returned immediately.
 *     - **Stages 1–5 do NOT execute.**
 *
 * **Stage 1 — Short-term Context (recent messages)**
 *   Calls `MessageDao.getRecentMessages(conversationId, MAX_CONTEXT_MESSAGES)`.
 *   Return shape: `List<entity.Message>` ordered by timestamp DESC (newest first).
 *   MAX_CONTEXT_MESSAGES = 10.  The result is mapped to `List<api.model.Message>`
 *   using SenderType → OpenAI role translation and stored in `PipelineContext
 *   .recentMessages`.
 *   Failure: propagates to outer catch → Result.failure.
 *
 * **Stage 2 — Long-term Memory Injection**
 *   Derives up to 5 keyword tokens from userMessage (lowercase, split on
 *   whitespace, drop tokens ≤ 3 chars, take first 5).  Calls
 *   `MemoryDao.searchMemories(keyword)` once per keyword (SQL LIKE against
 *   key/value/category columns, is_archived=0, sorted importance DESC).
 *   Post-aggregation: distinctBy { id }, sortedByDescending { importance }, take(5).
 *   Result is stored in `PipelineContext.memorySnippets`.
 *   Null / empty result: defaults to emptyList() — **never throws**.
 *   Error handling: all DB exceptions are caught internally → emptyList();
 *   memory retrieval failure is always non-fatal.
 *
 * **Stage 3 — Intent Classification and Agent Routing**
 *   Calls `classifyIntent(userMessage)` to derive an intentLabel and intentScore.
 *   Routing is then decided by three ordered criteria:
 *     (1) CommandMeta present → already handled in Stage 0 (never reached here).
 *     (2) intentScore > INTENT_THRESHOLD → route to specialist/sub-agent.
 *     (3) fallback → route to COORDINATOR (general-purpose default).
 *   Resolves the COORDINATOR agent via AgentManager.getCoordinatorAgent().
 *   Null coordinator → Result.failure (pipeline aborts).
 *   The full PipelineContext is passed as the sole argument to every downstream
 *   agent LLM call.
 *
 * **Stage 4 — Agent Execution**
 *   decideAgentsForTask() submits an LLM routing call (maxTokens=500,
 *   temperature=0.3) receiving [system: coordinator.systemPrompt] + [history:
 *   recentMessages] + [user: routing prompt].  Parses JSON AgentDecision.
 *   Parse failure → fallback AgentDecision(coordinator only, sequential).
 *   LLM null → Result.failure.
 *   executeWithAgents() then calls each selected agent sequentially, each
 *   receiving PipelineContext.  All non-null responses are concatenated with
 *   "\n\n---\n\n".  Zero responses → Result.failure.
 *
 * **Stage 5 — Optional Cross-Audit**
 *   Executed only when Settings.crossAuditEnabled == true.
 *   AUDITOR agent verifies response quality.  Failure → logWarning only;
 *   the response is still delivered to the caller.
 *
 * **Stage 6 — Persist Agent Response**
 *   Writes SenderType.AGENT row attributed to the coordinator.
 *
 * **Stage 7 — Optional Memory Update**
 *   Executed only when Settings.memoryEnabled == true.
 *   Creates a Memory(type=CONTEXT, importance=5) summarising this exchange.
 *
 * ## PipelineContext
 * Every downstream agent receives the same PipelineContext object containing:
 *   userMessage    : String      — raw user input
 *   recentMessages : List<Message> — short-term conversation history (Stage 1)
 *   memorySnippets : List<Memory>  — long-term relevant memories (Stage 2)
 *   commandMeta    : CommandResult? — non-null if Stage 0 matched (intercepted)
 *   intentLabel    : String?       — classification label from classifyIntent()
 *   intentScore    : Float?        — classification confidence score
 *
 * ## Correlation ID
 * Every auditLogger call within one processUserMessage invocation shares a single
 * correlationId = UUID.randomUUID().toString() generated at Stage 0, enabling
 * end-to-end tracing in the audit_logs table.
 *
 * ## Import Disambiguation
 * This file uses BOTH:
 *   com.agente.autonomo.api.model.Message  (API DTO, role+content)
 *   com.agente.autonomo.data.entity.Message (Room entity, senderType+content+...)
 * The unqualified 'Message' resolves to api.model.Message; entity.Message is always
 * fully qualified. Verify imports when modifying.
 */
class AgentOrchestrator(
    private val database: AppDatabase,
    private val agentManager: AgentManager,
    private val auditLogger: AuditLogger
) {

    companion object {
        const val TAG = "AgentOrchestrator"

        /**
         * Maximum number of past messages loaded as short-term conversation context
         * for every LLM call (Stage 1).
         *
         * Passed as the `limit` parameter of MessageDao.getRecentMessages().
         * Applies to both the routing-decision call (Stage 3/4) and every per-agent
         * execution call (Stage 4).
         *
         * Increase cautiously: larger values raise token consumption proportionally.
         */
        const val MAX_CONTEXT_MESSAGES = 10

        /**
         * Minimum confidence score returned by classifyIntent() that triggers routing
         * to a specialist agent rather than the default COORDINATOR fallback.
         *
         * Stage 3 routing criteria (in order):
         *   intentScore > INTENT_THRESHOLD → specialist agent
         *   else                            → COORDINATOR (general-purpose fallback)
         */
        const val INTENT_THRESHOLD = 0.6f

        /**
         * Recognised special-command prefixes and their natural-language equivalents.
         * Used by detectSpecialCommand() in Stage 0.
         *
         * Slash variants: /reset, /help, /status, /agents, /tasks
         * Natural-language variants are handled by ConversationAgentProgrammer
         * (Portuguese + English patterns).
         */
        val SLASH_COMMANDS = setOf("/reset", "/help", "/status", "/agents", "/tasks")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PipelineContext — passed as sole argument to every downstream agent
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Immutable context object assembled during the pre-processing stages
     * (Stages 0–3) and passed as the sole argument to every downstream agent
     * call in Stage 4.
     *
     * Fields:
     *   userMessage    — raw user input, never pre-processed
     *   recentMessages — short-term conversation history (Stage 1);
     *                    List<api.model.Message>, ORDER BY timestamp DESC,
     *                    capped at MAX_CONTEXT_MESSAGES (10)
     *   memorySnippets — long-term relevant memories (Stage 2);
     *                    List<entity.Memory>, sorted by importance DESC,
     *                    capped at 5; always non-null (defaults to emptyList())
     *   commandMeta    — non-null only when Stage 0 matched a command pattern;
     *                    when non-null the pipeline short-circuits before Stage 1
     *   intentLabel    — classification label from classifyIntent(); null if
     *                    classification was skipped or returned no result
     *   intentScore    — confidence score [0.0, 1.0] from classifyIntent();
     *                    null when intentLabel is null
     */
    data class PipelineContext(
        val userMessage: String,
        val recentMessages: List<Message>,
        val memorySnippets: List<com.agente.autonomo.data.entity.Memory>,
        val commandMeta: ConversationAgentProgrammer.CommandResult?,
        val intentLabel: String?,
        val intentScore: Float?
    )

    /**
     * LLM client, lazily initialised by initializeLLM().
     * Always null-check before use; null means no API key is configured or
     * initializeLLM() has not yet been called.
     */
    private var llmClient: LLMClient? = null

    /**
     * Processes natural-language agent-programming commands (create/modify/delete
     * agents and tasks).  Declared lateinit because it requires agentManager and
     * auditLogger which are constructor-injected.
     *
     * Guard: ::conversationProgrammer.isInitialized is checked before every use.
     * If not initialised (initializeLLM() never called), command interception is
     * silently bypassed — Stage 0 is effectively a no-op.
     */
    private lateinit var conversationProgrammer: ConversationAgentProgrammer

    // ─────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initialises the LLM client and the ConversationAgentProgrammer.
     *
     * Must be called before processUserMessage() to enable:
     *   - LLM API calls (Stages 3–5)
     *   - Special-command interception (Stage 0)
     *
     * Safe to call multiple times; a new LLMClient is created each time so that
     * settings changes (new API key, provider switch) are picked up immediately.
     *
     * If Settings.apiKey is blank, llmClient remains null and all LLM calls will
     * return null, causing Result.failure at the routing step.
     */
    suspend fun initializeLLM() {
        val settings = database.settingsDao().getSettingsSync()
        if (settings != null && settings.apiKey.isNotBlank()) {
            llmClient = LLMClient(settings)
        }
        // conversationProgrammer only needs DB + agentManager + auditLogger;
        // initialise unconditionally so command interception works even without
        // a configured API key.
        conversationProgrammer = ConversationAgentProgrammer(database, agentManager, auditLogger)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Primary Entry Point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Processes a user message through the full multi-agent pipeline.
     *
     * Entry point for all user messages that require LLM processing.  Assembles
     * a [PipelineContext] across Stages 0–3 and passes it to downstream agents
     * in Stage 4.
     *
     * @param userMessage    Raw user input exactly as typed; never pre-processed.
     *   Also used as the search query for Stage 2 memory retrieval (first 5 keywords
     *   derived from lowercase tokenisation, tokens > 3 chars, up to 5 taken).
     * @param conversationId Logical conversation bucket used to scope message
     *   history (Stage 1) and memory queries (Stage 2). Defaults to "default".
     *   Use distinct IDs for parallel conversations.
     * @return Result.success(responseText) on success; Result.failure(exception)
     *   on any non-recoverable error.  Partial-failure handling is documented
     *   per stage above.
     *
     * All I/O is dispatched on Dispatchers.IO via withContext; callers may invoke
     * this from any coroutine context.
     *
     * ## Control-flow summary
     * ```
     * processUserMessage(userMessage, conversationId)
     *   Stage 0: detectSpecialCommand(userMessage)
     *     ↳ match  → persist messages, audit, return Result.success (short-circuit)
     *     ↳ no match → continue
     *   Stage 1: MessageDao.getRecentMessages(conversationId, 10)
     *     → PipelineContext.recentMessages: List<Message> (DESC timestamp, newest first)
     *   Stage 2: MemoryDao.searchMemories(keyword) × up to 5 keywords
     *     → PipelineContext.memorySnippets: List<Memory> (importance DESC, max 5)
     *     → null / DB error → defaulted to [] without throwing
     *   Stage 3: classifyIntent(userMessage)
     *     → PipelineContext.intentLabel, intentScore
     *     → routing: intentScore > INTENT_THRESHOLD → specialist
     *               else                              → COORDINATOR fallback
     *   Stage 4: decideAgentsForTask() + executeWithAgents(PipelineContext)
     *     → concatenated responses or Result.failure if every agent returned null
     *   Stage 5: performCrossAudit() [if Settings.crossAuditEnabled]
     *   Stage 6: saveAgentMessage()
     *   Stage 7: updateMemory() [if Settings.memoryEnabled]
     *   → Result.success(finalResponse)
     * ```
     */
    suspend fun processUserMessage(
        userMessage: String,
        conversationId: String = "default"
    ): Result<String> = withContext(Dispatchers.IO) {

        // A single UUID ties every auditLogger call in this invocation together,
        // enabling end-to-end tracing in the audit_logs table.
        val correlationId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        try {
            // ──────────────────────────────────────────────────────────────────
            // Stage 0: Intercept special commands
            // ──────────────────────────────────────────────────────────────────
            // detectSpecialCommand() checks whether userMessage matches any
            // registered command pattern (slash commands: /reset, /help, /status,
            // /agents, /tasks; plus PT/EN natural-language patterns delegated to
            // ConversationAgentProgrammer).
            //
            // On match (isCommand == true):
            //   - User message and command response are persisted.
            //   - An audit record is written.
            //   - Result.success is returned immediately; Stages 1–7 are skipped.
            //
            // Guard: conversationProgrammer must be initialised (initializeLLM
            // called at least once).  If not, interception is silently skipped.
            if (::conversationProgrammer.isInitialized) {
                val commandResult = conversationProgrammer.processMessage(userMessage)
                if (commandResult.isCommand) {
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

            // ──────────────────────────────────────────────────────────────────
            // Stage 1: Load short-term context (recent messages)
            // ──────────────────────────────────────────────────────────────────
            // Calls MessageDao.getRecentMessages(conversationId, MAX_CONTEXT_MESSAGES).
            //
            // Parameters:
            //   conversationId      : scopes the query to the current conversation
            //   limit               : MAX_CONTEXT_MESSAGES = 10
            //
            // Return shape: List<entity.Message>, ORDER BY timestamp DESC (newest first).
            // Mapped to List<api.model.Message> via SenderType → OpenAI role translation:
            //   SenderType.USER   → "user"
            //   SenderType.AGENT  → "assistant"
            //   SenderType.SYSTEM → "system"
            //
            // Stored in PipelineContext.recentMessages and injected between the
            // system prompt and the current user turn in every LLM call.
            //
            // NOTE: User message is persisted here (before context load) so that
            // this turn is included in subsequent getRecentMessages() calls made
            // by any downstream component within the same pipeline invocation.
            //
            // Failure: DB exception propagates to outer catch → Result.failure.
            saveUserMessage(userMessage, conversationId)
            val recentMessages = getConversationContext(conversationId)

            // ──────────────────────────────────────────────────────────────────
            // Stage 2: Inject long-term memory
            // ──────────────────────────────────────────────────────────────────
            // Derives a search query from userMessage:
            //   userMessage.lowercase().split(" ").filter { it.length > 3 }.take(5)
            // Each token becomes one call to MemoryDao.searchMemories(keyword).
            //
            // DAO query per keyword:
            //   SQL   : LIKE '%<keyword>%' on columns key, value, category
            //   Filter: is_archived = 0 (active memories only)
            //   Sort  : importance DESC, access_count DESC
            //
            // Post-aggregation: distinctBy { id }, sortedByDescending { importance },
            //   take(5).  Stored in PipelineContext.memorySnippets.
            //
            // Null / empty result: defaults to emptyList() — NEVER throws.
            // All DB exceptions are caught internally; memory retrieval failure
            // is always non-fatal to the pipeline.
            val memorySnippets = getRelevantMemories(userMessage)

            // ──────────────────────────────────────────────────────────────────
            // Stage 3: Classify intent and select routing target
            // ──────────────────────────────────────────────────────────────────
            // Calls classifyIntent(userMessage) to derive intentLabel and
            // intentScore.  Routing decisions are applied in the following order:
            //
            //   (1) CommandMeta present → already handled in Stage 0 (unreachable here)
            //   (2) intentScore > INTENT_THRESHOLD (0.6) → route to specialist agent
            //   (3) fallback → route to COORDINATOR (general-purpose default)
            //
            // Resolves the COORDINATOR agent via AgentManager.getCoordinatorAgent().
            // Null coordinator → Result.failure (pipeline aborts).
            //
            // The PipelineContext assembled here is passed as the sole argument
            // to every downstream agent call in Stage 4.
            val (intentLabel, intentScore) = classifyIntent(userMessage)

            val coordinator = agentManager.getCoordinatorAgent()
                ?: return@withContext Result.failure(
                    Exception("Agente coordenador não encontrado")
                )

            // Build the immutable PipelineContext that travels through the rest
            // of the pipeline; every downstream agent receives this same object.
            val pipelineContext = PipelineContext(
                userMessage = userMessage,
                recentMessages = recentMessages,
                memorySnippets = memorySnippets,
                commandMeta = null,  // Stage 0 already handled commands; null here
                intentLabel = intentLabel,
                intentScore = intentScore
            )

            auditLogger.logAction(
                action = "Contexto do pipeline construído",
                agentId = coordinator.id,
                details = "recentMessages=${recentMessages.size}, " +
                    "memorySnippets=${memorySnippets.size}, " +
                    "intentLabel=$intentLabel, intentScore=$intentScore",
                correlationId = correlationId
            )

            // ──────────────────────────────────────────────────────────────────
            // Stage 4: Classify intent, route, and execute agents
            // ──────────────────────────────────────────────────────────────────
            // decideAgentsForTask() submits an LLM routing call (maxTokens=500,
            // temperature=0.3) that receives:
            //   [system]  coordinator.systemPrompt
            //   [history] pipelineContext.recentMessages
            //   [user]    routing prompt with enumerated active agents + userMessage
            //
            // PipelineContext.memorySnippets are serialised and prepended to the
            // routing prompt so the LLM is aware of relevant long-term memory.
            //
            // Expected LLM JSON response:
            //   { "selectedAgents": ["agentId1", ...],
            //     "reasoning": "...",
            //     "executionOrder": "sequential" | "parallel" }
            //
            // Parse failure → fallback AgentDecision(coordinator only, sequential).
            // LLM null → Result.failure.
            //
            // executeWithAgents() then calls each selected agent sequentially.
            // Each agent receives PipelineContext as its execution context:
            //   [system: agent.systemPrompt]
            //   + [memory context from pipelineContext.memorySnippets]
            //   + [history: pipelineContext.recentMessages]
            //   + [user: pipelineContext.userMessage]
            //
            // NOTE: intentScore > INTENT_THRESHOLD may have pre-selected a
            // specialist agent in Stage 3; that preference is encoded into the
            // routing prompt so the LLM honours it.
            val agentDecision = decideAgentsForTask(
                coordinator,
                pipelineContext,
                intentLabel,
                intentScore
            ) ?: return@withContext Result.failure(
                Exception("Falha na decisão de agentes")
            )

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
                pipelineContext,
                correlationId
            )

            // ──────────────────────────────────────────────────────────────────
            // Stage 5: Optional cross-audit
            // ──────────────────────────────────────────────────────────────────
            // Executed only when Settings.crossAuditEnabled == true.
            // Submits the final response to the first AgentType.AUDITOR agent for
            // quality review (maxTokens=500, temperature=0.2).
            // Detects "REPROVADO" string in auditor response.
            // Audit failure → logWarning only; the response is still delivered.
            val settings = database.settingsDao().getSettingsSync()
            if (settings?.crossAuditEnabled == true) {
                val auditResult = performCrossAudit(
                    pipelineContext.userMessage,
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

            // ──────────────────────────────────────────────────────────────────
            // Stage 6: Persist agent response
            // ──────────────────────────────────────────────────────────────────
            // Writes a SenderType.AGENT row attributed to the coordinator.
            // Note: when multiple agents responded, individual attributions are
            // available only in the audit log; the DB row always uses coordinator.
            val finalResponse = executionResult.getOrThrow()
            saveAgentMessage(finalResponse, conversationId, coordinator.id, coordinator.name)

            // ──────────────────────────────────────────────────────────────────
            // Stage 7: Optional memory update
            // ──────────────────────────────────────────────────────────────────
            // Executed only when Settings.memoryEnabled == true.
            // Creates a Memory(type=CONTEXT, importance=5) summarising this exchange.
            // response is truncated to 200 chars before storage.
            // KNOWN ISSUE: failure here propagates to outer catch and returns
            // Result.failure even though the LLM response was obtained successfully.
            // Consider wrapping in try/catch to make memory persistence non-critical.
            if (settings?.memoryEnabled == true) {
                updateMemory(pipelineContext.userMessage, finalResponse, conversationId)
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
            // Catch-all: any unhandled exception from Stages 0–7 lands here.
            // The error is logged to the audit trail before surfacing to the caller.
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

    // ─────────────────────────────────────────────────────────────────────────
    // Task Execution (direct, bypasses routing pipeline)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes a scheduled Task directly, bypassing the full message pipeline.
     *
     * Resolves the assigned agent (or falls back to coordinator), builds a
     * task-execution prompt, and calls the LLM once.
     *
     * @throws Exception if no agent is available or the LLM call fails.
     */
    suspend fun executeTask(task: Task): String = withContext(Dispatchers.IO) {
        val agent = task.assignedAgent?.let { agentManager.getAgent(it) }
            ?: agentManager.getCoordinatorAgent()
            ?: throw Exception("Nenhum agente disponível")

        val messages = listOf(
            Message(role = "system", content = agent.systemPrompt),
            Message(
                role = "user",
                content = "Execute a seguinte tarefa: ${task.description}\n\n" +
                    "Parâmetros: ${task.parameters ?: "Nenhum"}"
            )
        )

        val response = callLLM(messages, agent.maxTokens, agent.temperature)
            ?: throw Exception("Falha ao executar tarefa")

        agentManager.recordAgentUsage(agent.id)

        response
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private Pipeline Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Stage 0 helper — checks userMessage against slash-command prefixes and
     * delegates natural-language pattern matching to ConversationAgentProgrammer.
     *
     * Recognised slash commands: /reset, /help, /status, /agents, /tasks
     * (defined in SLASH_COMMANDS companion set).
     * Natural-language commands (PT/EN) are handled by ConversationAgentProgrammer.
     *
     * This function is NOT called directly in processUserMessage; the
     * ConversationAgentProgrammer delegation already covers both slash and
     * natural-language variants.  It exists as a testable, isolated helper for
     * slash-command detection that can be unit-tested independently.
     *
     * @return CommandResult with isCommand=true on match, isCommand=false otherwise.
     */
    internal fun detectSpecialCommand(message: String): Boolean {
        val trimmed = message.trim().lowercase()
        return SLASH_COMMANDS.any { trimmed.startsWith(it) }
    }

    /**
     * Stage 3 helper — lightweight intent classifier.
     *
     * Current implementation uses keyword heuristics to assign a label and a
     * confidence score without an extra LLM call, keeping Stage 3 fast.
     *
     * Routing criteria (applied in processUserMessage Stage 3):
     *   intentScore > INTENT_THRESHOLD (0.6) → route to specialist agent
     *   else                                  → COORDINATOR fallback
     *
     * Returns a Pair<String?, Float?> where:
     *   first  = intentLabel  (e.g. "research", "planning", "execution", null)
     *   second = intentScore  ([0.0, 1.0], null when label is null)
     *
     * Extend this function with an actual ML classifier or a fast LLM call
     * as the system matures.
     */
    internal fun classifyIntent(userMessage: String): Pair<String?, Float?> {
        val lower = userMessage.lowercase()

        // Research-related keywords
        val researchKeywords = listOf(
            "pesquisa", "busca", "encontra", "procura", "research",
            "find", "search", "look up", "information", "what is", "o que é"
        )

        // Planning-related keywords
        val planningKeywords = listOf(
            "plano", "planejamento", "planejar", "etapas", "passos",
            "plan", "planning", "steps", "strategy", "estratégia"
        )

        // Execution-related keywords
        val executionKeywords = listOf(
            "execute", "executar", "fazer", "realizar", "implementa",
            "run", "do", "create", "build", "cria", "construir"
        )

        val researchScore = researchKeywords.count { lower.contains(it) }.toFloat() /
            researchKeywords.size
        val planningScore = planningKeywords.count { lower.contains(it) }.toFloat() /
            planningKeywords.size
        val executionScore = executionKeywords.count { lower.contains(it) }.toFloat() /
            executionKeywords.size

        // Normalise: amplify sparse keyword matches for short messages
        val amplifiedResearch = minOf(researchScore * 5f, 1.0f)
        val amplifiedPlanning = minOf(planningScore * 5f, 1.0f)
        val amplifiedExecution = minOf(executionScore * 5f, 1.0f)

        return when {
            amplifiedResearch >= amplifiedPlanning && amplifiedResearch >= amplifiedExecution
                && amplifiedResearch > INTENT_THRESHOLD ->
                Pair("research", amplifiedResearch)

            amplifiedPlanning >= amplifiedResearch && amplifiedPlanning >= amplifiedExecution
                && amplifiedPlanning > INTENT_THRESHOLD ->
                Pair("planning", amplifiedPlanning)

            amplifiedExecution > INTENT_THRESHOLD ->
                Pair("execution", amplifiedExecution)

            else -> Pair(null, null)
        }
    }

    /**
     * Stage 4 implementation — submits a routing prompt to the coordinator LLM
     * and parses the JSON AgentDecision response.
     *
     * Receives the assembled [PipelineContext] so it can include both
     * pipelineContext.recentMessages (short-term context) and serialised
     * pipelineContext.memorySnippets (long-term memory) in the routing prompt.
     *
     * When intentScore > INTENT_THRESHOLD, the routing prompt explicitly requests
     * inclusion of the matching specialist agent type (intentLabel) in
     * selectedAgents so the LLM honours the Stage 3 classification.
     *
     * LLM call parameters: maxTokens=500, temperature=0.3
     *
     * Returns null only when callLLM() returns null (LLM unavailable).
     * JSON parse failures are caught and replaced with a safe coordinator-only fallback.
     *
     * @param coordinator   The coordinator Agent entity (provides systemPrompt).
     * @param context       Assembled PipelineContext from Stages 1–3.
     * @param intentLabel   Intent classification label from Stage 3 (may be null).
     * @param intentScore   Intent classification score from Stage 3 (may be null).
     */
    private suspend fun decideAgentsForTask(
        coordinator: Agent,
        context: PipelineContext,
        intentLabel: String?,
        intentScore: Float?
    ): AgentDecision? {
        // Serialise memory snippets for the routing prompt so the LLM can
        // incorporate long-term memory context into its agent-selection decision.
        val memorySummary = if (context.memorySnippets.isNotEmpty()) {
            "\nContexto de memória relevante:\n" +
                context.memorySnippets.joinToString("\n") { memory ->
                    "  - [${memory.type.name}] ${memory.key}: ${memory.value.take(100)}"
                }
        } else {
            ""  // No memory snippets; omit section entirely
        }

        // When Stage 3 classified intent with sufficient confidence, hint the LLM
        // to include the matching specialist agent type in its selection.
        val intentHint = if (intentLabel != null && intentScore != null &&
            intentScore > INTENT_THRESHOLD
        ) {
            // (2) intentScore > INTENT_THRESHOLD → prefer specialist agent
            "\nNota: A intenção detectada é '$intentLabel' (confiança: ${
                String.format("%.2f", intentScore)
            }). " +
                "Inclua preferencialmente um agente especialista do tipo '$intentLabel' " +
                "na lista selectedAgents."
        } else {
            // (3) fallback → coordinator handles as general-purpose agent
            "\nNota: Nenhuma intenção especialista detectada. " +
                "O agente coordenador pode lidar com a solicitação diretamente."
        }

        // The routing prompt enumerates every active agent so the LLM can make
        // an informed selection.  getAvailableAgentsDescription() queries AgentDao
        // for all agents where isActive = true.
        val decisionPrompt = """Analise a solicitação do usuário e decida quais agentes devem atuar.

Agentes disponíveis:
${getAvailableAgentsDescription()}$memorySummary$intentHint

Solicitação do usuário: "${context.userMessage}"

Responda APENAS no seguinte formato JSON:
{
  "selectedAgents": ["id_agente1", "id_agente2"],
  "reasoning": "Explicação da decisão",
  "executionOrder": "parallel" ou "sequential"
}"""

        val messages = buildList {
            add(Message(role = "system", content = coordinator.systemPrompt))
            addAll(context.recentMessages)  // short-term context from Stage 1
            add(Message(role = "user", content = decisionPrompt))
        }

        val response = callLLM(messages, maxTokens = 500, temperature = 0.3f)
            ?: return null  // propagated as Result.failure in processUserMessage

        return try {
            parseAgentDecision(response)
        } catch (e: Exception) {
            // Fallback: single coordinator agent, sequential order.
            // Handles malformed JSON, missing fields, type mismatches, etc.
            AgentDecision(
                selectedAgents = listOf(coordinator.id),
                reasoning = "Fallback para coordenador devido a erro no parsing",
                executionOrder = "sequential"
            )
        }
    }

    /**
     * Stage 4 execution — calls each selected agent's LLM sequentially.
     *
     * Receives the full [PipelineContext] and passes it to each agent call so
     * every agent has access to:
     *   - pipelineContext.userMessage    (original user turn)
     *   - pipelineContext.recentMessages (short-term history, Stage 1)
     *   - pipelineContext.memorySnippets (long-term memory, Stage 2)
     *   - pipelineContext.intentLabel / intentScore (Stage 3 classification)
     *
     * Context payload delivered to each agent's LLM call:
     *   index 0          : Message(role="system", content=agent.systemPrompt)
     *   [memory section] : serialised pipelineContext.memorySnippets (if non-empty)
     *   indices 1..N     : pipelineContext.recentMessages (history, DESC order)
     *   last index       : Message(role="user", content=pipelineContext.userMessage)
     *
     * Agent-level LLM parameters:
     *   maxTokens   : agent.maxTokens   (entity field, may be null → LLMClient default)
     *   temperature : agent.temperature (entity field, may be null → LLMClient default)
     *
     * Per-agent failures (unknown ID, null LLM response) are silently skipped.
     *
     * @return Result.success(concatenatedResponses) or
     *         Result.failure if every agent returned null.
     */
    private suspend fun executeWithAgents(
        agentIds: List<String>,
        context: PipelineContext,
        correlationId: String
    ): Result<String> {
        val results = mutableListOf<String>()

        // Serialise memory snippets once; reuse for every agent call to avoid
        // re-allocating the same string N times inside the loop.
        val memoryContext = if (context.memorySnippets.isNotEmpty()) {
            "Memórias relevantes:\n" +
                context.memorySnippets.joinToString("\n") { memory ->
                    "  [${memory.type.name}] ${memory.key}: ${memory.value.take(150)}"
                } + "\n\n"
        } else {
            ""  // No memory snippets; omit memory section
        }

        for (agentId in agentIds) {
            // Unknown agent IDs: silently skip.  This can happen if an agent was
            // deleted between the routing decision (Stage 3/4) and execution.
            val agent = agentManager.getAgent(agentId) ?: continue

            // Build the context payload for this agent call.
            // Memory snippets (Stage 2) are injected as a system-level message
            // immediately after the agent's own system prompt so the LLM treats
            // them as authoritative background knowledge.
            val messages = buildList {
                add(Message(role = "system", content = agent.systemPrompt))
                // Inject long-term memory as an additional system context segment
                if (memoryContext.isNotEmpty()) {
                    add(Message(role = "system", content = memoryContext))
                }
                addAll(context.recentMessages)  // short-term history (Stage 1)
                add(Message(role = "user", content = context.userMessage))
            }

            val response = callLLM(messages, agent.maxTokens, agent.temperature)

            if (response != null) {
                results.add(response)
                // Increment usageCount on the Agent entity in the DB.
                agentManager.recordAgentUsage(agent.id)

                auditLogger.logAction(
                    action = "Resposta do agente ${agent.name}",
                    agentId = agent.id,
                    agentName = agent.name,
                    outputData = response.take(500),
                    correlationId = correlationId
                )
            }
            // Null response: silently skipped; no warning logged.
        }

        return if (results.isNotEmpty()) {
            // Multiple agent responses are concatenated; no synthesis step exists yet.
            Result.success(results.joinToString("\n\n---\n\n"))
        } else {
            Result.failure(Exception("Nenhum agente conseguiu responder"))
        }
    }

    /**
     * Stage 5 implementation — submits the final response to an AUDITOR agent.
     *
     * LLM call parameters: maxTokens=500, temperature=0.2 (highly deterministic).
     * Detects "REPROVADO" substring in the auditor's response.
     *
     * Returns Result.failure if:
     *   - executionResult itself is a failure (propagated unchanged)
     *   - auditor LLM response contains "REPROVADO"
     * Returns Result.success if no auditor agent exists (audit is optional).
     *
     * Regardless of return value, processUserMessage() only logs a warning and
     * continues — audit failure does NOT suppress the response to the user.
     */
    private suspend fun performCrossAudit(
        originalRequest: String,
        responseResult: Result<String>,
        correlationId: String
    ): Result<Unit> {
        if (responseResult.isFailure) {
            return Result.failure(
                responseResult.exceptionOrNull() ?: Exception("Resposta falhou")
            )
        }

        // No AUDITOR agent configured → audit step is a no-op.
        val auditor = agentManager.getAgentsByType(Agent.AgentType.AUDITOR).firstOrNull()
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

    // ─────────────────────────────────────────────────────────────────────────
    // LLM Gateway
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Centralised LLM call gateway used by all pipeline stages.
     *
     * Lazily re-initialises llmClient if null (covers the case where
     * initializeLLM() was not called before the first processUserMessage()).
     *
     * Returns null (instead of throwing) when:
     *   - llmClient is null after re-init (no API key configured)
     *   - The LLM API returns a failure result
     *   - The response has no choices
     *   - Any exception is thrown (caught here; logged to audit trail)
     *
     * @param messages     Ordered list of api.model.Message for the completion call.
     * @param maxTokens    Token ceiling; null delegates to LLMClient/Settings default.
     * @param temperature  Sampling temperature; null delegates to LLMClient/Settings default.
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
            val result = client.sendMessage(
                messages,
                maxTokens = maxTokens,
                temperature = temperature
            )
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

    // ─────────────────────────────────────────────────────────────────────────
    // Database Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Persists a user-authored message to the messages table (Stage 1).
     *
     * Uses OnConflictStrategy.REPLACE.  The auto-generated Long id is discarded.
     * Failure propagates to the outer catch in processUserMessage.
     */
    private suspend fun saveUserMessage(content: String, conversationId: String) {
        val message = com.agente.autonomo.data.entity.Message(
            senderType = com.agente.autonomo.data.entity.Message.SenderType.USER,
            content = content,
            conversationId = conversationId
        )
        database.messageDao().insertMessage(message)
    }

    /**
     * Persists an agent-authored message to the messages table (Stages 0 and 6).
     *
     * @param agentId    The persisted agent's entity ID; "system" for command responses.
     * @param agentName  Display name shown in the UI; "Sistema" for command responses.
     *
     * NOTE: When multiple agents responded in Stage 4, this is called once with the
     * coordinator's ID/name.  Individual per-agent attributions are in the audit log.
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
     * Stage 1 implementation — loads conversation history for LLM context hydration.
     *
     * Calls: MessageDao.getRecentMessages(conversationId, MAX_CONTEXT_MESSAGES)
     *
     * Parameters:
     *   conversationId : scopes the query to the current conversation bucket
     *   limit          : MAX_CONTEXT_MESSAGES = 10
     *
     * Return shape:
     *   List<entity.Message>, ORDER BY timestamp DESC (newest first)
     *   Mapped to List<api.model.Message> via SenderType → OpenAI role translation:
     *     SenderType.USER   → "user"
     *     SenderType.AGENT  → "assistant"
     *     SenderType.SYSTEM → "system"
     *
     * The resulting list is stored in PipelineContext.recentMessages and injected
     * between the system prompt and the current user turn in every LLM call.
     *
     * NOTE: Messages arrive newest-first (DESC order).  Consider reversing before
     * injecting into prompts if chronological order is required.
     *
     * Failure propagates to the outer catch in processUserMessage → Result.failure.
     */
    private suspend fun getConversationContext(
        conversationId: String
    ): List<Message> {
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
     * Stage 2 implementation — retrieves semantically relevant memories.
     *
     * Derives a search query from userMessage:
     *   userMessage.lowercase().split(" ").filter { it.length > 3 }.take(5)
     * Each token becomes one call to MemoryDao.searchMemories(keyword).
     *
     * DAO query per keyword:
     *   SQL   : LIKE '%<keyword>%' on columns key, value, category
     *   Filter: is_archived = 0 (active memories only)
     *   Sort  : importance DESC, access_count DESC
     *
     * Post-aggregation: distinctBy { id }, sortedByDescending { importance }, take(5).
     * Stored in PipelineContext.memorySnippets.
     *
     * Null / empty result: always defaults to emptyList() — NEVER throws.
     * All DB exceptions are caught internally; memory retrieval failure is
     * always non-fatal to the pipeline.
     *
     * @param query  The raw user message; keywords are derived internally.
     * @return List<entity.Memory> of up to 5 relevant memories, or emptyList().
     */
    private suspend fun getRelevantMemories(
        query: String
    ): List<com.agente.autonomo.data.entity.Memory> {
        return try {
            val keywords = query.lowercase()
                .split(" ")
                .filter { it.length > 3 }  // drop short / stop-word tokens
                .take(5)                    // cap DB calls at 5

            if (keywords.isEmpty()) {
                return emptyList()
            }

            val memories = mutableListOf<com.agente.autonomo.data.entity.Memory>()

            for (keyword in keywords) {
                // searchMemories returns null on some DAO implementations; guard here
                // to ensure the Stage 2 contract (never throws, defaults to []) holds.
                val found = try {
                    database.memoryDao().searchMemories(keyword)
                } catch (inner: Exception) {
                    null  // individual keyword failure is non-fatal
                }
                if (found != null) {
                    memories.addAll(found)
                }
            }

            // De-duplicate across keywords, sort by importance, hard ceiling of 5.
            memories.distinctBy { it.id }
                .sortedByDescending { it.importance }
                .take(5)
        } catch (e: Exception) {
            // Non-fatal: pipeline continues with empty memory context.
            emptyList()
        }
    }

    /**
     * Stage 7 implementation — persists a summary of the current exchange as a
     * CONTEXT-type Memory.
     *
     * Memory fields:
     *   id             : UUID
     *   type           : MemoryType.CONTEXT
     *   key            : "conversation_<conversationId>_<epochMs>" (unique per message)
     *   value          : "Usuário: <userMessage>\nResposta: <response[:200]>"
     *   conversationId : scoping key
     *   importance     : 5 (mid-range, hardcoded)
     *
     * KNOWN ISSUE: failure here is NOT wrapped in its own try/catch.  A DB
     * error during memory write causes processUserMessage to return Result.failure
     * even though the LLM response was already obtained.  Consider wrapping in
     * try/catch to make memory persistence non-critical.
     */
    private suspend fun updateMemory(
        userMessage: String,
        response: String,
        conversationId: String
    ) {
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

    // ─────────────────────────────────────────────────────────────────────────
    // Utility Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the agent enumeration string injected into the routing prompt (Stage 4).
     *
     * Calls AgentManager.getAllActiveAgents().first() to get a snapshot of all
     * agents where isActive = true from AgentDao.
     *
     * Format per agent: "- {id}: {name} ({type}) - {description}"
     */
    private suspend fun getAvailableAgentsDescription(): String {
        val agents = agentManager.getAllActiveAgents().first()
        return agents.joinToString("\n") { agent ->
            "- ${agent.id}: ${agent.name} (${agent.type.name}) - ${agent.description}"
        }
    }

    /**
     * Parses the LLM routing response JSON into an AgentDecision.
     *
     * Uses Gson for deserialization.  Any exception (malformed JSON, wrong types,
     * null required fields) propagates to the caller (decideAgentsForTask),
     * which catches it and substitutes a safe fallback AgentDecision.
     */
    private fun parseAgentDecision(json: String): AgentDecision {
        val gson = com.google.gson.Gson()
        return gson.fromJson(json, AgentDecision::class.java)
    }

    /**
     * Returns the token count from the most recent LLM response.
     * TODO: implement actual token tracking via ChatCompletionResponse.usage.
     */
    private fun getLastTokenUsage(): Int {
        return 0
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal Data Structures
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Result of the LLM-assisted routing decision (Stage 4).
     *
     * Kotlin equivalent of the expected LLM JSON response:
     * {
     *   "selectedAgents": ["agentId1", "agentId2"],  // Agent.id values (String)
     *   "reasoning": "...",                           // logged to audit trail
     *   "executionOrder": "sequential" | "parallel"   // NOTE: parallel not yet implemented
     * }
     *
     * TypeScript-equivalent schema for cross-language reference:
     *   interface AgentDecision {
     *     selectedAgents: string[];   // matches Agent.id
     *     reasoning: string;
     *     executionOrder: 'sequential' | 'parallel';
     *   }
     */
    data class AgentDecision(
        val selectedAgents: List<String>,
        val reasoning: String,
        val executionOrder: String
    )
}
