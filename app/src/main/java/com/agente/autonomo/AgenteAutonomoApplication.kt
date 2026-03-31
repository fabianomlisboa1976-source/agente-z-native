package com.agente.autonomo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.agente.autonomo.data.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Classe Application principal
 */
class AgenteAutonomoApplication : Application() {

    companion object {
        const val CHANNEL_ID = "agente_autonomo_channel"
    }

    override fun onCreate() {
        super.onCreate()
        
        createNotificationChannel()
        initializeDatabase()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initializeDatabase() {
        CoroutineScope(Dispatchers.IO).launch {
            // Inicializa o banco de dados (cria tabelas e dados padrão)
            AppDatabase.getDatabase(this@AgenteAutonomoApplication)
        }
    }
}
