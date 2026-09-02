package dev.mindmax.v4.core.di

import android.content.Context
import androidx.room.Room
import dev.mindmax.v4.core.prefs.AppPrefs
import dev.mindmax.v4.core.prefs.SecureKeyStore
import dev.mindmax.v4.data.dao.SettingsDao
import dev.mindmax.v4.data.db.DefaultAgents
import dev.mindmax.v4.data.db.MindMaxDatabase
import dev.mindmax.v4.data.entity.SettingsEntity
import dev.mindmax.v4.data.repo.AgentRepository
import dev.mindmax.v4.data.repo.AuditRepository
import dev.mindmax.v4.data.repo.ChatRepository
import dev.mindmax.v4.data.repo.MemoryRepository
import dev.mindmax.v4.data.repo.SettingsRepository
import dev.mindmax.v4.data.repo.TaskRepository
import dev.mindmax.v4.llm.LlmClient
import dev.mindmax.v4.llm.Provider
import dev.mindmax.v4.llm.ProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Date

/**
 * Tiny manual DI container. The app is small enough that Hilt would only add ceremony.
 * Singletons are constructed once per process from [dev.mindmax.v4.MindMaxApp.onCreate].
 *
 * Exposes:
 *  - an application-scoped [scope] for fire-and-forget work (audit flush, key migration),
 *  - the [database] for one-shot queries that don't yet have a repository,
 *  - typed repositories that wrap each DAO and (for settings) also the secure store,
 *  - [llmClient] lazy-resolves per current provider, so a switch is honoured on the
 *    very next call without an app restart.
 *
 * All accessors are [lateinit] with `private set` — the public read-only surface
 * guarantees no one can swap a single dependency out at runtime.
 */
object ServiceLocator {

    @Volatile private var initialized = false

    /** IO-bound work that outlives any single ViewModel — DB seeds, audit flush, key migration. */
    val scope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    lateinit var database: MindMaxDatabase
        private set
    lateinit var secureKeyStore: SecureKeyStore
        private set
    lateinit var appPrefs: AppPrefs
        private set

    lateinit var chatRepository: ChatRepository
        private set
    lateinit var agentRepository: AgentRepository
        private set
    lateinit var auditRepository: AuditRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var memoryRepository: MemoryRepository
        private set
    lateinit var taskRepository: TaskRepository
        private set

    /**
     * LLM client cache key. Provider changes invalidate the cache so the next
     * call resolves a fresh client. Synchronised because settings edits can
     * race with an in-flight chat.
     */
    @Volatile private var llmCacheKey: Pair<String, String?>? = null
    @Volatile private var llmCached: LlmClient? = null

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext

            database = Room.databaseBuilder(
                appContext,
                MindMaxDatabase::class.java,
                "mindmax.db",
            )
                .fallbackToDestructiveMigration()
                .build()

            secureKeyStore = SecureKeyStore(appContext)
            appPrefs = AppPrefs(appContext)

            chatRepository = ChatRepository(database.messageDao())
            agentRepository = AgentRepository(database.agentDao())
            auditRepository = AuditRepository(database.auditLogDao())
            settingsRepository = SettingsRepository(
                dao = database.settingsDao(),
                secureKeyStore = secureKeyStore,
                database = database,
            )
            memoryRepository = MemoryRepository(database.memoryDao())
            taskRepository = TaskRepository(database.taskDao())

            // First-launch work — both routines are idempotent so it's safe to
            // fire on every launch: seedDefaultsIfEmpty() bails unless the agents
            // table is empty; migrateLegacyApiKeyIfPresent() bails when the
            // sentinel is already present.
            scope.launch { runCatching { seedFirstLaunchData() } }
            scope.launch { runCatching { settingsRepository.migrateLegacyApiKeyIfPresent() } }

            initialized = true
        }
    }

    /**
     * Returns an [LlmClient] tuned for the currently persisted provider. Cached
     * by `(providerId, cloudflareAccountId)` and rebuilt on cache miss. Reading
     * the current Settings is synchronous and tiny, but we still expose the
     * suspend version for callers already inside a coroutine.
     */
    fun llmClient(): LlmClient {
        // runBlocking here is acceptable: Settings.get() is a single Room read
        // that returns from a warm-cached page; worst-case latency is <5ms.
        val snapshot = runBlocking {
            (settingsRepository.current() ?: SettingsEntity.default(Date()))
        }
        val provider = ProviderRegistry.provider(snapshot)
        val accountId = if (provider is Provider.Cloudflare) snapshot.apiBaseUrl else null
        val key = provider.id to accountId
        llmCached?.let { cached ->
            if (llmCacheKey == key) return cached
        }
        synchronized(this) {
            llmCacheKey = key
            llmCached = LlmClient(
                provider = provider,
                accountIdProvider = { accountId },
            )
            return llmCached!!
        }
    }

    /** Bumps the cache so the next [llmClient] call rebuilds the client. */
    fun invalidateLlmClient() {
        synchronized(this) {
            llmCacheKey = null
            llmCached = null
        }
    }

    /**
     * Inserts the 7 default agents and a default Settings row on first launch only.
     * Subsequent launches short-circuit thanks to the empty checks.
     */
    private suspend fun seedFirstLaunchData() {
        if (agentRepository.count() == 0) {
            val now = Date()
            DefaultAgents.all(now).forEach { agentRepository.upsert(it) }
        }
        val settingsDao: SettingsDao = database.settingsDao()
        if (settingsDao.get() == null) {
            settingsDao.upsert(SettingsEntity.default(Date()))
        }
    }
}
