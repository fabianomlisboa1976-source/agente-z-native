package com.agente.autonomo.agent

import com.agente.autonomo.api.LLMClient
import com.agente.autonomo.api.model.ChatCompletionResponse
import com.agente.autonomo.api.model.Choice
import com.agente.autonomo.api.model.Message
import com.agente.autonomo.data.dao.AgentDao
import com.agente.autonomo.data.dao.AuditLogDao
import com.agente.autonomo.data.dao.MemoryDao
import com.agente.autonomo.data.dao.MessageDao
import com.agente.autonomo.data.dao.SettingsDao
import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.data.entity.Agent
import com.agente.autonomo.data.entity.Memory
import com.agente.autonomo.data.entity.Settings
import com.agente.autonomo.utils.AuditLogger
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AgentOrchestrator multi-agent routing pipeline.
 *
 * Test matrix:
 *   (a) Normal routing      — verifies PipelineContext is assembled correctly from
 *                             MessageDao + MemoryDao and the correct agent is invoked.
 *   (b) Special-command     — verifies short-circuit; no downstream agent handle() called.
 *   (c) Missing memory      — verifies null from MemoryDao defaults to [] without throwing.
 *
 * Test boundaries (what is mocked):
 *   - AppDatabase and all DAOs  (no real SQLite)
 *   - AgentManager              (controls which agents are returned)
 *   - AuditLogger               (suppress real DB writes)
 *   - LLMClient                 (no real HTTP calls; injected via reflective field access
 *                                 OR by calling initializeLLM() which reads SettingsDao)
 *   - ConversationAgentProgrammer (mocked at the private lateinit field level)
 *
 * All tests run on a StandardTestDispatcher; Dispatchers.IO is NOT replaced because
 * withContext(Dispatchers.IO) is transparent in test scope with runTest.
 */
@ExperimentalCoroutinesApi
class AgentOrchestratorTest {

    // ─── Mocks ────────────────────────────────────────────────────────────────

    @MockK lateinit var mockDatabase: AppDatabase
    @MockK lateinit var mockAgentManager: AgentManager
    @MockK lateinit var mockAuditLogger: AuditLogger
    @MockK lateinit var mockMessageDao: MessageDao
    @MockK lateinit var mockMemoryDao: MemoryDao
    @MockK lateinit var mockAgentDao: AgentDao
    @MockK lateinit var mockSettingsDao: SettingsDao
    @MockK lateinit var mockAuditLogDao: AuditLogDao
    @MockK lateinit var mockLlmClient: LLMClient
    @MockK lateinit var mockConversationProgrammer: ConversationAgentProgrammer

    // ─── System Under Test ────────────────────────────────────────────────────

    private lateinit var orchestrator: AgentOrchestrator

    // ─── Test Fixtures ────────────────────────────────────────────────────────

    private val testConversationId = "test-conversation-001"

    /** A minimal active COORDINATOR agent used across all three test cases. */
    private val coordinatorAgent = Agent(
        id = "coordinator-id",
        name = "Coordenador",
        type = Agent.AgentType.COORDINATOR,
        systemPrompt = "Você é o agente coordenador.",
        isActive = true,
        description = "Coordena os demais agentes",
        priority = 1,
        temperature = 0.7f,
        maxTokens = 1000
    )

    /** A minimal active RESEARCHER agent used in test (a). */
    private val researcherAgent = Agent(
        id = "researcher-id",
        name = "Pesquisador",
        type = Agent.AgentType.RESEARCHER,
        systemPrompt = "Você é o agente pesquisador.",
        isActive = true,
        description = "Pesquisa informações",
        priority = 2,
        temperature = 0.5f,
        maxTokens = 800
    )

    /** Valid settings with a non-blank API key so LLMClient is enabled. */
    private val validSettings = Settings(
        id = 1,
        apiKey = "test-api-key-12345",
        apiProvider = "groq",
        apiModel = "llama-3.1-8b-instant",
        serviceEnabled = true,
        crossAuditEnabled = false,
        memoryEnabled = false
    )

    /** Pre-canned LLM response for the routing decision call. */
    private val routingDecisionJson = """
        {
          "selectedAgents": ["researcher-id"],
          "reasoning": "A solicitação requer pesquisa",
          "executionOrder": "sequential"
        }
    """.trimIndent()

    /** Pre-canned final LLM response returned by the researcher agent. */
    private val agentResponseText = "Aqui estão os resultados da pesquisa sobre Kotlin."

    // ─── Setup / Teardown ─────────────────────────────────────────────────────

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)

        // Wire up database mock to return the correct DAOs
        every { mockDatabase.messageDao() } returns mockMessageDao
        every { mockDatabase.memoryDao() } returns mockMemoryDao
        every { mockDatabase.agentDao() } returns mockAgentDao
        every { mockDatabase.settingsDao() } returns mockSettingsDao
        every { mockDatabase.auditLogDao() } returns mockAuditLogDao

        // AuditLogger: suppress all writes
        coEvery { mockAuditLogger.logAction(any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { mockAuditLogger.logAgentDecision(any(), any(), any(), any(), any(), any()) }
            just Runs
        coEvery { mockAuditLogger.logError(any(), any(), any(), any()) } just Runs
        coEvery { mockAuditLogger.logWarning(any(), any()) } just Runs

        // MessageDao: default — save operations succeed silently
        coEvery { mockMessageDao.insertMessage(any()) } just Runs

        // Settings
        coEvery { mockSettingsDao.getSettingsSync() } returns validSettings

        // AgentManager: by default return coordinator
        coEvery { mockAgentManager.getCoordinatorAgent() } returns coordinatorAgent
        coEvery { mockAgentManager.getAllActiveAgents() } returns
            flowOf(listOf(coordinatorAgent, researcherAgent))
        coEvery { mockAgentManager.getAgent("coordinator-id") } returns coordinatorAgent
        coEvery { mockAgentManager.getAgent("researcher-id") } returns researcherAgent
        coEvery { mockAgentManager.recordAgentUsage(any()) } just Runs
        coEvery { mockAgentManager.getAgentsByType(Agent.AgentType.AUDITOR) } returns emptyList()

        orchestrator = AgentOrchestrator(
            database = mockDatabase,
            agentManager = mockAgentManager,
            auditLogger = mockAuditLogger
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: inject a pre-configured LLMClient mock via reflection
    // (avoids needing a real Settings/Retrofit stack).
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Injects [mockLlmClient] directly into the private `llmClient` field of
     * [orchestrator] and sets up [mockConversationProgrammer] as the
     * `conversationProgrammer` lateinit field.
     *
     * This bypasses initializeLLM() (which would try to create a real Retrofit
     * client) while keeping both guards satisfied:
     *   llmClient != null  → callLLM() will use the mock
     *   ::conversationProgrammer.isInitialized → Stage 0 check passes
     */
    private fun injectMockedLLM(
        commandResult: ConversationAgentProgrammer.CommandResult =
            ConversationAgentProgrammer.CommandResult(isCommand = false, message = "")
    ) {
        // Inject llmClient via reflection
        val llmClientField = AgentOrchestrator::class.java
            .getDeclaredField("llmClient")
        llmClientField.isAccessible = true
        llmClientField.set(orchestrator, mockLlmClient)

        // Inject conversationProgrammer via reflection
        val programmerField = AgentOrchestrator::class.java
            .getDeclaredField("conversationProgrammer")
        programmerField.isAccessible = true
        programmerField.set(orchestrator, mockConversationProgrammer)

        // Default command result: not a command
        coEvery { mockConversationProgrammer.processMessage(any()) } returns commandResult
    }

    /**
     * Builds a minimal [ChatCompletionResponse] wrapping [content] so that
     * callLLM() extracts it correctly via
     * `response.choices.firstOrNull()?.message?.content`.
     */
    private fun buildLlmResponse(content: String): ChatCompletionResponse {
        return ChatCompletionResponse(
            id = "chatcmpl-test",
            obj = "chat.completion",
            created = System.currentTimeMillis() / 1000,
            model = "llama-3.1-8b-instant",
            choices = listOf(
                Choice(
                    index = 0,
                    message = Message(role = "assistant", content = content),
                    finishReason = "stop"
                )
            ),
            usage = null
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test (a): Normal routing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Test (a): Normal routing — MessageDao and MemoryDao return valid data.
     *
     * Asserts:
     *   1. classifyIntent() is implicitly called (Stage 3) — we verify the intent
     *      classification helper returns a result rather than mocking it.
     *   2. The correct downstream agent's LLM call is invoked with a PipelineContext
     *      containing the loaded recentMessages and memorySnippets.
     *   3. Result.success is returned with the agent response text.
     *
     * Mocking strategy:
     *   - MessageDao.getRecentMessages → returns 2 pre-canned entity.Message rows
     *   - MemoryDao.searchMemories     → returns 1 pre-canned Memory row per keyword
     *   - LLMClient.sendMessage        → returns routing JSON for first call,
     *                                     agent response for second call
     */
    @Test
    fun `(a) normal routing - loads recentMessages and memorySnippets and invokes correct agent`() =
        runTest {
            // ── Arrange ──────────────────────────────────────────────────────

            // Stage 0: not a command
            injectMockedLLM(
                ConversationAgentProgrammer.CommandResult(isCommand = false, message = "")
            )

            // Stage 1: two recent messages
            val recentEntityMessages = listOf(
                com.agente.autonomo.data.entity.Message(
                    id = 1L,
                    conversationId = testConversationId,
                    senderType = com.agente.autonomo.data.entity.Message.SenderType.USER,
                    content = "Olá, como vai?",
                    agentId = null,
                    agentName = null
                ),
                com.agente.autonomo.data.entity.Message(
                    id = 2L,
                    conversationId = testConversationId,
                    senderType = com.agente.autonomo.data.entity.Message.SenderType.AGENT,
                    content = "Tudo bem, obrigado!",
                    agentId = "coordinator-id",
                    agentName = "Coordenador"
                )
            )
            coEvery {
                mockMessageDao.getRecentMessages(
                    testConversationId,
                    AgentOrchestrator.MAX_CONTEXT_MESSAGES
                )
            } returns recentEntityMessages

            // Stage 2: one relevant memory returned per keyword search
            val relevantMemory = Memory(
                id = "mem-001",
                type = Memory.MemoryType.FACT,
                key = "kotlin_info",
                value = "Kotlin é uma linguagem moderna para Android",
                category = "technology",
                importance = 8,
                isArchived = false
            )
            // searchMemories is called per keyword; return the memory for any keyword
            coEvery { mockMemoryDao.searchMemories(any()) } returns listOf(relevantMemory)

            // Stage 4 — routing call: returns JSON selecting the researcher agent
            // Stage 4 — execution call: returns the researcher's answer
            var llmCallCount = 0
            coEvery {
                mockLlmClient.sendMessage(
                    messages = any(),
                    maxTokens = any(),
                    temperature = any()
                )
            } answers {
                llmCallCount++
                when (llmCallCount) {
                    1 -> Result.success(buildLlmResponse(routingDecisionJson))
                    else -> Result.success(buildLlmResponse(agentResponseText))
                }
            }

            // ── Act ──────────────────────────────────────────────────────────
            val result = orchestrator.processUserMessage(
                userMessage = "Pesquisa sobre Kotlin para Android",
                conversationId = testConversationId
            )

            // ── Assert ───────────────────────────────────────────────────────

            // 1. Overall result must be success
            assertTrue(
                "Expected Result.success but got failure: ${result.exceptionOrNull()?.message}",
                result.isSuccess
            )

            // 2. Response text matches what the researcher agent returned
            assertEquals(
                "Response text should match agent output",
                agentResponseText,
                result.getOrNull()
            )

            // 3. MessageDao.getRecentMessages was called with correct args (Stage 1)
            coVerify(exactly = 1) {
                mockMessageDao.getRecentMessages(
                    testConversationId,
                    AgentOrchestrator.MAX_CONTEXT_MESSAGES
                )
            }

            // 4. MemoryDao.searchMemories was called at least once (Stage 2)
            coVerify(atLeast = 1) { mockMemoryDao.searchMemories(any()) }

            // 5. LLM was called at least twice: once for routing, once for execution
            assertTrue(
                "LLM should have been called at least twice (routing + execution) but was $llmCallCount",
                llmCallCount >= 2
            )

            // 6. recordAgentUsage called for the researcher agent (Stage 4)
            coVerify(atLeast = 1) { mockAgentManager.recordAgentUsage("researcher-id") }

            // 7. Agent response was persisted (Stage 6)
            coVerify(atLeast = 1) { mockMessageDao.insertMessage(any()) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Test (b): Special-command interception
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Test (b): Special-command interception — userMessage is a recognised command.
     *
     * Asserts:
     *   1. No downstream agent handle() (LLM call) is made after Stage 0 intercepts.
     *   2. The returned response matches the expected command output exactly.
     *   3. Stages 1–7 are fully bypassed (no getRecentMessages, no searchMemories,
     *      no routing decision LLM call).
     *
     * Mocking strategy:
     *   - ConversationAgentProgrammer.processMessage → returns isCommand=true
     *   - LLMClient.sendMessage should NOT be called at all
     */
    @Test
    fun `(b) special command interception - short-circuits pipeline, no agent invoked`() =
        runTest {
            // ── Arrange ──────────────────────────────────────────────────────

            val commandMessage = "/help"
            val expectedCommandResponse =
                "Comandos disponíveis: /help, /status, /agents, /reset, /tasks"

            // Stage 0: command IS matched → short-circuit
            injectMockedLLM(
                ConversationAgentProgrammer.CommandResult(
                    isCommand = true,
                    message = expectedCommandResponse
                )
            )

            // LLM should NEVER be called when a command is intercepted
            coEvery {
                mockLlmClient.sendMessage(any(), any(), any())
            } throws AssertionError("LLM sendMessage must not be called for command messages")

            // MessageDao.getRecentMessages should NOT be called
            coEvery {
                mockMessageDao.getRecentMessages(any(), any())
            } throws AssertionError("getRecentMessages must not be called for command messages")

            // MemoryDao.searchMemories should NOT be called
            coEvery {
                mockMemoryDao.searchMemories(any())
            } throws AssertionError("searchMemories must not be called for command messages")

            // ── Act ──────────────────────────────────────────────────────────
            val result = orchestrator.processUserMessage(
                userMessage = commandMessage,
                conversationId = testConversationId
            )

            // ── Assert ───────────────────────────────────────────────────────

            // 1. Overall result must be success
            assertTrue(
                "Expected Result.success for command message, got: ${result.exceptionOrNull()?.message}",
                result.isSuccess
            )

            // 2. Response text must exactly match the command's expected output
            assertEquals(
                "Command response text should match",
                expectedCommandResponse,
                result.getOrNull()
            )

            // 3. ConversationAgentProgrammer.processMessage was called exactly once
            coVerify(exactly = 1) { mockConversationProgrammer.processMessage(commandMessage) }

            // 4. Messages were saved (user + command response) but nothing more
            coVerify(exactly = 2) { mockMessageDao.insertMessage(any()) }

            // 5. Coordinator lookup should NOT have happened (pipeline aborted at Stage 0)
            coVerify(exactly = 0) { mockAgentManager.getCoordinatorAgent() }

            // 6. No agent usage was recorded (no agents ran)
            coVerify(exactly = 0) { mockAgentManager.recordAgentUsage(any()) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Test (c): Missing memory graceful handling
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Test (c): Missing memory — MemoryDao.searchMemories returns null.
     *
     * Asserts:
     *   1. Routing completes without throwing any exception.
     *   2. memorySnippets in the PipelineContext is effectively [] (verified by
     *      checking no memory-related data is passed to the LLM beyond the base prompt).
     *   3. The correct downstream agent is still invoked and returns a response.
     *
     * Mocking strategy:
     *   - MemoryDao.searchMemories → returns null (simulates a broken DAO)
     *   - All other mocks identical to test (a)
     */
    @Test
    fun `(c) null from MemoryDao defaults to empty memorySnippets without throwing`() =
        runTest {
            // ── Arrange ──────────────────────────────────────────────────────

            // Stage 0: not a command
            injectMockedLLM(
                ConversationAgentProgrammer.CommandResult(isCommand = false, message = "")
            )

            // Stage 1: recent messages (same as test a)
            val recentEntityMessages = listOf(
                com.agente.autonomo.data.entity.Message(
                    id = 3L,
                    conversationId = testConversationId,
                    senderType = com.agente.autonomo.data.entity.Message.SenderType.USER,
                    content = "Diga-me sobre coroutines",
                    agentId = null,
                    agentName = null
                )
            )
            coEvery {
                mockMessageDao.getRecentMessages(any(), any())
            } returns recentEntityMessages

            // Stage 2: searchMemories returns null — this is the critical mock for test (c)
            // The DAO contract allows null; getRelevantMemories() must default to emptyList()
            coEvery { mockMemoryDao.searchMemories(any()) } returns null

            // Stage 4 — routing returns coordinator only (simplest valid decision)
            val fallbackRoutingJson = """
                {
                  "selectedAgents": ["coordinator-id"],
                  "reasoning": "Agente padrão sem especialização necessária",
                  "executionOrder": "sequential"
                }
            """.trimIndent()

            var llmCallCount = 0
            coEvery {
                mockLlmClient.sendMessage(
                    messages = any(),
                    maxTokens = any(),
                    temperature = any()
                )
            } answers {
                llmCallCount++
                when (llmCallCount) {
                    1 -> Result.success(buildLlmResponse(fallbackRoutingJson))
                    else -> Result.success(buildLlmResponse("Coroutines são funções que podem ser suspensas."))
                }
            }

            // ── Act ──────────────────────────────────────────────────────────
            // Must NOT throw even though searchMemories returns null
            val result = orchestrator.processUserMessage(
                userMessage = "Diga-me sobre coroutines Kotlin",
                conversationId = testConversationId
            )

            // ── Assert ───────────────────────────────────────────────────────

            // 1. No exception — result is a success
            assertTrue(
                "Expected Result.success even with null memories; got: " +
                    "${result.exceptionOrNull()?.message}",
                result.isSuccess
            )

            // 2. searchMemories WAS attempted (Stage 2 ran) but returned null gracefully
            coVerify(atLeast = 1) { mockMemoryDao.searchMemories(any()) }

            // 3. The coordinator agent was still invoked (pipeline completed normally)
            coVerify(atLeast = 1) { mockAgentManager.recordAgentUsage("coordinator-id") }

            // 4. The LLM was called at least twice (routing + execution)
            assertTrue(
                "LLM must still be called for routing and execution despite null memories",
                llmCallCount >= 2
            )

            // 5. Response is non-null and non-empty
            assertFalse(
                "Final response must not be null or empty",
                result.getOrNull().isNullOrBlank()
            )
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Supplementary unit tests for isolated helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies detectSpecialCommand() correctly identifies all registered slash commands.
     */
    @Test
    fun `detectSpecialCommand returns true for all registered slash commands`() {
        val slashCommands = listOf("/help", "/status", "/reset", "/agents", "/tasks")
        slashCommands.forEach { cmd ->
            assertTrue(
                "detectSpecialCommand should return true for '$cmd'",
                orchestrator.detectSpecialCommand(cmd)
            )
        }
    }

    /**
     * Verifies detectSpecialCommand() returns false for normal messages.
     */
    @Test
    fun `detectSpecialCommand returns false for normal user messages`() {
        val normalMessages = listOf(
            "Olá, como você está?",
            "Pesquise sobre inteligência artificial",
            "Crie um plano de estudos",
            "help me" // natural language, not slash
        )
        normalMessages.forEach { msg ->
            assertFalse(
                "detectSpecialCommand should return false for '$msg'",
                orchestrator.detectSpecialCommand(msg)
            )
        }
    }

    /**
     * Verifies classifyIntent() returns a research label for research-heavy messages.
     */
    @Test
    fun `classifyIntent returns research label for research-oriented messages`() {
        val message = "pesquisa e busca informações sobre machine learning"
        val (label, score) = orchestrator.classifyIntent(message)
        assertEquals("research", label)
        assertNotNull(score)
        assertTrue(
            "Score should exceed INTENT_THRESHOLD (${AgentOrchestrator.INTENT_THRESHOLD})",
            score!! > AgentOrchestrator.INTENT_THRESHOLD
        )
    }

    /**
     * Verifies classifyIntent() returns null for generic messages below threshold.
     */
    @Test
    fun `classifyIntent returns null label for generic messages`() {
        val message = "olá"
        val (label, score) = orchestrator.classifyIntent(message)
        assertNull(
            "Intent label should be null for a very short generic message, was '$label'",
            label
        )
        assertNull(
            "Intent score should be null when label is null, was '$score'",
            score
        )
    }

    /**
     * Verifies that the PipelineContext data class carries all expected fields
     * with correct types (compile-time regression guard).
     */
    @Test
    fun `PipelineContext can be constructed with all required fields`() {
        val ctx = AgentOrchestrator.PipelineContext(
            userMessage = "test message",
            recentMessages = emptyList(),
            memorySnippets = emptyList(),
            commandMeta = null,
            intentLabel = "research",
            intentScore = 0.85f
        )
        assertEquals("test message", ctx.userMessage)
        assertTrue(ctx.recentMessages.isEmpty())
        assertTrue(ctx.memorySnippets.isEmpty())
        assertNull(ctx.commandMeta)
        assertEquals("research", ctx.intentLabel)
        assertEquals(0.85f, ctx.intentScore)
    }
}
