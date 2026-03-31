package com.agente.autonomo.ui.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.agente.autonomo.R
import com.agente.autonomo.data.entity.AuditLog
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Adapter para lista de logs de auditoria
 */
class AuditLogAdapter(
    private val onLogClick: (AuditLog) -> Unit
) : ListAdapter<AuditLog, AuditLogAdapter.LogViewHolder>(LogDiffCallback()) {

    private val dateFormat = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audit_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        
        private val tvType: TextView = itemView.findViewById(R.id.tvLogType)
        private val tvAction: TextView = itemView.findViewById(R.id.tvLogAction)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvLogTimestamp)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvLogStatus)
        private val tvAgent: TextView = itemView.findViewById(R.id.tvLogAgent)
        
        fun bind(log: AuditLog) {
            tvType.text = log.type.name
            tvAction.text = log.action
            tvTimestamp.text = dateFormat.format(log.timestamp)
            tvStatus.text = log.status.name
            tvAgent.text = log.agentName ?: "Sistema"
            
            // Cor do status
            val statusColor = when (log.status) {
                AuditLog.Status.SUCCESS -> Color.parseColor("#22C55E")
                AuditLog.Status.WARNING -> Color.parseColor("#F59E0B")
                AuditLog.Status.ERROR -> Color.parseColor("#EF4444")
                AuditLog.Status.PENDING -> Color.parseColor("#3B82F6")
            }
            tvStatus.setTextColor(statusColor)
            
            // Cor do tipo
            val typeColor = when (log.type) {
                AuditLog.AuditType.REQUEST -> Color.parseColor("#3B82F6")
                AuditLog.AuditType.RESPONSE -> Color.parseColor("#10B981")
                AuditLog.AuditType.ACTION -> Color.parseColor("#8B5CF6")
                AuditLog.AuditType.ERROR -> Color.parseColor("#EF4444")
                AuditLog.AuditType.SYSTEM -> Color.parseColor("#6B7280")
                AuditLog.AuditType.SECURITY -> Color.parseColor("#F59E0B")
                AuditLog.AuditType.USER_ACTION -> Color.parseColor("#06B6D4")
                AuditLog.AuditType.AGENT_DECISION -> Color.parseColor("#EC4899")
            }
            tvType.setTextColor(typeColor)
            
            itemView.setOnClickListener {
                onLogClick(log)
            }
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<AuditLog>() {
        override fun areItemsTheSame(oldItem: AuditLog, newItem: AuditLog): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AuditLog, newItem: AuditLog): Boolean {
            return oldItem == newItem
        }
    }
}
