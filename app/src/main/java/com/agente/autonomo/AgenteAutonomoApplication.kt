package com.agente.autonomo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.agente.autonomo.api.ProviderHealthRepository
import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.service.ProviderExhaustedNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class responsible for:
 * 1. Creating all notification channels (foreground service + provider alerts).
 * 2. Seeding [ProviderHealth] rows for every provider in the failover chain.
 */
class AgenteAutonomoApplication : Application() {

    companion object {
        const val TAG = "AgenteApp"
        const val CHANNEL_ID_SERVICE = "agente_service"
        const val CHANNEL_ID_MESSAGES = "agente_messages"
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        seedProviderHealth()
    }

    // ---------------------------------------------------------------------------
    // Notification channels
    // ---------------------------------------------------------------------------

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Foreground service channel
            NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Agente Autônomo Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Canal para o serviço persistente do agente"
                manager.createNotificationChannel(this)
            }

            // Chat / message channel
            NotificationChannel(
                CHANNEL_ID_MESSAGES,
                "Mensagens do Agente",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificações de novas respostas dos agentes"
                manager.createNotificationChannel(this)
            }

            // Provider exhausted alert channel (created by ProviderExhaustedNotifier
            // on first use, but we register it here too for Settings display).
            NotificationChannel(
                ProviderExhaustedNotifier.CHANNEL_ID,
                ProviderExhaustedNotifier.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas quando todos os provedores de LLM estão inacessíveis"
                enableLights(true)
                enableVibration(true)
                manager.createNotificationChannel(this)
            }

            Log.d(TAG, "Notification channels created")
        }
    }

    // ---------------------------------------------------------------------------
    // Provider health seeding
    // ---------------------------------------------------------------------------

    /**
     * Seeds default [ProviderHealth] rows for every provider in the failover
     * chain.  Runs on [Dispatchers.IO] via [appScope] so it does not block
     * the main thread during app startup.
     */
    private fun seedProviderHealth() {
        appScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@AgenteAutonomoApplication)
                val repo = ProviderHealthRepository(db.providerHealthDao())
                repo.ensureAllProvidersSeeded()
                Log.d(TAG, "Provider health records seeded")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to seed provider health: ${e.message}", e)
            }
        }
    }
}
