package com.agente.autonomo.ui.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.agente.autonomo.R
import com.agente.autonomo.api.ProviderHealthRepository
import com.agente.autonomo.api.model.ApiProvider
import com.agente.autonomo.data.entity.ProviderHealth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [ListAdapter] that renders one card per [ProviderHealth] row in
 * [ProviderHealthActivity].
 *
 * Each card shows:
 * - Provider name and a colour-coded status badge (Healthy / Degraded /
 *   Unhealthy)
 * - Consecutive failure count and last failure reason
 * - Backoff expiry time if currently backing off
 * - Rolling average latency and total requests
 * - A **Reset** button that invokes [onResetClick]
 */
class ProviderHealthAdapter(
    private val onResetClick: (ApiProvider) -> Unit
) : ListAdapter<ProviderHealth, ProviderHealthAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ProviderHealth>() {
            override fun areItemsTheSame(a: ProviderHealth, b: ProviderHealth) =
                a.provider == b.provider
            override fun areContentsTheSame(a: ProviderHealth, b: ProviderHealth) = a == b
        }

        private val DATE_FMT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_provider_health, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvProviderName: TextView = view.findViewById(R.id.tvProviderName)
        private val tvStatus: TextView = view.findViewById(R.id.tvProviderStatus)
        private val tvFailures: TextView = view.findViewById(R.id.tvProviderFailures)
        private val tvLastFailure: TextView = view.findViewById(R.id.tvProviderLastFailure)
        private val tvBackoff: TextView = view.findViewById(R.id.tvProviderBackoff)
        private val tvLatency: TextView = view.findViewById(R.id.tvProviderLatency)
        private val tvTotalRequests: TextView = view.findViewById(R.id.tvProviderTotalRequests)
        private val btnReset: Button = view.findViewById(R.id.btnResetProvider)

        fun bind(health: ProviderHealth) {
            val provider = runCatching { ApiProvider.valueOf(health.provider) }.getOrNull()

            tvProviderName.text = health.provider

            // Status badge
            val now = System.currentTimeMillis()
            val inBackoff = health.backoffUntil > now
            when {
                health.isHealthy && !inBackoff -> {
                    tvStatus.text = "✅ Saudável"
                    tvStatus.setTextColor(Color.parseColor("#2E7D32"))
                }
                inBackoff -> {
                    val remaining = ((health.backoffUntil - now) / 1000).coerceAtLeast(0)
                    tvStatus.text = "⏳ Backoff (${remaining}s)"
                    tvStatus.setTextColor(Color.parseColor("#F57F17"))
                }
                else -> {
                    tvStatus.text = "❌ Não saudável"
                    tvStatus.setTextColor(Color.parseColor("#C62828"))
                }
            }

            tvFailures.text = "Falhas consecutivas: ${health.consecutiveFailures}" +
                    " / ${ProviderHealthRepository.MAX_CONSECUTIVE_FAILURES}"

            tvLastFailure.text = if (health.lastFailureReason != null) {
                "Último erro: ${health.lastFailureReason}"
            } else {
                "Nenhuma falha registrada"
            }

            tvBackoff.visibility = if (inBackoff) {
                tvBackoff.text = "Backoff até: ${DATE_FMT.format(Date(health.backoffUntil))}"
                View.VISIBLE
            } else View.GONE

            tvLatency.text = "Latência média: ${health.averageLatencyMs}ms"
            tvTotalRequests.text =
                "Requisições: ${health.totalRequests}  |  Falhas: ${health.totalFailures}"

            btnReset.setOnClickListener {
                provider?.let { onResetClick(it) }
            }
            btnReset.isEnabled = provider != null
        }
    }
}
