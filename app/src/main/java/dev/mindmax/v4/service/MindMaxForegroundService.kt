package dev.mindmax.v4.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.mindmax.R
import dev.mindmax.v4.MainActivity
import dev.mindmax.v4.audit.AuditLogger
import dev.mindmax.v4.core.di.ServiceLocator
import dev.mindmax.v4.data.entity.AuditType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service whose only job is to keep the LLM correlation alive when
 * the user steps away from the foreground chat. We deliberately do NOT spawn
 * long-running LLM polling here — Android (especially Android 15+) is quick to
 * kill FGS-owned background work that touches the network every few seconds.
 *
 * On start we:
 *   1. Build the notification channel [createChannel] on Android O+.
 *   2. Promote to FGS via [startForeground] with `FOREGROUND_SERVICE_TYPE_DATA_SYNC`
 *      on 14+ where the typed permission is required.
 *   3. Spin up a [Scope] for the lifetime of the service.
 *
 * On stop we cancel the scope and try to drop the foreground state. If a
 * notification is present, we keep the channel but remove the live notification.
 *
 * Boot receiver and external callers go through the [start]/[stop] helpers,
 * which are also exposed as Intent actions `ACTION_START` / `ACTION_STOP`.
 */
class MindMaxForegroundService : Service() {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audit by lazy { AuditLogger() }
    private val network by lazy { NetworkObserver(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        network.attach()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_KEEP_ALIVE
        when (action) {
            ACTION_KEEP_ALIVE -> startKeepAlive()
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startKeepAlive()
        }
        return START_STICKY
    }

    private fun startKeepAlive() {
        val notification = buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (security: SecurityException) {
            // POST_NOTIFICATIONS not granted. Fall back to a "secret" foreground
            // — Android still keeps the process alive even without a notification.
            stopSelf()
            return
        }
        audit.log(
            type = AuditType.SYSTEM,
            action = "service.start",
            details = "FGS ativo (DATA_SYNC).",
        )
        scope.launch { /* future: warming up the LlmClient cache */ }
    }

    override fun onDestroy() {
        scope.cancel()
        network.detach()
        audit.log(
            type = AuditType.SYSTEM,
            action = "service.stop",
            details = "FGS parado.",
        )
        super.onDestroy()
    }

    companion object {
        const val ACTION_KEEP_ALIVE = "dev.mindmax.v4.action.KEEP_ALIVE"
        const val ACTION_STOP = "dev.mindmax.v4.action.STOP"
        private const val CHANNEL_ID = "mindmax_v4_channel"
        private const val NOTIF_ID = 1000

        fun start(context: Context) {
            val intent = Intent(context, MindMaxForegroundService::class.java)
                .setAction(ACTION_KEEP_ALIVE)
            // Foreground services must be launched via startForegroundService
            // when called from a regular Context.
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MindMaxForegroundService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MindMax V4",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mantém o serviço em execução em background."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context) =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("MindMax V4")
                .setContentText("Pronto para responder.")
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
    }
}

/**
 * Observable FGS state for the UI layer. Currently the only listener is the
 * service's own internal start/stop, but having this as a static surface
 * makes it cheap to extend with broadcast receivers later.
 */
object MindMaxServiceState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun markStarted() {
        _running.value = true
    }

    fun markStopped() {
        _running.value = false
    }
}

/** Convenience object for callers to start/stop the service from `ServiceLocator.init`. */
object ServiceStarter {
    /**
     * Suspend variant kept for callers already inside a coroutine (e.g. a future
     * boot-completed broadcast that has migrated to goAsync). Reads the
     * persisted settings off the main thread and acts on the result.
     */
    suspend fun ensureStartedIfEnabled(context: Context) {
        val enabled = ServiceLocator.settingsRepository.current()?.serviceEnabled == true
        apply(context, enabled)
    }

    /**
     * Fire-and-forget version used from `Application.onCreate` and from
     * `BootReceiver.onReceive`. Hopping to [ServiceLocator.scope] guarantees we
     * never block the main thread (a Room read on the main thread freezes the
     * bootstrap; on `BootReceiver` it would exceed the 10s broadcast ceiling
     * and Android would kill the receiver before we started the service).
     */
    fun ensureStartedIfEnabledAsync(context: Context) {
        ServiceLocator.scope.launch {
            runCatching {
                val enabled = ServiceLocator.settingsRepository.current()?.serviceEnabled == true
                apply(context, enabled)
            }
        }
    }

    private fun apply(context: Context, enabled: Boolean) {
        if (enabled) {
            MindMaxForegroundService.start(context)
        } else {
            MindMaxForegroundService.stop(context)
        }
    }
}
