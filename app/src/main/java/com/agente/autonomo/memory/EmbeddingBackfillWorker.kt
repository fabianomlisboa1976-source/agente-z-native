package com.agente.autonomo.memory

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.agente.autonomo.data.database.AppDatabase

/**
 * A [CoroutineWorker] that back-fills sentence embeddings for legacy [Memory]
 * rows that were inserted before the embedding engine was available.
 *
 * ## Scheduling
 * Enqueue via [enqueue] from [com.agente.autonomo.AgenteAutonomoApplication] or
 * from the [com.agente.autonomo.service.AgenteAutonomoService] start-up path.
 * The work is scheduled with [ExistingWorkPolicy.KEEP] so multiple enqueue
 * calls are idempotent (only one instance runs at a time).
 *
 * ## Retry
 * The worker returns [Result.retry] on failure, relying on WorkManager's default
 * exponential back-off policy for transient errors (e.g. ONNX model not yet
 * extracted from assets).
 */
class EmbeddingBackfillWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "EmbeddingBackfillWorker"
        private const val WORK_NAME = "embedding_backfill"

        /**
         * Schedule a one-time back-fill job.  If one is already pending or
         * running, the existing job is kept ([ExistingWorkPolicy.KEEP]).
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<EmbeddingBackfillWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            Log.d(TAG, "Enqueued back-fill work (policy=KEEP)")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getDatabase(applicationContext)
            val engine = MemorySearchEngine(
                memoryDao = db.memoryDao(),
                context = applicationContext
            )
            val filled = engine.backfillEmbeddings()
            Log.i(TAG, "Back-fill complete: $filled rows processed")
            // If there may still be more rows to process, re-schedule
            if (filled >= MemorySearchEngine.BACKFILL_BATCH_SIZE) {
                enqueue(applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Back-fill worker failed", e)
            Result.retry()
        }
    }
}
