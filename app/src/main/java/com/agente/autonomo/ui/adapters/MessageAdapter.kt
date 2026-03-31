package com.agente.autonomo.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.agente.autonomo.R
import com.agente.autonomo.data.entity.Message
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Adapter para lista de mensagens no chat
 */
class MessageAdapter : ListAdapter<Message, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layoutId = when (viewType) {
            VIEW_TYPE_USER -> R.layout.item_message_user
            VIEW_TYPE_AGENT -> R.layout.item_message_agent
            VIEW_TYPE_SYSTEM -> R.layout.item_message_system
            else -> R.layout.item_message_user
        }
        
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).senderType) {
            Message.SenderType.USER -> VIEW_TYPE_USER
            Message.SenderType.AGENT -> VIEW_TYPE_AGENT
            Message.SenderType.SYSTEM -> VIEW_TYPE_SYSTEM
        }
    }

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        
        private val tvContent: TextView = itemView.findViewById(R.id.tvMessageContent)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvMessageTimestamp)
        private val tvAgentName: TextView? = itemView.findViewById(R.id.tvAgentName)
        
        fun bind(message: Message) {
            tvContent.text = message.content
            tvTimestamp.text = dateFormat.format(message.timestamp)
            
            if (message.senderType == Message.SenderType.AGENT) {
                tvAgentName?.text = message.agentName ?: "Agente"
                tvAgentName?.visibility = View.VISIBLE
            }
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        const val VIEW_TYPE_USER = 1
        const val VIEW_TYPE_AGENT = 2
        const val VIEW_TYPE_SYSTEM = 3
    }
}
