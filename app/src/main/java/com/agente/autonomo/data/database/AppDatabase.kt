package com.agente.autonomo.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agente.autonomo.data.dao.*
import com.agente.autonomo.data.entity.*
import com.agente.autonomo.utils.DateConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        Message::class,
        Agent::class,
        AuditLog::class,
        Settings::class,
        Task::class,
        Memory::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(DateConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun agentDao(): AgentDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun settingsDao(): SettingsDao
    abstract fun taskDao(): TaskDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agente_autonomo_database"
                )
                    .addCallback(DatabaseCallback())
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class DatabaseCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    populateDatabase(database)
                }
            }
        }

        private suspend fun populateDatabase(database: AppDatabase) {
            val settings = Settings()
            database.settingsDao().insertSettings(settings)

            val defaultAgents = createDefaultAgents()
            database.agentDao().insertAgents(defaultAgents)
        }

        private fun createDefaultAgents(): List<Agent> {
            return listOf(
                Agent(
                    id = "coordinator",
                    name = "Coordenador",
                    description = "Coordena a execução de tarefas entre os agentes",
                    type = "COORDINATOR",
                    systemPrompt = "Você é o Coordenador, o agente principal do sistema. Seu papel é analisar solicitações do usuário e coordenar a execução.",
                    priority = 100,
                    capabilities = "[\"coordenacao\", \"orquestracao\", \"analise\", \"decisao\"]",
                    color = "#8B5CF6"
                ),
                Agent(
                    id = "planner",
                    name = "Planejador",
                    description = "Cria planos e organiza tarefas",
                    type = "PLANNER",
                    systemPrompt = "Você é o Planejador, especialista em organização e estratégia.",
                    priority = 90,
                    capabilities = "[\"planejamento\", \"organizacao\", \"priorizacao\"]",
                    color = "#3B82F6"
                ),
                Agent(
                    id = "researcher",
                    name = "Pesquisador",
                    description = "Busca e analisa informações",
                    type = "RESEARCHER",
                    systemPrompt = "Você é o Pesquisador, especialista em buscar e analisar informações.",
                    priority = 80,
                    capabilities = "[\"pesquisa\", \"analise\", \"sintese\"]",
                    color = "#10B981"
                ),
                Agent(
                    id = "executor",
                    name = "Executor",
                    description = "Executa tarefas práticas",
                    type = "EXECUTOR",
                    systemPrompt = "Você é o Executor, especialista em execução prática de tarefas.",
                    priority = 70,
                    capabilities = "[\"execucao\", \"automacao\", \"implementacao\"]",
                    color = "#F59E0B"
                ),
                Agent(
                    id = "auditor",
                    name = "Auditor",
                    description = "Verifica qualidade e consistência",
                    type = "AUDITOR",
                    systemPrompt = "Você é o Auditor, especialista em verificação e garantia de qualidade.",
                    priority = 60,
                    capabilities = "[\"auditoria\", \"verificacao\", \"validacao\"]",
                    color = "#EF4444"
                ),
                Agent(
                    id = "memory",
                    name = "Memória",
                    description = "Gerencia memória e contexto",
                    type = "MEMORY",
                    systemPrompt = "Você é o agente de Memória, especialista em gerenciar informações contextuais.",
                    priority = 50,
                    capabilities = "[\"memoria\", \"contexto\", \"historico\"]",
                    color = "#EC4899"
                ),
                Agent(
                    id = "communication",
                    name = "Comunicação",
                    description = "Gerencia comunicações externas",
                    type = "COMMUNICATION",
                    systemPrompt = "Você é o agente de Comunicação, especialista em interações externas.",
                    priority = 40,
                    capabilities = "[\"comunicacao\", \"notificacao\", \"formatacao\"]",
                    color = "#06B6D4"
                )
            )
        }
    }
}
