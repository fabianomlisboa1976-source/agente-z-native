package com.agente.autonomo.ui.activities

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agente.autonomo.R
import com.agente.autonomo.api.model.ApiProvider
import com.agente.autonomo.api.model.AvailableModels
import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.data.entity.Settings
import com.agente.autonomo.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

/**
 * Activity de Configurações - Gerencia as configurações do aplicativo
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val TAG = "SettingsActivity"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var database: AppDatabase
    private var currentSettings: Settings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        database = AppDatabase.getDatabase(this)
        
        setupToolbar()
        setupSpinners()
        setupSwitches()
        setupButtons()
        loadSettings()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_title)
        }
    }

    private fun setupSpinners() {
        // Provedor de API
        val providers = listOf(
            ApiProvider.GROQ to getString(R.string.provider_groq),
            ApiProvider.OPENROUTER to getString(R.string.provider_openrouter)
        )
        
        val providerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            providers.map { it.second }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        
        binding.spinnerProvider.adapter = providerAdapter
        binding.spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateModelsSpinner(providers[position].first)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Modelos (será atualizado quando o provedor mudar)
        updateModelsSpinner(ApiProvider.GROQ)
    }

    private fun updateModelsSpinner(provider: ApiProvider) {
        val models = AvailableModels.getModelsByProvider(provider)
        
        val modelAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            models.map { it.name }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        
        binding.spinnerModel.adapter = modelAdapter
    }

    private fun setupSwitches() {
        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            // Será salvo quando o usuário clicar em Salvar
        }
        
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            // Será salvo quando o usuário clicar em Salvar
        }
        
        binding.switchAudit.setOnCheckedChangeListener { _, isChecked ->
            // Será salvo quando o usuário clicar em Salvar
        }
        
        binding.switchMultiAgent.setOnCheckedChangeListener { _, isChecked ->
            // Será salvo quando o usuário clicar em Salvar
        }
        
        binding.switchCrossAudit.setOnCheckedChangeListener { _, isChecked ->
            // Será salvo quando o usuário clicar em Salvar
        }
        
        binding.switchMemory.setOnCheckedChangeListener { _, isChecked ->
            // Será salvo quando o usuário clicar em Salvar
        }
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            saveSettings()
        }
        
        binding.btnTestConnection.setOnClickListener {
            testConnection()
        }
        
        binding.btnClearData.setOnClickListener {
            showClearDataDialog()
        }
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            database.settingsDao().getSettings().collect { settings ->
                settings?.let {
                    currentSettings = it
                    populateFields(it)
                }
            }
        }
    }

    private fun populateFields(settings: Settings) {
        // API Key (mascarada)
        binding.etApiKey.setText(settings.apiKey)
        
        // Provedor
        val providerIndex = when (settings.apiProvider) {
            "groq" -> 0
            "openrouter" -> 1
            else -> 0
        }
        binding.spinnerProvider.setSelection(providerIndex)
        
        // Modelo (será atualizado quando o provedor for selecionado)
        // TODO: Selecionar o modelo correto
        
        // Max tokens
        binding.etMaxTokens.setText(settings.maxTokens.toString())
        
        // Temperature
        binding.sliderTemperature.value = settings.temperature
        
        // Switches
        binding.switchAutoStart.isChecked = settings.autoStart
        binding.switchNotifications.isChecked = settings.notificationEnabled
        binding.switchAudit.isChecked = settings.auditEnabled
        binding.switchMultiAgent.isChecked = settings.multiAgentEnabled
        binding.switchCrossAudit.isChecked = settings.crossAuditEnabled
        binding.switchMemory.isChecked = settings.memoryEnabled
    }

    private fun saveSettings() {
        val apiKey = binding.etApiKey.text.toString().trim()
        
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Por favor, insira uma chave de API", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                val provider = when (binding.spinnerProvider.selectedItemPosition) {
                    0 -> "groq"
                    1 -> "openrouter"
                    else -> "groq"
                }
                
                val modelPosition = binding.spinnerModel.selectedItemPosition
                val models = AvailableModels.getModelsByProvider(
                    if (provider == "groq") ApiProvider.GROQ else ApiProvider.OPENROUTER
                )
                val model = if (modelPosition < models.size) models[modelPosition].id else settings.apiModel
                
                val newSettings = Settings(
                    id = 1,
                    apiProvider = provider,
                    apiKey = apiKey,
                    apiModel = model,
                    maxTokens = binding.etMaxTokens.text.toString().toIntOrNull() ?: 2048,
                    temperature = binding.sliderTemperature.value,
                    autoStart = binding.switchAutoStart.isChecked,
                    notificationEnabled = binding.switchNotifications.isChecked,
                    auditEnabled = binding.switchAudit.isChecked,
                    multiAgentEnabled = binding.switchMultiAgent.isChecked,
                    crossAuditEnabled = binding.switchCrossAudit.isChecked,
                    memoryEnabled = binding.switchMemory.isChecked,
                    createdAt = currentSettings?.createdAt ?: java.util.Date(),
                    updatedAt = java.util.Date()
                )
                
                database.settingsDao().insertSettings(newSettings)
                
                Toast.makeText(this@SettingsActivity, R.string.msg_settings_saved, Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                Toast.makeText(
                    this@SettingsActivity,
                    "Erro ao salvar: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun testConnection() {
        val apiKey = binding.etApiKey.text.toString().trim()
        
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Insira uma chave de API primeiro", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.btnTestConnection.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val provider = when (binding.spinnerProvider.selectedItemPosition) {
                    0 -> "groq"
                    1 -> "openrouter"
                    else -> "groq"
                }
                
                val modelPosition = binding.spinnerModel.selectedItemPosition
                val models = AvailableModels.getModelsByProvider(
                    if (provider == "groq") ApiProvider.GROQ else ApiProvider.OPENROUTER
                )
                val model = if (modelPosition < models.size) models[modelPosition].id else "llama-3.1-8b-instant"
                
                val tempSettings = Settings(
                    apiProvider = provider,
                    apiKey = apiKey,
                    apiModel = model
                )
                
                val client = com.agente.autonomo.api.LLMClient(tempSettings)
                val result = client.testConnection()
                
                runOnUiThread {
                    if (result.isSuccess) {
                        Toast.makeText(this@SettingsActivity, result.getOrDefault("Conectado!"), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(
                            this@SettingsActivity,
                            "Falha: ${result.exceptionOrNull()?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    binding.btnTestConnection.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun showClearDataDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Limpar Dados")
            .setMessage("Isso excluirá todas as mensagens, logs e memórias. Esta ação não pode ser desfeita.")
            .setPositiveButton("Limpar") { _, _ ->
                clearAllData()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun clearAllData() {
        lifecycleScope.launch {
            try {
                database.messageDao().deleteAllMessages()
                database.auditLogDao().deleteAllLogs()
                database.memoryDao().deleteAllMemories()
                database.taskDao().deleteAllTasks()
                
                Toast.makeText(this@SettingsActivity, "Dados limpos com sucesso!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Erro: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
