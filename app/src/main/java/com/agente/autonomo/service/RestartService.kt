package com.agente.autonomo.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Receiver que reinicia o serviço principal se ele for morto
 */
class RestartService : BroadcastReceiver() {
    
    companion object {
        const val TAG = "RestartService"
        const val RESTART_DELAY_MS = 5000L // 5 segundos
        const val JOB_ID = 1001
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Broadcast recebido: ${intent.action}")
        
        // Agendar reinício após delay
        Handler(Looper.getMainLooper()).postDelayed({
            if (!AgenteAutonomoService.isRunning) {
                Log.d(TAG, "Reiniciando serviço...")
                restartService(context)
            } else {
                Log.d(TAG, "Serviço já está rodando")
            }
        }, RESTART_DELAY_MS)
    }
    
    private fun restartService(context: Context) {
        val serviceIntent = Intent(context, AgenteAutonomoService::class.java).apply {
            action = AgenteAutonomoService.ACTION_START
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "Serviço reiniciado com sucesso")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao reiniciar serviço, tentando JobScheduler", e)
            scheduleJobRestart(context)
        }
    }
    
    private fun scheduleJobRestart(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val componentName = ComponentName(context, ServiceRestartJob::class.java)
            val jobInfo = JobInfo.Builder(JOB_ID, componentName)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setMinimumLatency(RESTART_DELAY_MS)
                .build()
            
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobScheduler.schedule(jobInfo)
            Log.d(TAG, "Job de reinício agendado")
        }
    }
}
