package dev.mindmax.v4.agent

import dev.mindmax.v4.audit.AuditLogger
import dev.mindmax.v4.core.di.ServiceLocator
import dev.mindmax.v4.data.entity.AgentEntity
import dev.mindmax.v4.data.entity.AgentType
import dev.mindmax.v4.data.entity.SettingsEntity
import dev.mindmax.v4.llm.LlmClient
import dev.mindmax.v4.llm.ProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.util.Date

/**
 * The single multi-agent runtime that drives a chat turn end-to-end.
 *
 * Pipeline (matches the plan):
 *  1. **ConversationProgrammer** — short-circuits on `/help` etc., so the
 *     rest of the pipeline sees a SYSTEM message and returns.
 *  2. **Persist user message** — emitted as [AgentEvent.UserSaved].
 *  3. **Coordinator pass** — calls the LLM with `coordinator.systemPrompt` +
 *     user text; expects JSON `{selected, executionOrder, reason}`. Falls
 *     back to `["executor"]` on parse failure.
 *  4. **Wave execution** — `executionOrder` is bucketed by index (positions
 *     at index 0 run in parallel, then index 1, …). Each wave is
 *     `async{}.awaitAll()`, so real parallelism — no serial-of-alls.
 *  5. **Aggregate** — the responses from all waves are joined with
 *     `\n\n---\n\n` and emitted as the final assistant message.
 *  6. **Cross-audit (optional)** — when `crossAuditEnabled` and `auditor`
 *     is selected, audit each non-empty answer and append a marker line.
 *  7. **Persistence** — final message + audit correlation.
 *
 * Errors are non-fatal: a single agent failure bubbles up as an empty result
 * but the rest of the run continues. The pipeline always ends with
 * [AgentEvent.AgentComplete].
 */
class AgentRuntime(
    private val auditLogger: AuditLogger = AuditLogger(),
) {

    fun handle(
        userMessage: String,
        conversationId: String,
    ): Flow<AgentEvent> = channelFlow {
        val correlationId = auditLogger.newCorrelationId()
        val now = Date()

        // (1) Programmer — early return path.
        ConversationProgrammer().tryProgramCommand(userMessage)?.let { reply ->
            val saved = ServiceLocator.chatRepository.appendSystemMessage(
                conversationId = conversationId,
                content = reply.content,
                now = now,
            )
            send(AgentEvent.UserSaved(saved.id))
            send(
                AgentEvent.AgentSpoke(
                    agentId = reply.agentId,
                    agentName = "Sistema",
                    content = reply.content,
                    correlationId = correlationId,
                ),
            )
            send(AgentEvent.AgentComplete(correlationId, reply.content, listOf(reply.agentId)))
            return@channelFlow
        }

        // (2) Persist the user turn first so the UI sees it immediately.
        val userSaved = ServiceLocator.chatRepository.appendUserMessage(
            conversationId = conversationId,
            content = userMessage,
            now = now,
        )
        send(AgentEvent.UserSaved(userSaved.id))

        val settings = ServiceLocator.settingsRepository.current()
            ?: SettingsEntity.default(now)
        val client = ServiceLocator.llmClient()

        // Snapshot agents from DB so the runtime is snapshot-stable.
        val allAgents = ServiceLocator.agentRepository.activeAgents()
        if (allAgents.isEmpty()) {
            send(AgentEvent.AgentError("coordinator", correlationId, "Nenhum agente ativo."))
            send(AgentEvent.AgentComplete(correlationId, "", emptyList()))
            return@channelFlow
        }

        val activeById = allAgents.associateBy { it.id }
        val coordinator = allAgents.firstOrNull { it.type == AgentType.COORDINATOR }
            ?: allAgents.first()

        // (3) Coordinator decides who runs and in what order.
        val decision = decide(coordinator, userMessage, settings, client, correlationId)
        auditLogger.logAgentDecision(correlationId, decision.toString())

        val orderedAgents = decision.executionOrder
            .mapNotNull { activeById[it] }
            .ifEmpty {
                val fallback = activeById["executor"] ?: coordinator
                listOf(fallback)
            }

        val waves = orderedAgents.withIndex().groupBy({ it.index }, { it.value })
        val participantIds = orderedAgents.map { it.id }

        send(
            AgentEvent.PlanStarted(
                correlationId = correlationId,
                participants = participantIds,
            ),
        )

        // (4) Wave-by-wave parallel execution.
        val collected = mutableListOf<AgentOutput>()
        for ((waveIndex, waveAgents) in waves.toSortedMap().entries) {
            val waveResults = runWave(
                waveIndex = waveIndex,
                waveAgents = waveAgents,
                userMessage = userMessage,
                settings = settings,
                client = client,
                correlationId = correlationId,
            )
            for (result in waveResults) {
                collected.add(result.output)
                result.events.forEach { send(it) }
            }
        }

        // (5) Aggregate and persist the final assistant message.
        val combined = collected
            .filter { it.content.isNotBlank() }
            .joinToString("\n\n---\n\n") { it.content }

        val finalContent = combined.ifBlank { "Sem resposta dos agentes para essa mensagem." }

        ServiceLocator.chatRepository.appendAgentMessage(
            conversationId = conversationId,
            content = finalContent,
            agentId = "multi",
            agentName = "Coordenador",
            tokensUsed = null,
            now = Date(),
        )

        // (6) Cross-audit (optional).
        val crossed = if (settings.crossAuditEnabled) {
            val auditor = activeById["auditor"]
            if (auditor != null && collected.isNotEmpty()) {
                runCrossAudit(
                    auditor = auditor,
                    answers = collected,
                    settings = settings,
                    client = client,
                    correlationId = correlationId,
                )
            } else null
        } else null

        if (crossed != null) {
            send(
                AgentEvent.AgentSpoke(
                    agentId = "auditor",
                    agentName = "Auditor",
                    content = crossed,
                    correlationId = correlationId,
                ),
            )
        }

        // (7) Emit completion.
        send(
            AgentEvent.AgentComplete(
                correlationId = correlationId,
                finalMessage = finalContent + (crossed?.let { "\n\n---\n\n$it" } ?: ""),
                participants = participantIds,
            ),
        )
    }.flowOn(Dispatchers.Default)

    /**
     * Coordinator pass — single-shot `chat` (not stream) so we can parse a JSON
     * decision deterministically before launching the wave execution.
     */
    private suspend fun decide(
        coordinator: AgentEntity,
        userMessage: String,
        settings: SettingsEntity,
        client: LlmClient,
        correlationId: String,
    ): AgentDecision {
        val provider = ProviderRegistry.provider(settings)
        val model = ProviderRegistry.modelFor(provider, settings)
        val start = System.currentTimeMillis()
        val agentNames = ServiceLocator.agentRepository.activeAgents()
            .joinToString { "${it.id}:${it.name}" }
        val prompt = coordinator.systemPrompt +
            "\n\nAgentes disponíveis (id:nome): $agentNames\n" +
            "Pedido: $userMessage"
        auditLogger.logRequest(correlationId, coordinator.name, coordinator.id, prompt)
        val reply = client.chatOnce(
            systemPrompt = coordinator.systemPrompt,
            userMessage = prompt,
            spec = LlmClient.RequestSpec(model = model, temperature = 0.2f),
        )
        val elapsed = System.currentTimeMillis() - start
        auditLogger.logResponse(correlationId, coordinator.name, coordinator.id, reply.content, elapsed)
        return AgentDecision.parseOrNull(reply.content) ?: AgentDecision.fallback()
    }

    /**
     * One parallel wave: every agent in the wave receives the same user input.
     * Returns per-agent output + the events that should be emitted to the
     * outer channel. Events are bubbled up rather than sent inside async
     * blocks because async{} does not have access to the channel flow's
     * ProducerScope.
     */
    private suspend fun runWave(
        waveIndex: Int,
        waveAgents: List<AgentEntity>,
        userMessage: String,
        settings: SettingsEntity,
        client: LlmClient,
        correlationId: String,
    ): List<WaveResult> = coroutineScope {
        waveAgents.map { agent ->
            async(Dispatchers.IO) {
                runSingle(agent, userMessage, settings, client, correlationId)
            }
        }.awaitAll()
    }

    private suspend fun runSingle(
        agent: AgentEntity,
        userMessage: String,
        settings: SettingsEntity,
        client: LlmClient,
        correlationId: String,
    ): WaveResult {
        val start = System.currentTimeMillis()
        auditLogger.logRequest(correlationId, agent.name, agent.id, userMessage)
        val model = ProviderRegistry.modelFor(ProviderRegistry.provider(settings), settings)
        val reply = try {
            client.chatOnce(
                systemPrompt = agent.systemPrompt,
                userMessage = userMessage,
                spec = LlmClient.RequestSpec(
                    model = model,
                    temperature = 0.7f,
                    maxTokens = agent.maxTokens,
                ),
            ).content
        } catch (error: Throwable) {
            auditLogger.logError(correlationId, "llm.error", agent.name, agent.id, error)
            ""
        }
        val elapsed = System.currentTimeMillis() - start
        if (reply.isNotBlank()) {
            auditLogger.logResponse(correlationId, agent.name, agent.id, reply, elapsed)
        }
        val events = buildList {
            add(
                AgentEvent.AgentSpoke(
                    agentId = agent.id,
                    agentName = agent.name,
                    content = reply,
                    correlationId = correlationId,
                ),
            )
            add(AgentEvent.AgentDone(agentId = agent.id, correlationId = correlationId))
        }
        return WaveResult(
            output = AgentOutput(agent, reply),
            events = events,
        )
    }

    private suspend fun runCrossAudit(
        auditor: AgentEntity,
        answers: List<AgentOutput>,
        settings: SettingsEntity,
        client: LlmClient,
        correlationId: String,
    ): String? {
        val model = ProviderRegistry.modelFor(ProviderRegistry.provider(settings), settings)
        val source = answers.joinToString("\n\n---\n\n") {
            "=== ${it.agent.name} ===\n${it.content}"
        }
        return try {
            client.chatOnce(
                systemPrompt = auditor.systemPrompt,
                userMessage = source.take(3_000),
                spec = LlmClient.RequestSpec(model = model, temperature = 0.2f, maxTokens = 256),
            ).content.trim()
        } catch (error: Throwable) {
            auditLogger.logError(correlationId, "cross_audit.error", auditor.name, auditor.id, error)
            null
        }
    }

    private data class AgentOutput(val agent: AgentEntity, val content: String)

    private data class WaveResult(
        val output: AgentOutput,
        val events: List<AgentEvent>,
    )
}
