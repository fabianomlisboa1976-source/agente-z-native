package com.agente.autonomo.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.agente.autonomo.R
import com.agente.autonomo.api.MemoryApiClient
import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.data.entity.Message
import com.agente.autonomo.data.entity.Task
import kotlinx.coroutines.*
import java.util.UUID

/**
 * Foreground Service principal que mantém o agente rodando
 * e se comunica com o servidor de memória
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
        
        const val PREFS_NAME = "agente_prefs"
        const val PREF_USER_ID = "user_id"
        const val PREF_USER_EMAIL = "user_email"
        const val PREF_API_URL = "api_url"
        
        @Volatile
        var isRunning = false
            private set
        
        var onResponseReady: ((String) -> Unit)? = null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var database: AppDatabase
    private lateinit var prefs: SharedPreferences
    private var memoryClient: MemoryApiClient? = null
    private var userId: String? = null
    
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Serviço criado")
        
        database = AppDatabase.getDatabase(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        userId = prefs.getString(PREF_USER_ID, null)
        
        val apiUrl = prefs.getString(PREF_API_URL, MemoryApiClient.DEFAULT_BASE_URL) ?: MemoryApiClient.DEFAULT_BASE_URL
        memoryClient = MemoryApiClient(apiUrl)
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> startService()
            ACTION_STOP -> stopService()
            ACTION_PROCESS_MESSAGE -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE)
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: "default"
                if (message != null) {
                    processMessage(message, conversationId)
                }
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Serviço destruído")
        
        isRunning = false
        serviceScope.cancel()
        
        try {
            val broadcastIntent = Intent(this, RestartService::class.java)
            sendBroadcast(broadcastIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar broadcast de restart", e)
        }
    }

    private fun startService() {
        if (isRunning) {
            Log.d(TAG, "Serviço já está rodando")
            return
        }
        
        Log.d(TAG, "Iniciando serviço...")
        
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        isRunning = true
        
        serviceScope.launch {
            ensureUserId()
        }
        
        startHeartbeat()
        
        Log.d(TAG, "Serviço iniciado com sucesso")
    }

    private fun stopService() {
        Log.d(TAG, "Parando serviço...")
        
        isRunning = false
        heartbeatJob?.cancel()
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (isActive && isRunning) {
                try {
                    Log.d(TAG, "Heartbeat: Serviço ativo, userId=$userId")
                    updateNotification()
                    delay(300000)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no heartbeat", e)
                }
            }
        }
    }

    private suspend fun ensureUserId() {
        if (userId != null) return
        
        userId = MemoryApiClient.getCachedUserId()
        if (userId != null) return
        
        userId = prefs.getString(PREF_USER_ID, null)
        if (userId != null) return
        
        val deviceId = UUID.randomUUID().toString().substring(0, 8)
        val email = "z_user_$deviceId@z-app.local"
        
        val result = memoryClient?.getOrCreateUser(email, "Z User")
        if (result?.isSuccess == true) {
            val user = result.getOrNull()?.data
            if (user != null) {
                userId = user.id
                prefs.edit()
                    .putString(PREF_USER_ID, user.id)
                    .putString(PREF_USER_EMAIL, user.email)
                    .apply()
                Log.d(TAG, "Usuário criado: ${user.id}")
            }
        } else {
            Log.e(TAG, "Falha ao criar usuário: ${result?.exceptionOrNull()?.message}")
        }
    }

    private fun processMessage(message: String, conversationId: String) {
        serviceScope.launch {
            try {
                Log.d(TAG, "Processando mensagem: ${message.take(50)}...")
                
                ensureUserId()
                
                val currentUserId = userId
                if (currentUserId == null) {
                    Log.e(TAG, "Sem userId, não é possível processar mensagem")
                    return@launch
                }
                
                val userMessage = Message(
                    senderType = Message.SenderType.USER,
                    content = message,
                    conversationId = conversationId
                )
                database.messageDao().insertMessage(userMessage)
                
                val result = memoryClient?.sendChatMessage(
                    currentUserId,
                    message,
                    if (conversationId == "default") null else conversationId
                )
                
                if (result?.isSuccess == true) {
                    val response = result.getOrNull()
                    if (response != null) {
                        val agentMessage = Message(
                            senderType = Message.SenderType.AGENT,
                            content = response.response,
                            conversationId = conversationId,
                            agentId = "z-agent",
                            agentName = "Z"
                        )
                        database.messageDao().insertMessage(agentMessage)
                        
                        Log.d(TAG, "Resposta recebida: ${response.response.take(100)}...")
                        onResponseReady?.invoke(response.response)
                    }
                } else {
                    Log.e(TAG, "Erro ao processar mensagem: ${result?.exceptionOrNull()?.message}")
                    
                    val errorMessage = Message(
                        senderType = Message.SenderType.SYSTEM,
                        content = "Erro: ${result?.exceptionOrNull()?.message ?: 'Falha na conexão'}",
                        conversationId = conversationId
                    )
                    database.messageDao().insertMessage(errorMessage)
                    
                    onResponseReady?.invoke("Erro: ${result?.exceptionOrNull()?.message}")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Exceção ao processar mensagem", e)
                onResponseReady?.invoke("Erro interno: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
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
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
