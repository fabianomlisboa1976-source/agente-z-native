package com.agente.autonomo.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.agente.autonomo.data.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receiver que inicia o serviço automaticamente após o boot do dispositivo
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_REBOOT ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Log.d(TAG, "Boot completo detectado")
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val database = AppDatabase.getDatabase(context)
                    val settings = database.settingsDao().getSettingsSync()
                    
                    if (settings?.autoStart == true && settings.serviceEnabled) {
                        Log.d(TAG, "Auto-start habilitado, iniciando serviço...")
                        startAgentService(context)
                    } else {
                        Log.d(TAG, "Auto-start desabilitado ou serviço desativado")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao verificar configurações", e)
                }
            }
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
