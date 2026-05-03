package com.agente.autonomo.memory

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Unit tests for pure-function helpers in [EmbeddingEngine].
 *
 * All tests run on the JVM without an Android emulator or ONNX Runtime;
 * only the static utility methods are exercised here.
 */
class EmbeddingEngineTest {

    // ------------------------------------------------------------------
    // l2Normalise
    // ------------------------------------------------------------------

    @Test
    fun `l2Normalise produces unit vector`() {
        val v = floatArrayOf(3f, 4f)          // norm = 5
        val normalised = EmbeddingEngine.l2Normalise(v)
        val norm = sqrt(normalised.fold(0.0) { acc, x -> acc + x * x }).toFloat()
        assertEquals(1.0f, norm, 1e-6f)
    }

    @Test
    fun `l2Normalise leaves zero vector unchanged`() {
        val v = FloatArray(EmbeddingEngine.EMBEDDING_DIM)
        val result = EmbeddingEngine.l2Normalise(v)
        assertTrue(result.all { it == 0f })
    }

    @Test
    fun `l2Normalise single-element vector becomes 1 or -1`() {
        assertEquals(1f, EmbeddingEngine.l2Normalise(floatArrayOf(42f))[0], 1e-6f)
        assertEquals(-1f, EmbeddingEngine.l2Normalise(floatArrayOf(-7f))[0], 1e-6f)
    }

    // ------------------------------------------------------------------
    // cosineSimilarity
    // ------------------------------------------------------------------

    @Test
    fun `cosineSimilarity of identical unit vectors is 1`() {
        val v = EmbeddingEngine.l2Normalise(floatArrayOf(1f, 0f, 0f, 0f))
        assertEquals(1.0f, EmbeddingEngine.cosineSimilarity(v, v), 1e-6f)
    }

    @Test
    fun `cosineSimilarity of orthogonal unit vectors is 0`() {
        val a = EmbeddingEngine.l2Normalise(floatArrayOf(1f, 0f))
        val b = EmbeddingEngine.l2Normalise(floatArrayOf(0f, 1f))
        assertEquals(0.0f, EmbeddingEngine.cosineSimilarity(a, b), 1e-6f)
    }

    @Test
    fun `cosineSimilarity of opposite unit vectors is -1`() {
        val a = EmbeddingEngine.l2Normalise(floatArrayOf(1f, 0f))
        val b = EmbeddingEngine.l2Normalise(floatArrayOf(-1f, 0f))
        assertEquals(-1.0f, EmbeddingEngine.cosineSimilarity(a, b), 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cosineSimilarity throws on dimension mismatch`() {
        EmbeddingEngine.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(1f))
    }

    // ------------------------------------------------------------------
    // meanPool
    // ------------------------------------------------------------------

    @Test
    fun `meanPool averages correctly over sequence`() {
        // 2 tokens, dim=2 → flat = [1,2, 3,4]
        val flat = floatArrayOf(1f, 2f, 3f, 4f)
        val result = EmbeddingEngine.meanPool(flat, seqLen = 2, dim = 2)
        assertEquals(2.0f, result[0], 1e-6f)  // (1+3)/2
        assertEquals(3.0f, result[1], 1e-6f)  // (2+4)/2
    }

    @Test
    fun `meanPool single token returns same values`() {
        val flat = floatArrayOf(5f, 6f, 7f)
        val result = EmbeddingEngine.meanPool(flat, seqLen = 1, dim = 3)
        assertArrayEquals(flat, result, 1e-6f)
    }

    // ------------------------------------------------------------------
    // floatArrayToBytes / bytesToFloatArray round-trip
    // ------------------------------------------------------------------

    @Test
    fun `round-trip serialisation preserves values`() {
        val original = FloatArray(EmbeddingEngine.EMBEDDING_DIM) { it.toFloat() / 1000f }
        val bytes = EmbeddingEngine.floatArrayToBytes(original)
        assertEquals(EmbeddingEngine.EMBEDDING_BYTES, bytes.size)
        val recovered = EmbeddingEngine.bytesToFloatArray(bytes)
        assertArrayEquals(original, recovered, 1e-6f)
    }

    @Test
    fun `round-trip of zero vector produces zero vector`() {
        val zero = FloatArray(EmbeddingEngine.EMBEDDING_DIM)
        val recovered = EmbeddingEngine.bytesToFloatArray(
            EmbeddingEngine.floatArrayToBytes(zero)
        )
        assertArrayEquals(zero, recovered, 1e-6f)
    }

    @Test
    fun `round-trip of l2-normalised vector preserves unit norm`() {
        val raw = FloatArray(EmbeddingEngine.EMBEDDING_DIM) { (it % 10 + 1).toFloat() }
        val normalised = EmbeddingEngine.l2Normalise(raw)
        val recovered = EmbeddingEngine.bytesToFloatArray(
            EmbeddingEngine.floatArrayToBytes(normalised)
        )
        val norm = sqrt(recovered.fold(0.0) { acc, x -> acc + x * x }).toFloat()
        assertEquals(1.0f, norm, 1e-5f)
    }

    // ------------------------------------------------------------------
    // EMBEDDING_DIM / EMBEDDING_BYTES constants
    // ------------------------------------------------------------------

    @Test
    fun `EMBEDDING_BYTES equals EMBEDDING_DIM times 4`() {
        assertEquals(EmbeddingEngine.EMBEDDING_DIM * 4, EmbeddingEngine.EMBEDDING_BYTES)
    }
}
