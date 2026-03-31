package com.agente.autonomo.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.agente.autonomo.data.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receiver que detecta mudanças na conectividade de rede
 */
class NetworkReceiver : BroadcastReceiver() {
    
    companion object {
        const val TAG = "NetworkReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION) {
            val isConnected = isNetworkAvailable(context)
            Log.d(TAG, "Estado da rede alterado: ${if (isConnected) "Conectado" else "Desconectado"}")
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val database = AppDatabase.getDatabase(context)
                    
                    if (isConnected) {
                        // Rede disponível - pode sincronizar dados pendentes
                        syncPendingData(database)
                        
                        // Se o serviço não estiver rodando, tentar iniciar
                        if (!AgenteAutonomoService.isRunning) {
                            val settings = database.settingsDao().getSettingsSync()
                            if (settings?.serviceEnabled == true) {
                                startAgentService(context)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao processar mudança de rede", e)
                }
            }
        }
    }
    
    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.isConnected == true
        }
    }
    
    private suspend fun syncPendingData(database: AppDatabase) {
        try {
            // Sincronizar mensagens não sincronizadas
            val unsyncedMessages = database.messageDao().getUnsyncedMessages()
            if (unsyncedMessages.isNotEmpty()) {
                Log.d(TAG, "Sincronizando ${unsyncedMessages.size} mensagens pendentes")
                // Aqui você implementaria a sincronização com servidor remoto se necessário
                for (message in unsyncedMessages) {
                    database.messageDao().markAsSynced(message.id)
                }
            }
            
            // Sincronizar logs não sincronizados
            val unsyncedLogs = database.auditLogDao().getUnsyncedLogs()
            if (unsyncedLogs.isNotEmpty()) {
                Log.d(TAG, "Sincronizando ${unsyncedLogs.size} logs pendentes")
                for (log in unsyncedLogs) {
                    database.auditLogDao().markAsSynced(log.id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao sincronizar dados", e)
        }
    }
    
    private fun startAgentService(context: Context) {
        val serviceIntent = Intent(context, AgenteAutonomoService::class.java).apply {
            action = AgenteAutonomoService.ACTION_START
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
