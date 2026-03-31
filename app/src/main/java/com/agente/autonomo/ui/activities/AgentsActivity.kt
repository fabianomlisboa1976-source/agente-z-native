package com.agente.autonomo.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.agente.autonomo.R
import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.data.entity.Agent
import com.agente.autonomo.databinding.ActivityAgentsBinding
import com.agente.autonomo.ui.adapters.AgentAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Activity de Agentes - Gerencia os agentes do sistema
 */
class AgentsActivity : AppCompatActivity() {

    companion object {
        const val TAG = "AgentsActivity"
    }

    private lateinit var binding: ActivityAgentsBinding
    private lateinit var database: AppDatabase
    private lateinit var agentAdapter: AgentAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        database = AppDatabase.getDatabase(this)
        
        setupToolbar()
        setupRecyclerView()
        setupFab()
        observeAgents()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.agents_title)
        }
    }

    private fun setupRecyclerView() {
        agentAdapter = AgentAdapter(
            onAgentClick = { agent ->
                showAgentDetails(agent)
            },
            onAgentToggle = { agent, isActive ->
                toggleAgent(agent, isActive)
            },
            onAgentEdit = { agent ->
                editAgent(agent)
            },
            onAgentDelete = { agent ->
                confirmDeleteAgent(agent)
            }
        )
        
        binding.recyclerViewAgents.apply {
            layoutManager = LinearLayoutManager(this@AgentsActivity)
            adapter = agentAdapter
        }
    }

    private fun setupFab() {
        binding.fabAddAgent.setOnClickListener {
            showCreateAgentDialog()
        }
    }

    private fun observeAgents() {
        lifecycleScope.launch {
            database.agentDao().getAllAgents()
                .collectLatest { agents ->
                    agentAdapter.submitList(agents)
                    updateEmptyState(agents.isEmpty())
                }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerViewAgents.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showAgentDetails(agent: Agent) {
        AlertDialog.Builder(this)
            .setTitle(agent.name)
            .setMessage("""
                Tipo: ${agent.type.name}
                Descrição: ${agent.description}
                Prioridade: ${agent.priority}
                Usos: ${agent.usageCount}
                
                Prompt:
                ${agent.systemPrompt.take(500)}${if (agent.systemPrompt.length > 500) "..." else ""}
            """.trimIndent())
            .setPositiveButton("OK", null)
            .setNeutralButton("Editar") { _, _ ->
                editAgent(agent)
            }
            .show()
    }

    private fun toggleAgent(agent: Agent, isActive: Boolean) {
        lifecycleScope.launch {
            try {
                database.agentDao().setAgentActive(agent.id, isActive)
                Toast.makeText(
                    this@AgentsActivity,
                    if (isActive) "Agente ativado" else "Agente desativado",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this@AgentsActivity, "Erro: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun editAgent(agent: Agent) {
        // TODO: Abrir tela de edição
        Toast.makeText(this, "Edição em desenvolvimento", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteAgent(agent: Agent) {
        if (agent.type == Agent.AgentType.COORDINATOR) {
            Toast.makeText(this, "Não é possível excluir o agente coordenador", Toast.LENGTH_SHORT).show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("Excluir Agente")
            .setMessage("Deseja realmente excluir o agente '${agent.name}'?")
            .setPositiveButton("Excluir") { _, _ ->
                deleteAgent(agent)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteAgent(agent: Agent) {
        lifecycleScope.launch {
            try {
                database.agentDao().deleteAgentById(agent.id)
                Toast.makeText(this@AgentsActivity, R.string.msg_agent_deleted, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@AgentsActivity, "Erro: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCreateAgentDialog() {
        // TODO: Implementar diálogo de criação de agente
        Toast.makeText(this, "Criação de agente em desenvolvimento", Toast.LENGTH_SHORT).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
