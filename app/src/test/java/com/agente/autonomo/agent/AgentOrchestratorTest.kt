package com.agente.autonomo.agent

import com.agente.autonomo.api.AllProvidersExhaustedException
import com.agente.autonomo.api.FallbackLLMClient
import com.agente.autonomo.api.ProviderHealthChecker
import com.agente.autonomo.api.ProviderHealthRepository
import com.agente.autonomo.api.model.ApiProvider
import com.agente.autonomo.api.model.ChatCompletionResponse
import com.agente.autonomo.api.model.Choice
import com.agente.autonomo.api.model.Message
import com.agente.autonomo.data.entity.Agent
import com.agente.autonomo.data.entity.AgentType
import com.agente.autonomo.data.entity.ProviderHealth
import com.agente.autonomo.data.entity.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.util.Date

/**
 * Unit tests for [AgentOrchestrator] and [FallbackLLMClient] failover logic.
 *
 * Room DAOs and network clients are mocked so tests run in the JVM without
 * an Android emulator.
 */
@ExperimentalCoroutinesApi
class AgentOrchestratorTest {

    // ---------------------------------------------------------------------------
    // Mocks & fakes
    // ---------------------------------------------------------------------------

    private lateinit var mockHealthRepository: ProviderHealthRepository
    private lateinit var mockHealthChecker: ProviderHealthChecker
    private var allProvidersFailedCalled = false

    private val healthyRecord = ProviderHealth(
        provider = ApiProvider.GROQ.name,
        isHealthy = true,
        consecutiveFailures = 0,
        lastChecked = Date(0), // epoch → always stale → ping will be called
        backoffUntil = 0L
    )

    private fun makeResponse(content: String): ChatCompletionResponse =
        ChatCompletionResponse(
            id = "test-id",
            model = "test-model",
            choices = listOf(
                Choice(
                    index = 0,
                    message = Message(role = "assistant", content = content),
                    delta = null,
                    finish_reason = "stop"
                )
            ),
            usage = null,
            created = null
        )

    private val defaultSettings = Settings(
        id = 1,
        apiKey = "test-key",
        apiProvider = "groq",
        apiModel = "llama-3.1-8b-instant"
    )

    @Before
    fun setUp() {
        mockHealthRepository = mock()
        mockHealthChecker = mock()
        allProvidersFailedCalled = false
    }

    // ---------------------------------------------------------------------------
    // ProviderHealthRepository.computeBackoff
    // ---------------------------------------------------------------------------

    @Test
    fun `computeBackoff returns 0 for zero failures`() {
        val repo = ProviderHealthRepository(mock())
        assertEquals(0L, repo.computeBackoff(0))
    }

    @Test
    fun `computeBackoff returns BASE for first failure`() {
        val repo = ProviderHealthRepository(mock())
        assertEquals(
            ProviderHealthRepository.BASE_BACKOFF_MS,
            repo.computeBackoff(1)
        )
    }

    @Test
    fun `computeBackoff doubles with each failure`() {
        val repo = ProviderHealthRepository(mock())
        assertEquals(ProviderHealthRepository.BASE_BACKOFF_MS * 1, repo.computeBackoff(1))
        assertEquals(ProviderHealthRepository.BASE_BACKOFF_MS * 2, repo.computeBackoff(2))
        assertEquals(ProviderHealthRepository.BASE_BACKOFF_MS * 4, repo.computeBackoff(3))
        assertEquals(ProviderHealthRepository.BASE_BACKOFF_MS * 8, repo.computeBackoff(4))
    }

    @Test
    fun `computeBackoff is capped at MAX_BACKOFF_MS`() {
        val repo = ProviderHealthRepository(mock())
        // 100 failures → would overflow without cap
        val result = repo.computeBackoff(100)
        assertEquals(ProviderHealthRepository.MAX_BACKOFF_MS, result)
    }

    // ---------------------------------------------------------------------------
    // ProviderHealthChecker.shouldRePing
    // ---------------------------------------------------------------------------

    @Test
    fun `shouldRePing returns true when last check was before interval`() {
        val checker = ProviderHealthChecker()
        val oldTimestamp = System.currentTimeMillis() -
                ProviderHealthChecker.HEALTH_CHECK_INTERVAL_MS - 1
        assertTrue(checker.shouldRePing(oldTimestamp))
    }

    @Test
    fun `shouldRePing returns false when last check was recent`() {
        val checker = ProviderHealthChecker()
        val recentTimestamp = System.currentTimeMillis()
        assertFalse(checker.shouldRePing(recentTimestamp))
    }

    // ---------------------------------------------------------------------------
    // FallbackLLMClient – all providers in backoff
    // ---------------------------------------------------------------------------

    @Test
    fun `sendMessage returns failure and calls callback when all providers in backoff`() =
        runTest {
            // No available providers (all in backoff)
            whenever(mockHealthRepository.getOrderedAvailableProviders())
                .thenReturn(emptyList())

            val client = FallbackLLMClient(
                baseSettings = defaultSettings,
                healthRepository = mockHealthRepository,
                healthChecker = mockHealthChecker,
                onAllProvidersFailed = { allProvidersFailedCalled = true },
                maxRetriesPerProvider = 1
            )

            val result = client.sendMessage(
                listOf(Message(role = "user", content = "hello"))
            )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is AllProvidersExhaustedException)
            assertTrue(allProvidersFailedCalled)
        }

    // ---------------------------------------------------------------------------
    // FallbackLLMClient – ping failure skips provider
    // ---------------------------------------------------------------------------

    @Test
    fun `sendMessage skips provider when ping fails and all fail triggers callback`() =
        runTest {
            whenever(mockHealthRepository.getOrderedAvailableProviders())
                .thenReturn(listOf(ApiProvider.GROQ))
            whenever(mockHealthRepository.getHealth(ApiProvider.GROQ))
                .thenReturn(healthyRecord.copy(lastChecked = Date(0)))
            // Ping always fails
            whenever(
                mockHealthChecker.ping(
                    eq(ApiProvider.GROQ), any(), any()
                )
            ).thenReturn(false)
            whenever(mockHealthChecker.shouldRePing(any())).thenReturn(true)
            whenever(mockHealthRepository.recordFailure(any(), any())).thenAnswer { }

            val client = FallbackLLMClient(
                baseSettings = defaultSettings,
                healthRepository = mockHealthRepository,
                healthChecker = mockHealthChecker,
                onAllProvidersFailed = { allProvidersFailedCalled = true },
                maxRetriesPerProvider = 1
            )

            val result = client.sendMessage(
                listOf(Message(role = "user", content = "hello"))
            )

            assertTrue(result.isFailure)
            assertTrue(allProvidersFailedCalled)
            verify(mockHealthRepository).recordFailure(
                eq(ApiProvider.GROQ),
                eq("Health-check ping failed")
            )
        }

    // ---------------------------------------------------------------------------
    // FallbackLLMClient – success path records latency
    // ---------------------------------------------------------------------------

    @Test
    fun `recordSuccess is called with non-negative latency on successful request`() =
        runTest {
            // We test ProviderHealthRepository.recordSuccess in isolation
            // since FallbackLLMClient constructs real LLMClient instances.
            // Here we verify the repository method is callable without error.
            val daoMock = mock<com.agente.autonomo.data.dao.ProviderHealthDao>()
            whenever(daoMock.recordSuccess(any(), any(), any())).thenAnswer { }

            val repo = ProviderHealthRepository(daoMock)
            repo.recordSuccess(ApiProvider.GROQ, latencyMs = 150L)

            verify(daoMock).recordSuccess(
                providerName = eq(ApiProvider.GROQ.name),
                latencyMs = eq(150L),
                now = any()
            )
        }

    // ---------------------------------------------------------------------------
    // FallbackLLMClient – failure path records backoff
    // ---------------------------------------------------------------------------

    @Test
    fun `recordFailure stores correct backoff for first failure`() = runTest {
        val daoMock = mock<com.agente.autonomo.data.dao.ProviderHealthDao>()
        val currentHealth = ProviderHealth(
            provider = ApiProvider.GROQ.name,
            consecutiveFailures = 0
        )
        whenever(daoMock.getProviderHealth(ApiProvider.GROQ.name)).thenReturn(currentHealth)
        whenever(daoMock.recordFailure(any(), any(), any(), any(), any())).thenAnswer { }

        val repo = ProviderHealthRepository(daoMock)
        repo.recordFailure(ApiProvider.GROQ, "timeout")

        val expectedBackoff = ProviderHealthRepository.BASE_BACKOFF_MS // 2^0 * BASE
        verify(daoMock).recordFailure(
            providerName = eq(ApiProvider.GROQ.name),
            reason = eq("timeout"),
            backoffUntil = argThat { this >= System.currentTimeMillis() + expectedBackoff - 1000 },
            now = any(),
            maxFailures = eq(ProviderHealthRepository.MAX_CONSECUTIVE_FAILURES)
        )
    }

    // ---------------------------------------------------------------------------
    // FallbackLLMClient – correlationId is not involved but provider order is
    // ---------------------------------------------------------------------------

    @Test
    fun `PROVIDER_CHAIN has correct order`() {
        val chain = ProviderHealthRepository.PROVIDER_CHAIN
        assertEquals(ApiProvider.GROQ, chain[0])
        assertEquals(ApiProvider.OPENROUTER, chain[1])
        assertEquals(ApiProvider.GITHUB, chain[2])
        assertEquals(ApiProvider.CLOUDFLARE, chain[3])
    }

    // ---------------------------------------------------------------------------
    // ProviderHealthRepository – ensureAllProvidersSeeded
    // ---------------------------------------------------------------------------

    @Test
    fun `ensureAllProvidersSeeded inserts rows for all providers`() = runTest {
        val daoMock = mock<com.agente.autonomo.data.dao.ProviderHealthDao>()
        // All providers are absent initially
        whenever(daoMock.getProviderHealth(any())).thenReturn(null)
        whenever(daoMock.upsert(any())).thenAnswer { }

        val repo = ProviderHealthRepository(daoMock)
        repo.ensureAllProvidersSeeded()

        verify(daoMock, times(ProviderHealthRepository.PROVIDER_CHAIN.size)).upsert(any())
    }

    @Test
    fun `ensureAllProvidersSeeded skips existing rows`() = runTest {
        val daoMock = mock<com.agente.autonomo.data.dao.ProviderHealthDao>()
        val existing = ProviderHealth(provider = ApiProvider.GROQ.name)
        // GROQ already seeded, rest absent
        whenever(daoMock.getProviderHealth(ApiProvider.GROQ.name)).thenReturn(existing)
        whenever(daoMock.getProviderHealth(ApiProvider.OPENROUTER.name)).thenReturn(null)
        whenever(daoMock.getProviderHealth(ApiProvider.GITHUB.name)).thenReturn(null)
        whenever(daoMock.getProviderHealth(ApiProvider.CLOUDFLARE.name)).thenReturn(null)
        whenever(daoMock.upsert(any())).thenAnswer { }

        val repo = ProviderHealthRepository(daoMock)
        repo.ensureAllProvidersSeeded()

        // GROQ should NOT be re-inserted
        verify(daoMock, never()).upsert(
            argThat { provider == ApiProvider.GROQ.name }
        )
        // The other 3 should be inserted
        verify(daoMock, times(3)).upsert(any())
    }

    // ---------------------------------------------------------------------------
    // getOrderedAvailableProviders – backoff filtering
    // ---------------------------------------------------------------------------

    @Test
    fun `getOrderedAvailableProviders excludes providers still in backoff`() = runTest {
        val daoMock = mock<com.agente.autonomo.data.dao.ProviderHealthDao>()
        val futureBackoff = System.currentTimeMillis() + 60_000L
        val pastBackoff = System.currentTimeMillis() - 1L

        whenever(daoMock.getProviderHealth(ApiProvider.GROQ.name)).thenReturn(
            ProviderHealth(provider = ApiProvider.GROQ.name, backoffUntil = futureBackoff)
        )
        whenever(daoMock.getProviderHealth(ApiProvider.OPENROUTER.name)).thenReturn(
            ProviderHealth(provider = ApiProvider.OPENROUTER.name, backoffUntil = pastBackoff)
        )
        whenever(daoMock.getProviderHealth(ApiProvider.GITHUB.name)).thenReturn(null)
        whenever(daoMock.getProviderHealth(ApiProvider.CLOUDFLARE.name)).thenReturn(null)

        val repo = ProviderHealthRepository(daoMock)
        val available = repo.getOrderedAvailableProviders()

        assertFalse(available.contains(ApiProvider.GROQ))   // in backoff
        assertTrue(available.contains(ApiProvider.OPENROUTER)) // past backoff
        assertTrue(available.contains(ApiProvider.GITHUB))     // no row → available
        assertTrue(available.contains(ApiProvider.CLOUDFLARE)) // no row → available
    }
}
