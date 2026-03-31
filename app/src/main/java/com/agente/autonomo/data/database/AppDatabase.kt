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
    exportSchema = true
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
            // Inserir configurações padrão
            val settings = Settings()
            database.settingsDao().insertSettings(settings)

            // Inserir agentes padrão
            val defaultAgents = createDefaultAgents()
            database.agentDao().insertAgents(defaultAgents)
        }

        private fun createDefaultAgents(): List<Agent> {
            return listOf(
                Agent(
                    id = "coordinator",
                    name = "Coordenador",
                    description = "Coordena a execução de tarefas entre os agentes",
                    type = Agent.AgentType.COORDINATOR,
                    systemPrompt = """Você é o Coordenador, o agente principal do sistema. 
Seu papel é analisar solicitações do usuário e coordenar a execução entre os agentes especializados.
Você deve:
1. Entender a intenção do usuário
2. Determinar qual(is) agente(s) deve(m) atuar
3. Orquestrar a execução em sequência ou paralelo
4. Consolidar resultados
5. Garantir a qualidade através de auditoria cruzada

Seja proativo, eficiente e mantenha o foco nos objetivos do usuário.""",
                    priority = 100,
                    capabilities = "[\"coordenacao\", \"orquestracao\", \"analise\", \"decisao\"]",
                    color = "#8B5CF6"
                ),
                Agent(
                    id = "planner",
                    name = "Planejador",
                    description = "Cria planos e organiza tarefas",
                    type = Agent.AgentType.PLANNER,
                    systemPrompt = """Você é o Planejador, especialista em organização e estratégia.
Seu papel é:
1. Quebrar objetivos grandes em tarefas menores
2. Criar cronogramas e deadlines
3. Priorizar atividades
4. Identificar dependências
5. Sugerir recursos necessários

Você pensa de forma estruturada e sempre considera prazos realistas.""",
                    priority = 90,
                    capabilities = "[\"planejamento\", \"organizacao\", \"priorizacao\", \"cronograma\"]",
                    color = "#3B82F6"
                ),
                Agent(
                    id = "researcher",
                    name = "Pesquisador",
                    description = "Busca e analisa informações",
                    type = Agent.AgentType.RESEARCHER,
                    systemPrompt = """Você é o Pesquisador, especialista em buscar e analisar informações.
Seu papel é:
1. Realizar pesquisas aprofundadas
2. Analisar dados e fontes
3. Sintetizar informações complexas
4. Identificar tendências e padrões
5. Verificar fatos e fontes

Você é meticuloso, analítico e sempre busca fontes confiáveis.""",
                    priority = 80,
                    capabilities = "[\"pesquisa\", \"analise\", \"sintese\", \"verificacao\"]",
                    color = "#10B981"
                ),
                Agent(
                    id = "executor",
                    name = "Executor",
                    description = "Executa tarefas práticas",
                    type = Agent.AgentType.EXECUTOR,
                    systemPrompt = """Você é o Executor, especialista em execução prática de tarefas.
Seu papel é:
1. Implementar soluções propostas
2. Executar operações no sistema
3. Gerenciar arquivos e dados
4. Automatizar processos repetitivos
5. Reportar progresso e resultados

Você é eficiente, confiável e focado em resultados concretos.""",
                    priority = 70,
                    capabilities = "[\"execucao\", \"automacao\", \"implementacao\", \"operacao\"]",
                    color = "#F59E0B"
                ),
                Agent(
                    id = "auditor",
                    name = "Auditor",
                    description = "Verifica qualidade e consistência",
                    type = Agent.AgentType.AUDITOR,
                    systemPrompt = """Você é o Auditor, especialista em verificação e garantia de qualidade.
Seu papel é:
1. Verificar a qualidade das respostas
2. Identificar inconsistências
3. Validar informações
4. Detectar erros e vulnerabilidades
5. Garantir conformidade com diretrizes

Você é crítico, detalhista e sempre busca a excelência.""",
                    priority = 60,
                    capabilities = "[\"auditoria\", \"verificacao\", \"validacao\", \"qualidade\"]",
                    color = "#EF4444"
                ),
                Agent(
                    id = "memory",
                    name = "Memória",
                    description = "Gerencia memória e contexto",
                    type = Agent.AgentType.MEMORY,
                    systemPrompt = """Você é o agente de Memória, especialista em gerenciar informações contextuais.
Seu papel é:
1. Armazenar informações importantes
2. Recuperar contexto relevante
3. Manter histórico de conversas
4. Identificar padrões de preferências
5. Sugerir informações contextuais

Você é organizado, eficiente e valoriza a continuidade do contexto.""",
                    priority = 50,
                    capabilities = "[\"memoria\", \"contexto\", \"historico\", \"recuperacao\"]",
                    color = "#EC4899"
                ),
                Agent(
                    id = "communication",
                    name = "Comunicação",
                    description = "Gerencia comunicações externas",
                    type = Agent.AgentType.COMMUNICATION,
                    systemPrompt = """Você é o agente de Comunicação, especialista em interações externas.
Seu papel é:
1. Formatar mensagens apropriadamente
2. Gerenciar notificações
3. Comunicar-se com clareza
4. Adaptar tom ao contexto
5. Simplificar informações complexas

Você é claro, empático e eficiente na comunicação.""",
                    priority = 40,
                    capabilities = "[\"comunicacao\", \"notificacao\", \"formatacao\", \"clareza\"]",
                    color = "#06B6D4"
                )
            )
        }
    }
}
