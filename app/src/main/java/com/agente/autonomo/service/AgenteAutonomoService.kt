package com.agente.autonomo.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.agente.autonomo.R
import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.data.entity.AuditLog
import com.agente.autonomo.data.entity.Task
import com.agente.autonomo.agent.AgentManager
import com.agente.autonomo.agent.AgentOrchestrator
import com.agente.autonomo.utils.AuditLogger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Foreground Service principal que mantém o agente rodando 24/7
 */
class AgenteAutonomoService : Service() {

    companion object {
        const val TAG = "AgenteAutonomoService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "agente_autonomo_channel"
        
        const val ACTION_START = "com.agente.autonomo.ACTION_START"
        const val ACTION_STOP = "com.agente.autonomo.ACTION_STOP"
        const val ACTION_PROCESS_MESSAGE = "com.agente.autonomo.ACTION_PROCESS_MESSAGE"
        
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        
        @Volatile
        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var database: AppDatabase
    private lateinit var agentManager: AgentManager
    private lateinit var agentOrchestrator: AgentOrchestrator
    private lateinit var auditLogger: AuditLogger
    
    private var taskProcessorJob: Job? = null
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Serviço criado")
        
        database = AppDatabase.getDatabase(this)
        auditLogger = AuditLogger(database.auditLogDao())
        agentManager = AgentManager(database, auditLogger)
        agentOrchestrator = AgentOrchestrator(database, agentManager, auditLogger)
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                startService()
            }
            ACTION_STOP -> {
                stopService()
            }
            ACTION_PROCESS_MESSAGE -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE)
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: "default"
                if (message != null) {
                    processMessage(message, conversationId)
                }
            }
        }
        
        // START_STICKY garante que o serviço seja reiniciado se for morto pelo sistema
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Serviço destruído")
        
        isRunning = false
        serviceScope.cancel()
        
        // Enviar broadcast para reiniciar o serviço
        val broadcastIntent = Intent(this, RestartService::class.java)
        sendBroadcast(broadcastIntent)
        
        serviceScope.launch {
            auditLogger.logSystem("Serviço destruído, tentando reiniciar...")
        }
    }

    private fun startService() {
        if (isRunning) {
            Log.d(TAG, "Serviço já está rodando")
            return
        }
        
        Log.d(TAG, "Iniciando serviço...")
        
        // Criar notificação persistente (obrigatório para foreground service)
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        isRunning = true
        
        serviceScope.launch {
            auditLogger.logSystem("Serviço iniciado com sucesso")
        }
        
        // Iniciar processadores
        startTaskProcessor()
        startHeartbeat()
        
        Log.d(TAG, "Serviço iniciado com sucesso")
    }

    private fun stopService() {
        Log.d(TAG, "Parando serviço...")
        
        isRunning = false
        taskProcessorJob?.cancel()
        heartbeatJob?.cancel()
        
        serviceScope.launch {
            auditLogger.logSystem("Serviço parado pelo usuário")
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTaskProcessor() {
        taskProcessorJob = serviceScope.launch {
            while (isActive && isRunning) {
                try {
                    processPendingTasks()
                    delay(TimeUnit.SECONDS.toMillis(5)) // Verifica a cada 5 segundos
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no processador de tarefas", e)
                    auditLogger.logError(
                        action = "Processar tarefas",
                        error = e.message ?: "Erro desconhecido"
                    )
                    delay(TimeUnit.SECONDS.toMillis(10)) // Espera mais em caso de erro
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (isActive && isRunning) {
                try {
                    // Log de heartbeat a cada 5 minutos
                    auditLogger.logSystem("Heartbeat: Serviço ativo")
                    updateNotification()
                    delay(TimeUnit.MINUTES.toMillis(5))
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no heartbeat", e)
                }
            }
        }
    }

    private suspend fun processPendingTasks() {
        val pendingTasks = database.taskDao().getPendingTasks()
        
        for (task in pendingTasks) {
            try {
                database.taskDao().markTaskAsRunning(task.id)
                
                val startTime = System.currentTimeMillis()
                
                // Executar a tarefa através do orquestrador
                val result = agentOrchestrator.executeTask(task)
                
                val duration = System.currentTimeMillis() - startTime
                
                database.taskDao().markTaskAsCompleted(
                    task.id,
                    result = result
                )
                
                auditLogger.logAction(
                    action = "Tarefa executada: ${task.title}",
                    agentId = task.assignedAgent,
                    details = "Tipo: ${task.type}, Duração: ${duration}ms",
                    durationMs = duration
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao executar tarefa ${task.id}", e)
                
                val retryCount = task.retryCount + 1
                if (retryCount >= task.maxRetries) {
                    database.taskDao().markTaskAsFailed(task.id, e.message ?: "Erro desconhecido")
                    auditLogger.logError(
                        action = "Falha na tarefa: ${task.title}",
                        error = e.message ?: "Erro desconhecido"
                    )
                } else {
                    database.taskDao().incrementRetryCount(task.id)
                    database.taskDao().updateTaskStatus(task.id, Task.TaskStatus.RETRYING)
                }
            }
        }
    }

    private fun processMessage(message: String, conversationId: String) {
        serviceScope.launch {
            try {
                auditLogger.logUserAction("Mensagem recebida: ${message.take(100)}...")
                
                // Processar a mensagem através do orquestrador
                val response = agentOrchestrator.processUserMessage(message, conversationId)
                
                // A resposta será salva automaticamente pelo orquestrador
                
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar mensagem", e)
                auditLogger.logError(
                    action = "Processar mensagem",
                    error = e.message ?: "Erro desconhecido"
                )
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW // Baixa importância para não incomodar
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, Class.forName("com.agente.autonomo.ui.activities.MainActivity")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AgenteAutonomoService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Ícone padrão
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_action_stop), stopIntent)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
