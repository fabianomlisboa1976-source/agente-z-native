package com.agente.autonomo.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.agente.autonomo.ui.activities.SettingsActivity

/**
 * Posts a local Android notification when [FallbackLLMClient] determines
 * that every provider in the failover chain is exhausted.
 *
 * ## Channel
 * Uses a dedicated notification channel (`provider_exhausted`) with
 * [NotificationManager.IMPORTANCE_HIGH] so the alert appears as a heads-up
 * banner.  The channel is created lazily on first use so it is safe to call
 * from any context.
 *
 * ## Action
 * The notification carries a tap action that opens [SettingsActivity] so the
 * user can inspect or update API keys without navigating manually.
 *
 * ## Deduplication
 * The notification ID is fixed at [NOTIFICATION_ID]; subsequent calls while
 * the notification is still visible will update it in-place rather than
 * creating duplicates.
 */
class ProviderExhaustedNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "provider_exhausted"
        const val CHANNEL_NAME = "LLM Provider Alerts"
        const val NOTIFICATION_ID = 9001
        const val TAG = "ProviderExhaustedNotifier"
    }

    /**
     * Creates (if necessary) the notification channel and posts or updates
     * the "all providers exhausted" alert.
     *
     * Safe to call from a background coroutine; [NotificationManagerCompat]
     * and channel creation are thread-safe.
     */
    fun notifyAllProvidersExhausted() {
        ensureChannel()

        val settingsIntent = Intent(context, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Todos os provedores de IA falharam")
            .setContentText(
                "Groq, OpenRouter, GitHub Models e Cloudflare estão inacessíveis. " +
                "Verifique sua chave de API e conexão."
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "O Agente Autônomo tentou todos os provedores configurados " +
                        "(Groq → OpenRouter → GitHub Models → Cloudflare) e todos " +
                        "falharam.\n\n" +
                        "Possíveis causas:\n" +
                        "• Chave de API inválida ou expirada\n" +
                        "• Limite de requisições atingido (rate-limit)\n" +
                        "• Sem conexão com a internet\n\n" +
                        "Toque para abrir Configurações."
                    )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_preferences,
                "Abrir Configurações",
                pendingIntent
            )
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on API 33+
            android.util.Log.w(TAG, "Cannot post notification – permission denied: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "Alertas quando todos os provedores de LLM estão inacessíveis"
                enableLights(true)
                enableVibration(true)
            }
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
