package com.agente.autonomo.ui.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.agente.autonomo.R
import com.agente.autonomo.data.entity.Agent

/**
 * Adapter para lista de agentes
 */
class AgentAdapter(
    private val onAgentClick: (Agent) -> Unit,
    private val onAgentToggle: (Agent, Boolean) -> Unit,
    private val onAgentEdit: (Agent) -> Unit,
    private val onAgentDelete: (Agent) -> Unit
) : ListAdapter<Agent, AgentAdapter.AgentViewHolder>(AgentDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AgentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_agent, parent, false)
        return AgentViewHolder(view)
    }

    override fun onBindViewHolder(holder: AgentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AgentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        
        private val tvName: TextView = itemView.findViewById(R.id.tvAgentName)
        private val tvDescription: TextView = itemView.findViewById(R.id.tvAgentDescription)
        private val tvType: TextView = itemView.findViewById(R.id.tvAgentType)
        private val switchActive: Switch = itemView.findViewById(R.id.switchAgentActive)
        private val btnEdit: ImageButton = itemView.findViewById(R.id.btnEditAgent)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteAgent)
        
        fun bind(agent: Agent) {
            tvName.text = agent.name
            tvDescription.text = agent.description
            tvType.text = agent.type.name
            
            try {
                val color = Color.parseColor(agent.color)
                tvName.setTextColor(color)
            } catch (e: Exception) {
                // Usa cor padrão se parsing falhar
            }
            
            switchActive.setOnCheckedChangeListener(null)
            switchActive.isChecked = agent.isActive
            switchActive.setOnCheckedChangeListener { _, isChecked ->
                onAgentToggle(agent, isChecked)
            }
            
            itemView.setOnClickListener {
                onAgentClick(agent)
            }
            
            btnEdit.setOnClickListener {
                onAgentEdit(agent)
            }
            
            btnDelete.setOnClickListener {
                onAgentDelete(agent)
            }
        }
    }

    class AgentDiffCallback : DiffUtil.ItemCallback<Agent>() {
        override fun areItemsTheSame(oldItem: Agent, newItem: Agent): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Agent, newItem: Agent): Boolean {
            return oldItem == newItem
        }
    }
}
