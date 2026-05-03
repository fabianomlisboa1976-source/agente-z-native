package com.agente.autonomo.ui.activities

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agente.autonomo.R
import com.agente.autonomo.api.ProviderHealthRepository
import com.agente.autonomo.api.model.ApiProvider
import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.ui.adapters.ProviderHealthAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Displays a live dashboard of [ProviderHealth] records for every provider
 * in the failover chain.
 *
 * Features:
 * - Real-time [RecyclerView] backed by a Room [Flow] so updates from the
 *   background service are reflected immediately without polling.
 * - Per-provider **Reset** button that calls
 *   [ProviderHealthRepository.resetProvider] to clear failures and backoff.
 * - A global **Reset All** button for convenience.
 *
 * Navigation: accessible from [SettingsActivity] via an explicit Intent.
 */
class ProviderHealthActivity : AppCompatActivity() {

    companion object {
        const val TAG = "ProviderHealthActivity"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var btnResetAll: Button

    private lateinit var adapter: ProviderHealthAdapter
    private lateinit var healthRepository: ProviderHealthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provider_health)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Saúde dos Provedores"
        }

        val db = AppDatabase.getDatabase(this)
        healthRepository = ProviderHealthRepository(db.providerHealthDao())

        recyclerView = findViewById(R.id.recyclerProviderHealth)
        progressBar = findViewById(R.id.progressBarProviderHealth)
        emptyView = findViewById(R.id.tvEmptyProviderHealth)
        btnResetAll = findViewById(R.id.btnResetAllProviders)

        adapter = ProviderHealthAdapter { provider ->
            resetProvider(provider)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnResetAll.setOnClickListener { resetAllProviders() }

        observeProviderHealth()
        seedProviders()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------------------------------------------------------------------------
    // Data
    // ---------------------------------------------------------------------------

    private fun observeProviderHealth() {
        val db = AppDatabase.getDatabase(this)
        lifecycleScope.launch {
            db.providerHealthDao().getAllProviderHealth().collectLatest { records ->
                progressBar.visibility = View.GONE
                if (records.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    // Sort by canonical chain order
                    val ordered = ProviderHealthRepository.PROVIDER_CHAIN
                        .mapNotNull { p -> records.find { it.provider == p.name } }
                    adapter.submitList(ordered)
                }
            }
        }
    }

    private fun seedProviders() {
        lifecycleScope.launch {
            healthRepository.ensureAllProvidersSeeded()
        }
    }

    private fun resetProvider(provider: ApiProvider) {
        lifecycleScope.launch {
            healthRepository.resetProvider(provider)
            Toast.makeText(
                this@ProviderHealthActivity,
                "${provider.name} reiniciado",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun resetAllProviders() {
        lifecycleScope.launch {
            ProviderHealthRepository.PROVIDER_CHAIN.forEach { provider ->
                healthRepository.resetProvider(provider)
            }
            Toast.makeText(
                this@ProviderHealthActivity,
                "Todos os provedores reiniciados",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
