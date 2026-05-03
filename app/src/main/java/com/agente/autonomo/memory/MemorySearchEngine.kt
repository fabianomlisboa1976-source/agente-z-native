package com.agente.autonomo.memory

import android.content.Context
import android.util.Log
import com.agente.autonomo.data.dao.MemoryDao
import com.agente.autonomo.data.entity.Memory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Semantic memory retrieval engine.
 *
 * Replaces the keyword-based `LIKE '%query%'` approach in [MemoryDao.searchMemories]
 * with a two-phase retrieval strategy:
 *
 * ## Phase 1 — Candidate retrieval
 * All active memories that have a pre-computed embedding are fetched from Room
 * via [MemoryDao.getAllEmbeddedMemories].  Memories without embeddings (legacy
 * rows) are retrieved separately via [MemoryDao.searchMemories] as a fallback.
 *
 * ## Phase 2 — Cosine similarity re-ranking
 * The query text is embedded by [EmbeddingEngine.embed].  Each candidate
 * embedding is deserialised from its BLOB and compared with
 * [EmbeddingEngine.cosineSimilarity].  A combined score blends semantic
 * similarity with the stored importance field:
 *
 * ```
 * score = SEMANTIC_WEIGHT * cosineSimilarity + IMPORTANCE_WEIGHT * (importance / 10)
 * ```
 *
 * Results are sorted descending by score and the top [topK] entries are
 * returned.
 *
 * ## Fallback
 * If the [EmbeddingEngine] fails to produce a query embedding (returns a zero
 * vector), the engine falls back transparently to keyword-based search so the
 * agent pipeline is never left without memory context.
 *
 * ## Back-fill
 * [backfillEmbeddings] processes up to [BACKFILL_BATCH_SIZE] rows per call,
 * computing and persisting embeddings for legacy rows.  It should be invoked
 * from a background coroutine at application start-up or whenever new memories
 * are inserted without an embedding.
 */
class MemorySearchEngine(
    private val memoryDao: MemoryDao,
    private val context: Context
) {

    companion object {
        private const val TAG = "MemorySearchEngine"

        /**
         * Weight applied to the normalised cosine similarity score (range [0,1]
         * after clamping negative values to 0).
         */
        const val SEMANTIC_WEIGHT = 0.75f

        /**
         * Weight applied to the normalised importance score (importance / 10).
         */
        const val IMPORTANCE_WEIGHT = 0.25f

        /**
         * Default number of top memories to return per search.
         */
        const val DEFAULT_TOP_K = 5

        /**
         * Minimum cosine similarity for a candidate to be included in results.
         * Candidates below this threshold are suppressed even if importance is
         * high, unless the keyword fallback is active.
         */
        const val SIMILARITY_THRESHOLD = 0.20f

        /**
         * Maximum rows processed per [backfillEmbeddings] invocation.
         */
        const val BACKFILL_BATCH_SIZE = 50
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Retrieve the [topK] most semantically relevant active memories for
     * [query].
     *
     * The function is non-throwing: any internal error produces an empty list
     * and logs a warning, preserving agent pipeline continuity.
     *
     * @param query     Raw user message or agent prompt snippet.
     * @param topK      Maximum number of results (default [DEFAULT_TOP_K]).
     * @return          Ranked [Memory] list, highest relevance first.
     */
    suspend fun search(query: String, topK: Int = DEFAULT_TOP_K): List<Memory> =
        withContext(Dispatchers.Default) {
            try {
                searchInternal(query, topK)
            } catch (e: Exception) {
                Log.w(TAG, "MemorySearchEngine.search failed — returning empty list", e)
                emptyList()
            }
        }

    /**
     * Compute and persist embeddings for up to [BACKFILL_BATCH_SIZE] legacy
     * memory rows that do not yet have an embedding.
     *
     * Safe to call on every app start; it is a no-op when all rows already
     * have embeddings.
     *
     * @return Number of rows successfully back-filled.
     */
    suspend fun backfillEmbeddings(): Int = withContext(Dispatchers.IO) {
        try {
            val engine = EmbeddingEngine.getInstance(context)
            val pending = memoryDao.getMemoriesWithoutEmbedding(BACKFILL_BATCH_SIZE)
            var count = 0
            for (memory in pending) {
                val text = buildEmbeddingText(memory)
                val vec = engine.embed(text)
                if (!isZeroVector(vec)) {
                    val blob = EmbeddingEngine.floatArrayToBytes(vec)
                    memoryDao.updateEmbedding(memory.id, blob)
                    count++
                }
            }
            Log.i(TAG, "Back-filled embeddings for $count / ${pending.size} memories")
            count
        } catch (e: Exception) {
            Log.w(TAG, "backfillEmbeddings failed", e)
            0
        }
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private suspend fun searchInternal(query: String, topK: Int): List<Memory> {
        val engine = EmbeddingEngine.getInstance(context)
        val queryVec = engine.embed(query)

        // If the query embedding is a zero vector, fall back to keyword search
        if (isZeroVector(queryVec)) {
            Log.w(TAG, "Query embedding is zero vector — using keyword fallback")
            return keywordFallback(query, topK)
        }

        // Phase 1: fetch all embedded candidates from DB
        val embedded = memoryDao.getAllEmbeddedMemories()

        // Phase 2: score and rank
        val scored = embedded.mapNotNull { memory ->
            val blob = memory.embedding ?: return@mapNotNull null
            val memVec = try {
                EmbeddingEngine.bytesToFloatArray(blob)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to deserialise embedding for memory ${memory.id}", e)
                return@mapNotNull null
            }
            val similarity = EmbeddingEngine.cosineSimilarity(queryVec, memVec)
                .coerceAtLeast(0f) // clamp negatives to 0
            if (similarity < SIMILARITY_THRESHOLD) return@mapNotNull null
            val importanceNorm = memory.importance.coerceIn(1, 10) / 10f
            val combinedScore = SEMANTIC_WEIGHT * similarity + IMPORTANCE_WEIGHT * importanceNorm
            Pair(memory, combinedScore)
        }

        val topEmbedded = scored
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }

        // Supplement with keyword results for legacy rows if we have room
        val remaining = topK - topEmbedded.size
        return if (remaining > 0) {
            val keywordResults = keywordFallback(query, remaining)
            val embeddedIds = topEmbedded.map { it.id }.toSet()
            val supplementary = keywordResults.filter { it.id !in embeddedIds }
            topEmbedded + supplementary.take(remaining)
        } else {
            topEmbedded
        }
    }

    /**
     * Keyword fallback: extract meaningful tokens from [query] and execute
     * per-token LIKE queries, then de-duplicate and return the top [limit].
     * Mirrors the heuristic previously used directly in [AgentOrchestrator].
     */
    private suspend fun keywordFallback(query: String, limit: Int): List<Memory> {
        val keywords = query
            .lowercase()
            .split(" ")
            .filter { it.length > 3 }
            .take(5)

        return if (keywords.isEmpty()) {
            emptyList()
        } else {
            keywords
                .flatMap { kw ->
                    try { memoryDao.searchMemories(kw) } catch (_: Exception) { emptyList() }
                }
                .distinctBy { it.id }
                .sortedByDescending { it.importance }
                .take(limit)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Builds the text that represents this memory for embedding purposes.
     * Concatenates [Memory.key] and [Memory.value] so the model sees the
     * full semantic content of the memory.
     */
    private fun buildEmbeddingText(memory: Memory): String =
        if (memory.key.isBlank()) memory.value
        else "${memory.key}: ${memory.value}"

    /** Returns true if every element of [v] is within epsilon of zero. */
    private fun isZeroVector(v: FloatArray, epsilon: Float = 1e-9f): Boolean =
        v.all { kotlin.math.abs(it) < epsilon }
}
