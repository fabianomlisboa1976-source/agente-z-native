package com.agente.autonomo.ui.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.agente.autonomo.R
import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.databinding.ActivityMainBinding
import com.agente.autonomo.service.AgenteAutonomoService
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch

/**
 * Activity Principal - Tela inicial do aplicativo
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
        const val PERMISSION_REQUEST_CODE = 1001
        const val BATTERY_OPTIMIZATION_REQUEST = 1002
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: AppDatabase

    private val requiredPermissions = mutableListOf<String>().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(Manifest.permission.FOREGROUND_SERVICE)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        database = AppDatabase.getDatabase(this)
        
        setupUI()
        checkPermissions()
        checkBatteryOptimization()
        checkServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun setupUI() {
        // Configurar toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        
        // Botão para abrir chat
        binding.btnOpenChat.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        
        // Botão para gerenciar agentes
        binding.btnManageAgents.setOnClickListener {
            startActivity(Intent(this, AgentsActivity::class.java))
        }
        
        // Botão para configurações
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        // Botão para auditoria
        binding.btnAudit.setOnClickListener {
            startActivity(Intent(this, AuditActivity::class.java))
        }
        
        // Botão para iniciar/parar serviço
        binding.btnToggleService.setOnClickListener {
            toggleService()
        }
        
        // Status do serviço
        updateServiceStatus()
    }

    private fun checkPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryOptimizationDialog()
            }
        }
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Otimização de Bateria")
            .setMessage("Para garantir que o agente funcione 24/7, é necessário desabilitar a otimização de bateria para este aplicativo.")
            .setPositiveButton("Configurar") { _, _ ->
                requestBatteryOptimizationExemption()
            }
            .setNegativeButton("Depois") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST)
        }
    }

    private fun checkServiceStatus() {
        lifecycleScope.launch {
            val settings = database.settingsDao().getSettingsSync()
            if (settings?.autoStart == true && !AgenteAutonomoService.isRunning) {
                startAgentService()
            }
        }
    }

    private fun toggleService() {
        if (AgenteAutonomoService.isRunning) {
            stopAgentService()
        } else {
            startAgentService()
        }
    }

    private fun startAgentService() {
        val intent = Intent(this, AgenteAutonomoService::class.java).apply {
            action = AgenteAutonomoService.ACTION_START
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        Toast.makeText(this, R.string.msg_service_started, Toast.LENGTH_SHORT).show()
        updateServiceStatus()
    }

    private fun stopAgentService() {
        val intent = Intent(this, AgenteAutonomoService::class.java).apply {
            action = AgenteAutonomoService.ACTION_STOP
        }
        startService(intent)
        
        Toast.makeText(this, R.string.msg_service_stopped, Toast.LENGTH_SHORT).show()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val isRunning = AgenteAutonomoService.isRunning
        binding.tvServiceStatus.text = if (isRunning) {
            getString(R.string.status_online)
        } else {
            getString(R.string.status_offline)
        }
        
        binding.tvServiceStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (isRunning) R.color.success else R.color.error
            )
        )
        
        binding.btnToggleService.text = if (isRunning) {
            getString(R.string.btn_stop_service)
        } else {
            getString(R.string.btn_start_service)
        }
        
        binding.indicatorServiceStatus.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (isRunning) R.color.success else R.color.error
            )
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val deniedPermissions = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first }
            
            if (deniedPermissions.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Algumas permissões foram negadas. O funcionamento pode ser limitado.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == BATTERY_OPTIMIZATION_REQUEST) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    Toast.makeText(this, "Otimização de bateria desabilitada!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
