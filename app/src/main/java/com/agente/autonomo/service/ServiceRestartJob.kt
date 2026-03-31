package com.agente.autonomo.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * JobService para reiniciar o serviço principal (Android 5.0+)
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class ServiceRestartJob : JobService() {
    
    companion object {
        const val TAG = "ServiceRestartJob"
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Job de reinício iniciado")
        
        if (!AgenteAutonomoService.isRunning) {
            val serviceIntent = Intent(this, AgenteAutonomoService::class.java).apply {
                action = AgenteAutonomoService.ACTION_START
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Log.d(TAG, "Serviço reiniciado via Job")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao reiniciar serviço via Job", e)
            }
        }
        
        return false // Job completo
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Job de reinício interrompido")
        return false // Não reagendar
    }
}
