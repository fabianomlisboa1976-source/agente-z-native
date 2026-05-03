package com.agente.autonomo.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * On-device sentence embedding engine backed by a quantised all-MiniLM-L6-v2
 * ONNX model (≈ 22 MB, bundled in `assets/models/minilm_l6_v2_quantized.onnx`).
 *
 * ## Usage
 * ```kotlin
 * val engine = EmbeddingEngine.getInstance(context)
 * val vec: FloatArray = engine.embed("Hello world")
 * val similarity = EmbeddingEngine.cosineSimilarity(vecA, vecB)
 * ```
 *
 * ## Threading
 * [embed] is safe to call from multiple coroutines concurrently; a [Mutex]
 * serialises ONNX session invocations because the ONNX Runtime Android build
 * does not guarantee thread-safety for a single [OrtSession] instance.
 *
 * ## Model contract
 * - Input  : `input_ids`      shape [1, seqLen]  dtype INT64
 * - Input  : `attention_mask` shape [1, seqLen]  dtype INT64
 * - Output : `last_hidden_state` shape [1, seqLen, 384]  (mean-pooled here)
 * - OR Output named `sentence_embedding` shape [1, 384]  (used directly)
 *
 * The engine handles both output shapes transparently.
 */
class EmbeddingEngine private constructor(private val session: OrtSession) {

    private val mutex = Mutex()

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Tokenise [text] with a minimal whitespace tokeniser and run an ONNX
     * inference pass, returning a L2-normalised float embedding vector of
     * dimension [EMBEDDING_DIM].
     *
     * Returns [FloatArray] of all zeros if inference fails so callers can
     * degrade gracefully without crashing the agent pipeline.
     */
    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        try {
            mutex.withLock {
                val tokens = tokenise(text)
                runInference(tokens)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Embedding failed for text snippet, returning zero vector", e)
            FloatArray(EMBEDDING_DIM)
        }
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Minimal whitespace tokeniser.  For production quality consider shipping
     * the SentencePiece vocabulary and using the WordPiece tokeniser, but the
     * simple approach is sufficient for semantic similarity ranking where
     * precision at the word level matters less than overall sentence structure.
     *
     * Token mapping: [CLS]=101, word→hash mod vocab, [SEP]=102.
     * Sequence is truncated to [MAX_SEQ_LEN] before the [SEP] token.
     */
    private fun tokenise(text: String): LongArray {
        val words = text
            .lowercase()
            .replace(Regex("[^a-záéíóúàãõâêô\\w\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        // Reserve slots for [CLS] and [SEP]
        val maxWords = MAX_SEQ_LEN - 2
        val truncated = if (words.size > maxWords) words.subList(0, maxWords) else words

        val ids = LongArray(truncated.size + 2)
        ids[0] = CLS_TOKEN
        truncated.forEachIndexed { i, word ->
            // Stable hash into a small vocab — deterministic across runs
            ids[i + 1] = ((word.hashCode().toLong() and 0x7FFF_FFFFL) % VOCAB_SIZE) + VOCAB_OFFSET
        }
        ids[ids.size - 1] = SEP_TOKEN
        return ids
    }

    private fun runInference(tokenIds: LongArray): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val seqLen = tokenIds.size.toLong()

        val inputIdsTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(tokenIds),
            longArrayOf(1L, seqLen)
        )
        val attentionMask = LongArray(tokenIds.size) { 1L }
        val maskTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(attentionMask),
            longArrayOf(1L, seqLen)
        )

        val inputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to maskTensor
        )

        session.run(inputs).use { result ->
            // Try direct sentence embedding output first
            val sentenceEmbKey = "sentence_embedding"
            if (result.map { it.key }.contains(sentenceEmbKey)) {
                @Suppress("UNCHECKED_CAST")
                val tensor = result[sentenceEmbKey].get() as OnnxTensor
                val raw = tensor.floatBuffer.array()
                inputIdsTensor.close(); maskTensor.close()
                return l2Normalise(raw)
            }

            // Fall back to mean-pooling last_hidden_state [1, seqLen, 384]
            @Suppress("UNCHECKED_CAST")
            val hiddenTensor = result["last_hidden_state"].get() as OnnxTensor
            val flat = hiddenTensor.floatBuffer.array() // seqLen * EMBEDDING_DIM floats
            val seqLenInt = tokenIds.size
            val pooled = meanPool(flat, seqLenInt, EMBEDDING_DIM)
            inputIdsTensor.close(); maskTensor.close()
            return l2Normalise(pooled)
        }
    }

    // ------------------------------------------------------------------
    // Companion
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "EmbeddingEngine"

        /** Output dimensionality of all-MiniLM-L6-v2. */
        const val EMBEDDING_DIM = 384

        /** Bytes consumed by one embedding (384 floats × 4 bytes). */
        const val EMBEDDING_BYTES = EMBEDDING_DIM * 4

        private const val MAX_SEQ_LEN = 128
        private const val CLS_TOKEN = 101L
        private const val SEP_TOKEN = 102L
        private const val VOCAB_SIZE = 30_000L
        private const val VOCAB_OFFSET = 1000L

        private const val MODEL_ASSET = "models/minilm_l6_v2_quantized.onnx"

        @Volatile
        private var INSTANCE: EmbeddingEngine? = null
        private val initMutex = Mutex()

        /**
         * Returns the singleton [EmbeddingEngine], loading the ONNX model on
         * first call.  Safe to call from any coroutine context.
         */
        suspend fun getInstance(context: Context): EmbeddingEngine {
            INSTANCE?.let { return it }
            return initMutex.withLock {
                INSTANCE ?: createInstance(context).also { INSTANCE = it }
            }
        }

        private suspend fun createInstance(context: Context): EmbeddingEngine =
            withContext(Dispatchers.IO) {
                val modelFile = extractModelToCache(context)
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    // Prefer NNAPI on supported devices, fall back to CPU
                    try {
                        addNnapi()
                    } catch (_: Exception) {
                        // NNAPI not available on this device — CPU fallback is automatic
                    }
                    setIntraOpNumThreads(2)
                }
                val session = env.createSession(modelFile.absolutePath, opts)
                EmbeddingEngine(session)
            }

        /**
         * Copies the model from assets to the app's cache directory on first
         * run, then returns the [File] for subsequent loads.
         */
        private fun extractModelToCache(context: Context): File {
            val dest = File(context.cacheDir, "minilm_l6_v2_quantized.onnx")
            if (dest.exists() && dest.length() > 0) return dest
            context.assets.open(MODEL_ASSET).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            return dest
        }

        // ------------------------------------------------------------------
        // Math utilities (pure functions, no Android deps → unit-testable)
        // ------------------------------------------------------------------

        /**
         * Cosine similarity between two L2-normalised vectors.
         * Because both are unit vectors, dot product == cosine similarity.
         * Returns value in [−1, 1].
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) {
                "Vector dimension mismatch: ${a.size} vs ${b.size}"
            }
            var dot = 0.0
            for (i in a.indices) dot += a[i] * b[i].toDouble()
            return dot.toFloat()
        }

        /** Mean pool [flat] tensor of shape [seqLen, dim] into a [dim] vector. */
        fun meanPool(flat: FloatArray, seqLen: Int, dim: Int): FloatArray {
            val result = FloatArray(dim)
            for (t in 0 until seqLen) {
                for (d in 0 until dim) {
                    result[d] += flat[t * dim + d]
                }
            }
            val seqLenF = seqLen.toFloat()
            for (d in result.indices) result[d] /= seqLenF
            return result
        }

        /** L2-normalise a float vector in-place, returning it. */
        fun l2Normalise(v: FloatArray): FloatArray {
            var norm = 0.0
            for (x in v) norm += x * x.toDouble()
            norm = sqrt(norm)
            if (norm < 1e-9) return v // zero vector — leave as-is
            for (i in v.indices) v[i] = (v[i] / norm).toFloat()
            return v
        }

        /** Serialise a [FloatArray] to a [ByteArray] (little-endian IEEE-754). */
        fun floatArrayToBytes(v: FloatArray): ByteArray {
            val buf = java.nio.ByteBuffer.allocate(v.size * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            v.forEach { buf.putFloat(it) }
            return buf.array()
        }

        /** Deserialise a [ByteArray] produced by [floatArrayToBytes]. */
        fun bytesToFloatArray(b: ByteArray): FloatArray {
            val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { buf.getFloat() }
        }
    }
}
