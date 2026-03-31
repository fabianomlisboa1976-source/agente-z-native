package com.agente.autonomo.ui.activities

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.agente.autonomo.R
import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.data.entity.AuditLog
import com.agente.autonomo.databinding.ActivityAuditBinding
import com.agente.autonomo.ui.adapters.AuditLogAdapter
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Activity de Auditoria - Visualiza logs do sistema
 */
class AuditActivity : AppCompatActivity() {

    companion object {
        const val TAG = "AuditActivity"
    }

    private lateinit var binding: ActivityAuditBinding
    private lateinit var database: AppDatabase
    private lateinit var auditAdapter: AuditLogAdapter
    
    private var currentFilter: FilterType = FilterType.ALL

    enum class FilterType {
        ALL, TODAY, WEEK, MONTH, ERRORS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        database = AppDatabase.getDatabase(this)
        
        setupToolbar()
        setupRecyclerView()
        setupFilterChips()
        loadLogs()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.audit_title)
        }
    }

    private fun setupRecyclerView() {
        auditAdapter = AuditLogAdapter { log ->
            showLogDetails(log)
        }
        
        binding.recyclerViewAudit.apply {
            layoutManager = LinearLayoutManager(this@AuditActivity)
            adapter = auditAdapter
        }
    }

    private fun setupFilterChips() {
        binding.chipAll.setOnClickListener { 
            currentFilter = FilterType.ALL
            loadLogs()
        }
        
        binding.chipToday.setOnClickListener { 
            currentFilter = FilterType.TODAY
            loadLogs()
        }
        
        binding.chipWeek.setOnClickListener { 
            currentFilter = FilterType.WEEK
            loadLogs()
        }
        
        binding.chipMonth.setOnClickListener { 
            currentFilter = FilterType.MONTH
            loadLogs()
        }
        
        binding.chipErrors.setOnClickListener { 
            currentFilter = FilterType.ERRORS
            loadLogs()
        }
    }

    private fun loadLogs() {
        lifecycleScope.launch {
            try {
                val logs = when (currentFilter) {
                    FilterType.ALL -> {
                        database.auditLogDao().getRecentLogs(1000)
                    }
                    FilterType.TODAY -> {
                        val calendar = Calendar.getInstance()
                        calendar.set(Calendar.HOUR_OF_DAY, 0)
                        calendar.set(Calendar.MINUTE, 0)
                        calendar.set(Calendar.SECOND, 0)
                        database.auditLogDao().getLogsSince(calendar.time)
                    }
                    FilterType.WEEK -> {
                        val calendar = Calendar.getInstance()
                        calendar.add(Calendar.DAY_OF_YEAR, -7)
                        database.auditLogDao().getLogsSince(calendar.time)
                    }
                    FilterType.MONTH -> {
                        val calendar = Calendar.getInstance()
                        calendar.add(Calendar.MONTH, -1)
                        database.auditLogDao().getLogsSince(calendar.time)
                    }
                    FilterType.ERRORS -> {
                        database.auditLogDao().getLogsByStatus(AuditLog.Status.ERROR)
                            .let { flow ->
                                var result: List<AuditLog> = emptyList()
                                flow.collect { result = it }
                                result
                            }
                    }
                }
                
                auditAdapter.submitList(logs)
                updateEmptyState(logs.isEmpty())
                
            } catch (e: Exception) {
                Toast.makeText(this@AuditActivity, "Erro ao carregar logs: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerViewAudit.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showLogDetails(log: AuditLog) {
        AlertDialog.Builder(this)
            .setTitle("${log.type.name} - ${log.status.name}")
            .setMessage("""
                Ação: ${log.action}
                Agente: ${log.agentName ?: "N/A"}
                Data: ${log.timestamp}
                Duração: ${log.durationMs ?: "N/A"}ms
                
                Detalhes:
                ${log.details ?: "Nenhum"}
                
                ${if (log.errorMessage != null) "Erro: ${log.errorMessage}" else ""}
            """.trimIndent())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun clearAllLogs() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_confirm_title)
            .setMessage(R.string.dialog_confirm_clear_logs)
            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                lifecycleScope.launch {
                    try {
                        database.auditLogDao().deleteAllLogs()
                        Toast.makeText(this@AuditActivity, "Logs limpos", Toast.LENGTH_SHORT).show()
                        loadLogs()
                    } catch (e: Exception) {
                        Toast.makeText(this@AuditActivity, "Erro: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    private fun exportLogs() {
        lifecycleScope.launch {
            try {
                val logs = database.auditLogDao().getRecentLogs(10000)
                
                // Criar JSON dos logs
                val gson = com.google.gson.GsonBuilder()
                    .setPrettyPrinting()
                    .create()
                val json = gson.toJson(logs)
                
                // Compartilhar
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "application/json"
                    putExtra(Intent.EXTRA_TEXT, json)
                    putExtra(Intent.EXTRA_SUBJECT, "Logs do Agente Autônomo")
                }
                startActivity(Intent.createChooser(shareIntent, "Exportar logs"))
                
            } catch (e: Exception) {
                Toast.makeText(this@AuditActivity, "Erro ao exportar: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_audit, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_clear -> {
                clearAllLogs()
                true
            }
            R.id.action_export -> {
                exportLogs()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
