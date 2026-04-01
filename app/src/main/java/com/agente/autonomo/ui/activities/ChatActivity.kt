package com.agente.autonomo.ui.activities

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.agente.autonomo.R
import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.data.entity.Message
import com.agente.autonomo.databinding.ActivityChatBinding
import com.agente.autonomo.service.AgenteAutonomoService
import com.agente.autonomo.ui.adapters.MessageAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Activity de Chat - Interface de conversação com os agentes
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val TAG = "ChatActivity"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var database: AppDatabase
    private lateinit var messageAdapter: MessageAdapter
    
    private var conversationId: String = "default"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        database = AppDatabase.getDatabase(this)
        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: "default"
        
        setupToolbar()
        setupRecyclerView()
        setupInput()
        observeMessages()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.chat_title)
        }
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter()
        binding.recyclerViewMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener {
            sendMessage()
        }
        
        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }
    }

    private fun observeMessages() {
        lifecycleScope.launch {
            database.messageDao()
                .getMessagesByConversation(conversationId)
                .collectLatest { messages ->
                    messageAdapter.submitList(messages)
                    if (messages.isNotEmpty()) {
                        binding.recyclerViewMessages.scrollToPosition(messages.size - 1)
                    }
                    updateEmptyState(messages.isEmpty())
                }
        }
    }

    private fun sendMessage() {
        val messageText = binding.etMessage.text.toString().trim()
        if (messageText.isEmpty()) {
            Toast.makeText(this, R.string.msg_enter_message, Toast.LENGTH_SHORT).show()
            return
        }
        
        // Limpar input
        binding.etMessage.text?.clear()
        
        // Salvar mensagem do usuário
        lifecycleScope.launch {
            val userMessage = Message(
                senderType = Message.SenderType.USER,
                content = messageText,
                conversationId = conversationId
            )
            database.messageDao().insertMessage(userMessage)
            
            // Enviar para processamento
            if (AgenteAutonomoService.isRunning) {
                val intent = android.content.Intent(this@ChatActivity, AgenteAutonomoService::class.java).apply {
                    action = AgenteAutonomoService.ACTION_PROCESS_MESSAGE
                    putExtra(AgenteAutonomoService.EXTRA_MESSAGE, messageText)
                    putExtra(AgenteAutonomoService.EXTRA_CONVERSATION_ID, conversationId)
                }
                startService(intent)
                
                showLoading(true)
            } else {
                Toast.makeText(
                    this@ChatActivity,
                    R.string.error_service_not_running,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnSend.isEnabled = !show
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerViewMessages.visibility = if (isEmpty) View.GONE else View.VISIBLE
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
