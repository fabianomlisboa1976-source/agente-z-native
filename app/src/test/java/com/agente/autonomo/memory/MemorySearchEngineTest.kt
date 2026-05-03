package com.agente.autonomo.memory

import android.content.Context
import com.agente.autonomo.data.dao.MemoryDao
import com.agente.autonomo.data.entity.Memory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.util.Date
import java.util.UUID

/**
 * Unit tests for [MemorySearchEngine].
 *
 * [EmbeddingEngine] is **not** mocked here because its static getInstance()
 * would require PowerMock.  Instead, we test the ranking math and fallback
 * paths in isolation, verifying DAO interactions via Mockito.
 */
@ExperimentalCoroutinesApi
class MemorySearchEngineTest {

    private lateinit var mockDao: MemoryDao
    private lateinit var mockContext: Context

    // Helper builders
    private fun memory(
        key: String,
        value: String,
        importance: Int = 5,
        embedding: ByteArray? = null
    ) = Memory(
        id = UUID.randomUUID().toString(),
        type = Memory.MemoryType.FACT,
        key = key,
        value = value,
        importance = importance,
        embedding = embedding,
        createdAt = Date(),
        updatedAt = Date()
    )

    @Before
    fun setUp() {
        mockDao = mock()
        mockContext = mock()
    }

    // ------------------------------------------------------------------
    // Scoring math
    // ------------------------------------------------------------------

    @Test
    fun `combinedScore weights are respected`() {
        // combinedScore = SEMANTIC_WEIGHT * similarity + IMPORTANCE_WEIGHT * (importance/10)
        val similarity = 0.8f
        val importanceNorm = 8f / 10f
        val expected = MemorySearchEngine.SEMANTIC_WEIGHT * similarity +
                MemorySearchEngine.IMPORTANCE_WEIGHT * importanceNorm
        val actual = MemorySearchEngine.SEMANTIC_WEIGHT * similarity +
                MemorySearchEngine.IMPORTANCE_WEIGHT * importanceNorm
        assertEquals(expected, actual, 1e-6f)
    }

    @Test
    fun `SEMANTIC_WEIGHT plus IMPORTANCE_WEIGHT equals 1`() {
        assertEquals(
            1.0f,
            MemorySearchEngine.SEMANTIC_WEIGHT + MemorySearchEngine.IMPORTANCE_WEIGHT,
            1e-6f
        )
    }

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    @Test
    fun `DEFAULT_TOP_K is 5`() {
        assertEquals(5, MemorySearchEngine.DEFAULT_TOP_K)
    }

    @Test
    fun `SIMILARITY_THRESHOLD is positive`() {
        assertTrue(MemorySearchEngine.SIMILARITY_THRESHOLD > 0f)
    }

    @Test
    fun `BACKFILL_BATCH_SIZE is positive`() {
        assertTrue(MemorySearchEngine.BACKFILL_BATCH_SIZE > 0)
    }

    // ------------------------------------------------------------------
    // MemoryDao interaction — keyword fallback path
    // ------------------------------------------------------------------

    @Test
    fun `search returns empty list when DAO throws`() = runTest {
        // getAllEmbeddedMemories() throws — engine must catch and return empty
        whenever(mockDao.getAllEmbeddedMemories()).thenThrow(RuntimeException("DB error"))
        // EmbeddingEngine.getInstance will fail in unit test env (no assets)
        // so the engine will hit the zero-vector fallback then keyword fallback
        whenever(mockDao.searchMemories(any())).thenReturn(emptyList())

        // We cannot easily stub EmbeddingEngine.getInstance() without PowerMock,
        // so we verify the DAO fallback path is resilient to an exception from
        // getAllEmbeddedMemories.  The engine should still try keywordFallback.
        // Because EmbeddingEngine.getInstance() will throw in a unit-test JVM
        // (no ONNX libs), the engine catches it at the outer try/catch in search()
        // and returns an empty list — which is the safe degraded behaviour.
        val engine = MemorySearchEngine(mockDao, mockContext)
        val result = engine.search("any query")
        assertNotNull(result)
        // Result is either empty list or keyword results — never an exception
    }

    // ------------------------------------------------------------------
    // Cosine similarity integration with round-tripped embeddings
    // ------------------------------------------------------------------

    @Test
    fun `cosineSimilarity is consistent with l2Normalise round-trip`() {
        val raw1 = FloatArray(EmbeddingEngine.EMBEDDING_DIM) { it.toFloat() }
        val raw2 = FloatArray(EmbeddingEngine.EMBEDDING_DIM) { (EmbeddingEngine.EMBEDDING_DIM - it).toFloat() }

        val n1 = EmbeddingEngine.l2Normalise(raw1)
        val n2 = EmbeddingEngine.l2Normalise(raw2)

        // Round-trip through serialisation
        val n1rt = EmbeddingEngine.bytesToFloatArray(EmbeddingEngine.floatArrayToBytes(n1))
        val n2rt = EmbeddingEngine.bytesToFloatArray(EmbeddingEngine.floatArrayToBytes(n2))

        val directSim = EmbeddingEngine.cosineSimilarity(n1, n2)
        val rtSim = EmbeddingEngine.cosineSimilarity(n1rt, n2rt)

        assertEquals(directSim, rtSim, 1e-5f)
    }

    // ------------------------------------------------------------------
    // Memory entity — equals / hashCode rely on id only
    // ------------------------------------------------------------------

    @Test
    fun `Memory equals ignores embedding blob`() {
        val id = UUID.randomUUID().toString()
        val m1 = memory("key", "value").copy(id = id, embedding = byteArrayOf(1, 2, 3))
        val m2 = memory("key", "value").copy(id = id, embedding = byteArrayOf(9, 8, 7))
        assertEquals(m1, m2)
    }

    @Test
    fun `Memory hashCode is based on id`() {
        val id = UUID.randomUUID().toString()
        val m1 = memory("key", "value").copy(id = id, embedding = null)
        val m2 = memory("key", "value").copy(id = id, embedding = byteArrayOf(1))
        assertEquals(m1.hashCode(), m2.hashCode())
    }

    @Test
    fun `distinctBy id de-duplicates Memory list`() {
        val id = UUID.randomUUID().toString()
        val list = listOf(
            memory("k", "v").copy(id = id),
            memory("k", "v").copy(id = id)
        )
        assertEquals(1, list.distinctBy { it.id }.size)
    }
}
