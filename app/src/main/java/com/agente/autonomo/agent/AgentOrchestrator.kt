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
 * Full pipeline documentation: docs/agent-orchestrator.md
 *
 * ## Execution Order (inside processUserMessage)
 *
 *  Step 0  — Special command interception via ConversationAgentProgrammer.
 *            Recognised patterns (PT/EN): create/modify/delete agent, create/list task,
 *            list agents, help. On match: persists both messages and short-circuits;
 *            Steps 1–9 do NOT execute.
 *
 *  Step 1  — Persist user message to Room (messages table, SenderType.USER).
 *
 *  Step 2  — Load conversation history via MessageDao.getRecentMessages().
 *            Parameters: conversationId (String), limit = MAX_CONTEXT_MESSAGES (10).
 *            Result: List<entity.Message> mapped → List<api.model.Message> in
 *            descending-timestamp order (newest first — see docs/agent-orchestrator.md
 *            §Known Limitations for ordering caveat).
 *            Failure: propagates to outer catch → Result.failure.
 *
 *  Step 3  — Retrieve relevant memories via MemoryDao.searchMemories().
 *            Algorithm: tokenise userMessage on whitespace, drop tokens ≤ 3 chars,
 *            take up to 5 keywords, call searchMemories(keyword) once per keyword
 *            (SQL LIKE against key/value/category columns), de-dup by id, sort by
 *            importance DESC, cap at 5 results.
 *            NOTE: results are retrieved but not yet injected into the LLM prompt.
 *            See docs/agent-orchestrator.md §Known Limitations #1.
 *            Failure: caught internally → emptyList(); pipeline continues.
 *
 *  Step 4  — Resolve COORDINATOR agent via AgentManager.getCoordinatorAgent().
 *            Null result → Result.failure (pipeline aborts).
 *
 *  Step 5  — LLM-assisted agent routing via decideAgentsForTask().
 *            Sends coordinator system-prompt + history + routing prompt to LLM
 *            (maxTokens=500, temperature=0.3).
 *            Expects JSON: { selectedAgents:[String], reasoning:String,
 *            executionOrder:"sequential"|"parallel" }.
 *            Parse failure → fallback AgentDecision{coordinator only, sequential}.
 *            LLM null → Result.failure (pipeline aborts).
 *
 *  Step 6  — Execute selected agents sequentially via executeWithAgents().
 *            Each agent receives: [system: agent.systemPrompt] +
 *            [history: contextMessages] + [user: userMessage].
 *            Unknown agent IDs silently skipped. Null LLM responses silently skipped.
 *            All non-null responses concatenated with "\n\n---\n\n".
 *            Zero responses → Result.failure.
 *            NOTE: executionOrder="parallel" is not yet implemented; always sequential.
 *
 *  Step 7  — Optional cross-audit (Settings.crossAuditEnabled == true).
 *            Invokes first AUDITOR agent to verify response quality.
 *            Audit failure logs a warning but does NOT suppress the response.
 *
 *  Step 8  — Persist agent response (SenderType.AGENT, attributed to coordinator).
 *
 *  Step 9  — Optional memory update (Settings.memoryEnabled == true).
 *            Creates a CONTEXT-type Memory row with the exchange summary.
 *
 * ## Correlation ID
 * Every auditLogger call within one processUserMessage invocation shares a single
 * correlationId = UUID.randomUUID().toString() generated at Step 0, enabling
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
         * Maximum number of past messages loaded as conversation context for every
         * LLM call.  Applies to both the routing-decision call (Step 5) and every
         * per-agent execution call (Step 6).
         *
         * Corresponds to the 'limit' parameter of MessageDao.getRecentMessages().
         * Increase cautiously: larger values raise token consumption proportionally.
         */
        const val MAX_CONTEXT_MESSAGES = 10
    }

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
     * silently bypassed — a known limitation documented in docs/agent-orchestrator.md.
     */
    private lateinit var conversationProgrammer: ConversationAgentProgrammer

    // ─────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initialises the LLM client and the ConversationAgentProgrammer.
     *
     * Must be called before processUserMessage() to enable:
     *   - LLM API calls
     *   - Special command interception (Step 0)
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
     * @param userMessage  Raw user input exactly as typed; never pre-processed.
     * @param conversationId  Logical conversation bucket used to scope message
     *   history and memory queries. Defaults to "default". Use distinct IDs for
     *   parallel conversations.
     * @return Result.success(responseText) on success, Result.failure(exception)
     *   on any non-recoverable error.  The pipeline has internal partial-failure
     *   handling — see the Error Handling Matrix in docs/agent-orchestrator.md.
     *
     * All I/O is dispatched on Dispatchers.IO via withContext; callers may invoke
     * this from any coroutine context.
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
            // STEP 0 — Special Command Interception
            // ──────────────────────────────────────────────────────────────────
            // ConversationAgentProgrammer checks whether userMessage matches any
            // registered command pattern (PT/EN: create/modify/delete agent,
            // create/list tasks, list agents, help).
            //
            // On match (isCommand == true):
            //   - User message and command response are persisted.
            //   - An audit record is written.
            //   - Result.success is returned immediately; Steps 1–9 are skipped.
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
            // STEP 1 — Persist User Message
            // ──────────────────────────────────────────────────────────────────
            // Writes a SenderType.USER row to the messages table so subsequent
            // getRecentMessages() calls within this same pipeline invocation will
            // include the current turn.
            // Failure: DB exception propagates to outer catch → Result.failure.
            saveUserMessage(userMessage, conversationId)

            // ──────────────────────────────────────────────────────────────────
            // STEP 2 — Load Conversation History
            // ──────────────────────────────────────────────────────────────────
            // MessageDao.getRecentMessages(conversationId, MAX_CONTEXT_MESSAGES)
            //   conversationId : scoping key matching the parameter above
            //   limit          : MAX_CONTEXT_MESSAGES (10)
            //   return type    : List<entity.Message>, ORDER BY timestamp DESC
            //
            // Result mapped to List<api.model.Message> with role translation:
            //   SenderType.USER   → role = "user"
            //   SenderType.AGENT  → role = "assistant"
            //   SenderType.SYSTEM → role = "system"
            //
            // Injected into: decideAgentsForTask() and executeWithAgents() as
            // the middle segment of each LLM messages array:
            //   [system: agent.systemPrompt] + [contextMessages…] + [user: userMessage]
            //
            // NOTE: messages arrive newest-first (DESC). See docs §Known Limitations #3.
            // Failure: propagates to outer catch → Result.failure.
            val contextMessages = getConversationContext(conversationId)

            // ──────────────────────────────────────────────────────────────────
            // STEP 3 — Retrieve Relevant Memories
            // ──────────────────────────────────────────────────────────────────
            // MemoryDao.searchMemories(keyword) is called once per extracted keyword.
            //
            // Keyword extraction:
            //   userMessage.lowercase().split(" ").filter { it.length > 3 }.take(5)
            //
            // DAO query parameters:
            //   query : single keyword string
            //   SQL   : LIKE '%<query>%' against columns key, value, category
            //   filter: is_archived = 0  (active memories only)
            //   sort  : importance DESC, access_count DESC
            //   return: List<entity.Memory> (no DAO-level limit)
            //
            // Post-aggregation: distinctBy { id }, sortedByDescending { importance }, take(5)
            //
            // IMPORTANT: the retrieved memories are currently NOT injected into the
            // LLM prompt. This is a known gap; see docs/agent-orchestrator.md §1.
            // Failure: caught inside getRelevantMemories() → emptyList(); non-fatal.
            @Suppress("UNUSED_VARIABLE")
            val relevantMemories = getRelevantMemories(userMessage)
            // TODO: serialize relevantMemories into a context message and insert
            //       into contextMessages before building agent payloads.

            // ──────────────────────────────────────────────────────────────────
            // STEP 4 — Resolve Coordinator Agent
            // ──────────────────────────────────────────────────────────────────
            // Fetches first active Agent with AgentType.COORDINATOR from AgentDao.
            // The coordinator:
            //   - Provides the systemPrompt for the routing LLM call (Step 5).
            //   - Acts as the single-agent fallback when routing parsing fails.
            //   - Its id/name are used to attribute the final persisted response.
            // Null → immediate Result.failure; Steps 5–9 do not execute.
            val coordinator = agentManager.getCoordinatorAgent()
                ?: return@withContext Result.failure(
                    Exception("Agente coordenador não encontrado")
                )

            // ──────────────────────────────────────────────────────────────────
            // STEP 5 — LLM-Assisted Agent Routing
            // ──────────────────────────────────────────────────────────────────
            // decideAgentsForTask() submits an LLM call (maxTokens=500,
            // temperature=0.3) that receives:
            //   [system]  coordinator.systemPrompt
            //   [history] contextMessages
            //   [user]    routing prompt with enumerated active agents + userMessage
            //
            // Expected LLM JSON response:
            //   { "selectedAgents": ["agentId1", ...],
            //     "reasoning": "...",
            //     "executionOrder": "sequential" | "parallel" }
            //
            // Parsed via Gson into AgentDecision data class.
            // Parse failure → fallback AgentDecision(coordinator only, sequential).
            // LLM returns null → Result.failure("Falha na decisão de agentes").
            //
            // NOTE: "parallel" executionOrder is declared but not implemented;
            // execution is always sequential. See docs §Known Limitations #2.
            val agentDecision = decideAgentsForTask(coordinator, userMessage, contextMessages)
                ?: return@withContext Result.failure(
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

            // ──────────────────────────────────────────────────────────────────
            // STEP 6 — Execute Selected Agents
            // ──────────────────────────────────────────────────────────────────
            // executeWithAgents() iterates agentDecision.selectedAgents in order.
            //
            // For each agentId:
            //   - Skips silently if agentManager.getAgent(agentId) returns null.
            //   - Builds LLM payload:
            //       [system: agent.systemPrompt]
            //       + [history: contextMessages]
            //       + [user: userMessage]
            //   - Calls LLM with agent.maxTokens and agent.temperature.
            //   - Skips silently (no warning log) if LLM returns null.
            //   - Appends non-null responses to results list.
            //   - Calls agentManager.recordAgentUsage(agent.id) on success.
            //   - Writes per-agent audit entry with correlationId.
            //
            // Result aggregation:
            //   responses.joinToString("\n\n---\n\n") if any responses exist
            //   Result.failure("Nenhum agente conseguiu responder") if none.
            val executionResult = executeWithAgents(
                agentDecision.selectedAgents,
                userMessage,
                contextMessages,
                correlationId
            )

            // ──────────────────────────────────────────────────────────────────
            // STEP 7 — Optional Cross-Audit
            // ──────────────────────────────────────────────────────────────────
            // Executed only when Settings.crossAuditEnabled == true.
            // Submits the final response to the first AgentType.AUDITOR agent for
            // quality review (maxTokens=500, temperature=0.2).
            // Detects "REPROVADO" string in auditor response.
            // Audit failure → logWarning only; the response is still delivered.
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

            // ──────────────────────────────────────────────────────────────────
            // STEP 8 — Persist Agent Response
            // ──────────────────────────────────────────────────────────────────
            // Writes a SenderType.AGENT row attributed to the coordinator.
            // Note: when multiple agents responded, individual attributions are
            // available only in the audit log; the DB row always uses coordinator.
            // See docs §Known Limitations #9.
            // Failure: propagates to outer catch → Result.failure.
            val finalResponse = executionResult.getOrThrow()
            saveAgentMessage(finalResponse, conversationId, coordinator.id, coordinator.name)

            // ──────────────────────────────────────────────────────────────────
            // STEP 9 — Optional Memory Update
            // ──────────────────────────────────────────────────────────────────
            // Executed only when Settings.memoryEnabled == true.
            // Creates a Memory(type=CONTEXT, importance=5) summarising this exchange.
            // response is truncated to 200 chars before storage.
            // KNOWN ISSUE: failure here propagates to outer catch and returns
            // Result.failure even though the LLM response was obtained successfully.
            // Consider wrapping in try/catch to make memory persistence non-critical.
            // See docs §Known Limitations #6.
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
            // Catch-all: any unhandled exception from Steps 1–9 lands here.
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
     * Step 5 implementation — submits a routing prompt to the coordinator LLM
     * and parses the JSON AgentDecision response.
     *
     * LLM call parameters: maxTokens=500, temperature=0.3
     *
     * Returns null only when callLLM() returns null (LLM unavailable).
     * JSON parse failures are caught and replaced with a safe coordinator-only fallback.
     *
     * @param coordinator  The coordinator Agent entity (provides systemPrompt).
     * @param userMessage  Original user input (included in routing prompt).
     * @param contextMessages  Conversation history (api.model.Message, DESC order).
     */
    private suspend fun decideAgentsForTask(
        coordinator: Agent,
        userMessage: String,
        contextMessages: List<Message>
    ): AgentDecision? {
        // The routing prompt enumerates every active agent so the LLM can make
        // an informed selection.  getAvailableAgentsDescription() queries AgentDao
        // for all agents where isActive = true.
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
            addAll(contextMessages)  // conversation history injected here
            add(Message(role = "user", content = decisionPrompt))
        }

        val response = callLLM(messages, maxTokens = 500, temperature = 0.3)
            ?: return null  // propagated as Result.failure in processUserMessage

        return try {
            parseAgentDecision(response)
        } catch (e: Exception) {
            // Fallback: single coordinator agent, sequential order.
            // This handles malformed JSON, missing fields, type mismatches, etc.
            AgentDecision(
                selectedAgents = listOf(coordinator.id),
                reasoning = "Fallback para coordenador devido a erro no parsing",
                executionOrder = "sequential"
            )
        }
    }

    /**
     * Step 6 implementation — calls each selected agent's LLM sequentially.
     *
     * Context payload delivered to each agent's LLM call:
     *   index 0          : Message(role="system", content=agent.systemPrompt)
     *   indices 1..N     : contextMessages (history, api.model.Message, DESC order)
     *   last index       : Message(role="user", content=userMessage)
     *
     * Agent-level LLM parameters:
     *   maxTokens   : agent.maxTokens  (entity field, may be null → LLMClient default)
     *   temperature : agent.temperature (entity field, may be null → LLMClient default)
     *
     * Per-agent failures (unknown ID, null LLM response) are silently skipped.
     * Consider adding auditLogger.logWarning() for observability (docs §5).
     *
     * @return Result.success(concatenatedResponses) or
     *         Result.failure if every agent returned null.
     */
    private suspend fun executeWithAgents(
        agentIds: List<String>,
        userMessage: String,
        contextMessages: List<Message>,
        correlationId: String
    ): Result<String> {
        val results = mutableListOf<String>()

        for (agentId in agentIds) {
            // Unknown agent IDs: silently skip.  This can happen if an agent was
            // deleted between the routing decision (Step 5) and execution (Step 6).
            val agent = agentManager.getAgent(agentId) ?: continue

            // Context payload — see data contract in docs/agent-orchestrator.md
            // §Agent Context Data Contract.
            val messages = buildList {
                add(Message(role = "system", content = agent.systemPrompt))
                addAll(contextMessages)  // conversation history
                add(Message(role = "user", content = userMessage))
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
            // Null response: no warning logged here.  See docs §Known Limitations #5.
        }

        return if (results.isNotEmpty()) {
            // Multiple agent responses are concatenated; no synthesis step exists yet.
            // See docs §Known Limitations #4.
            Result.success(results.joinToString("\n\n---\n\n"))
        } else {
            Result.failure(Exception("Nenhum agente conseguiu responder"))
        }
    }

    /**
     * Step 7 implementation — submits the final response to an AUDITOR agent.
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

    // ─────────────────────────────────────────────────────────────────────────
    // LLM Gateway
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Centralised LLM call gateway used by all pipeline steps.
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
     * Persists a user-authored message to the messages table (Step 1).
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
     * Persists an agent-authored message to the messages table (Steps 0 and 8).
     *
     * @param agentId    The persisted agent's entity ID; "system" for command responses.
     * @param agentName  Display name shown in the UI; "Sistema" for command responses.
     *
     * NOTE: When multiple agents responded in Step 6, this is called once with the
     * coordinator's ID/name.  Individual per-agent attributions are in the audit log.
     * See docs §Known Limitations #9.
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
     * Loads conversation history for LLM context hydration (Step 2).
     *
     * Calls: MessageDao.getRecentMessages(conversationId, MAX_CONTEXT_MESSAGES)
     *   - conversationId : scopes the query to the current conversation bucket
     *   - limit          : MAX_CONTEXT_MESSAGES = 10
     *   - order          : timestamp DESC (newest first — see docs §Known Limitations #3)
     *
     * Maps entity.Message → api.model.Message using role translation:
     *   SenderType.USER   → "user"
     *   SenderType.AGENT  → "assistant"
     *   SenderType.SYSTEM → "system"
     *
     * The resulting list is injected between the system prompt and the current user
     * turn in every LLM call within this pipeline invocation.
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
     * Retrieves semantically relevant memories for the current user message (Step 3).
     *
     * Algorithm:
     *   1. Tokenise userMessage: lowercase → split on space → drop tokens ≤ 3 chars
     *      → take first 5 keywords. (Simple stop-word removal heuristic.)
     *   2. For each keyword call MemoryDao.searchMemories(keyword):
     *        SQL: LIKE '%<keyword>%' on columns key, value, category
     *        Filter: is_archived = 0
     *        Sort: importance DESC, access_count DESC
     *   3. Aggregate, de-duplicate by id, sort by importance DESC, cap at 5.
     *
     * IMPORTANT: the returned list is currently NOT injected into the LLM prompt.
     * It is retrieved here to validate retrieval correctness and to facilitate the
     * planned memory-injection feature.  See docs/agent-orchestrator.md §1.
     *
     * Error handling: all DB exceptions are caught internally; returns emptyList()
     * so memory retrieval failure is always non-fatal to the pipeline.
     */
    private suspend fun getRelevantMemories(
        query: String
    ): List<com.agente.autonomo.data.entity.Memory> {
        return try {
            val keywords = query.lowercase()
                .split(" ")
                .filter { it.length > 3 }  // drop short / stop-word tokens
                .take(5)                    // cap DB calls at 5

            val memories = mutableListOf<com.agente.autonomo.data.entity.Memory>()

            for (keyword in keywords) {
                val found = database.memoryDao().searchMemories(keyword)
                memories.addAll(found)
            }

            // De-duplicate across keywords, sort by importance, hard ceiling of 5.
            memories.distinctBy { it.id }
                .sortedByDescending { it.importance }
                .take(5)
        } catch (e: Exception) {
            // Non-fatal: pipeline continues without memories.
            // Consider adding auditLogger.logWarning() here for observability.
            emptyList()
        }
    }

    /**
     * Persists a summary of the current exchange as a CONTEXT-type Memory (Step 9).
     *
     * Memory fields:
     *   id             : UUID
     *   type           : MemoryType.CONTEXT
     *   key            : "conversation_<conversationId>_<epochMs>" (unique per message)
     *   value          : "Usuário: <userMessage>\nResposta: <response[:200]>"
     *   conversationId : scoping key
     *   importance     : 5 (mid-range, hardcoded)
     *
     * KNOWN ISSUE: failure here is NOT wrapped in its own try/catch, meaning a DB
     * error during memory write will cause processUserMessage to return Result.failure
     * even though the LLM response was already obtained.  See docs §Known Limitations #6.
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
     * Builds the agent enumeration string injected into the routing prompt (Step 5).
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
     * Result of the LLM-assisted routing decision (Step 5).
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
