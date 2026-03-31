package com.agente.autonomo.ui.activities

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
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
 * Activity de Chat - Interface de conversação com o Agente Z
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
    private var isServiceBound = false
    private var isWaitingResponse = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            isServiceBound = true
            // Configurar callback para quando a resposta estiver pronta
            AgenteAutonomoService.onResponseReady = { response ->
                runOnUiThread {
                    showLoading(false)
                    isWaitingResponse = false
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            AgenteAutonomoService.onResponseReady = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        database = AppDatabase.getDatabase(this)
        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: "default"
        
        setupToolbar()
        setupRecyclerView()
        setupInput()
        setupQuickActions()
        observeMessages()
    }

    override fun onStart() {
        super.onStart()
        // Bind ao service
        Intent(this, AgenteAutonomoService::class.java).also { intent ->
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
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
    
    private fun setupQuickActions() {
        // Ações rápidas para facilitar o uso
        binding.btnQuick1.setOnClickListener {
            binding.etMessage.setText("O que você lembra sobre mim?")
            sendMessage()
        }
        
        binding.btnQuick2.setOnClickListener {
            binding.etMessage.setText("Quais são minhas tarefas pendentes?")
            sendMessage()
        }
        
        binding.btnQuick3.setOnClickListener {
            binding.etMessage.setText("Me ajude a organizar minha semana")
            sendMessage()
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
        if (isWaitingResponse) {
            Toast.makeText(this, "Aguardando resposta...", Toast.LENGTH_SHORT).show()
            return
        }
        
        val messageText = binding.etMessage.text.toString().trim()
        if (messageText.isEmpty()) {
            Toast.makeText(this, R.string.msg_enter_message, Toast.LENGTH_SHORT).show()
            return
        }
        
        // Limpar input
        binding.etMessage.text?.clear()
        
        // Verificar se o serviço está rodando
        if (!AgenteAutonomoService.isRunning) {
            // Iniciar serviço
            val serviceIntent = Intent(this, AgenteAutonomoService::class.java).apply {
                action = AgenteAutonomoService.ACTION_START
            }
            startService(serviceIntent)
            
            Toast.makeText(this, "Iniciando agente...", Toast.LENGTH_SHORT).show()
        }
        
        // Enviar mensagem para o serviço processar
        val intent = Intent(this, AgenteAutonomoService::class.java).apply {
            action = AgenteAutonomoService.ACTION_PROCESS_MESSAGE
            putExtra(AgenteAutonomoService.EXTRA_MESSAGE, messageText)
            putExtra(AgenteAutonomoService.EXTRA_CONVERSATION_ID, conversationId)
        }
        startService(intent)
        
        showLoading(true)
        isWaitingResponse = true
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnSend.isEnabled = !show
        binding.etMessage.isEnabled = !show
        
        // Desabilitar ações rápidas durante carregamento
        binding.btnQuick1.isEnabled = !show
        binding.btnQuick2.isEnabled = !show
        binding.btnQuick3.isEnabled = !show
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerViewMessages.visibility = if (isEmpty) View.GONE else View.VISIBLE
        
        // Mostrar ações rápidas quando vazio
        binding.quickActionsContainer.visibility = if (isEmpty) View.VISIBLE else View.GONE
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
