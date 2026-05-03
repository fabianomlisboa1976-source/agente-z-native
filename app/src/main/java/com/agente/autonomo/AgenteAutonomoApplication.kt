package com.agente.autonomo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.memory.EmbeddingBackfillWorker

/**
 * Application class.
 *
 * Responsibilities:
 * - Create notification channels required by [com.agente.autonomo.service.AgenteAutonomoService].
 * - Initialise the Room database singleton.
 * - Enqueue the [EmbeddingBackfillWorker] to back-fill sentence embeddings for
 *   any legacy [com.agente.autonomo.data.entity.Memory] rows that pre-date the
 *   ONNX embedding engine.
 */
class AgenteAutonomoApplication : Application() {

    companion object {
        private const val TAG = "AgenteAutonomoApp"
        const val CHANNEL_ID_SERVICE = "agente_autonomo_service"
        const val CHANNEL_ID_ALERTS = "agente_autonomo_alerts"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Initialise DB eagerly so migrations run before any service starts
        AppDatabase.getDatabase(this)
        // Back-fill embeddings for legacy memory rows (idempotent, runs in background)
        EmbeddingBackfillWorker.enqueue(this)
        Log.i(TAG, "Application initialised")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_SERVICE,
                    "Agente Autônomo Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Canal para o serviço em primeiro plano do Agente Autônomo"
                }
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_ALERTS,
                    "Alertas do Agente",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notificações e alertas gerados pelos agentes"
                }
            )
        }
    }
}
