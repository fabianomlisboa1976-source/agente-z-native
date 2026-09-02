package dev.mindmax.v4.data.repo

import dev.mindmax.v4.core.prefs.SecureKeyStore
import dev.mindmax.v4.data.dao.SettingsDao
import dev.mindmax.v4.data.db.MindMaxDatabase
import dev.mindmax.v4.data.entity.AuditLogEntity
import dev.mindmax.v4.data.entity.AuditStatus
import dev.mindmax.v4.data.entity.AuditType
import dev.mindmax.v4.data.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Date

/**
 * The single point of contact for the user's settings.
 *
 * The SettingsEntity stored in Room only ever holds a sentinel for the API key
 * (`__ENC__:...`). Real keys live in [SecureKeyStore] — EncryptedSharedPreferences
 * with an Android Keystore master key. This class is the gate that enforces that
 * invariant: callers MUST go through [setApiKey]/[getApiKey], never write the
 * `SettingsEntity.apiKey` field directly.
 *
 * The migration routine [migrateLegacyApiKeyIfPresent] runs on database open and:
 *   1. detects a non-empty, non-sentinel plaintext column (e.g. from a prior install),
 *   2. copies that value into [SecureKeyStore],
 *   3. overwrites the Room column with `__ENC__:migrated`,
 *   4. writes an AuditLog of type SECURITY so the operation is traceable.
 *
 * If already migrated (sentinel present), the routine is a no-op.
 */
class SettingsRepository(
    private val dao: SettingsDao,
    private val secureKeyStore: SecureKeyStore,
    private val database: MindMaxDatabase,
) {

    fun observe(): Flow<SettingsEntity?> = dao.observe()

    /** Returns a SettingsEntity that always carries the decrypted API key in memory. */
    fun observeWithKey(): Flow<SettingsEntity?> = dao.observe().map { it?.withResolvedKey() }

    suspend fun current(): SettingsEntity? = dao.get()?.withResolvedKey()

    suspend fun upsert(settings: SettingsEntity, now: Date = Date()) {
        val stamped = settings.copy(updatedAt = now)
        // Persist only the sentinel in Room; the live key is touched separately.
        dao.upsert(stamped.copy(apiKey = stamped.apiKey.normalizedForDisk()))
    }

    suspend fun getApiKey(): String? = secureKeyStore.getApiKey()

    suspend fun setApiKey(value: String) {
        secureKeyStore.setApiKey(value)
        // Reflect presence in the Room row so observe() flips isConfigured true.
        val current = dao.get() ?: SettingsEntity.default(Date())
        dao.upsert(current.copy(apiKey = SettingsEntity.ENC_PREFIX + "present"))
    }

    suspend fun clearApiKey() {
        secureKeyStore.clearApiKey()
        val current = dao.get() ?: return
        dao.upsert(current.copy(apiKey = ""))
    }

    /**
     * Drains any plaintext API key left over from older installs into the secure
     * store. Safe to call on every database open — it bails the moment it sees a
     * sentinel. Writes a SECURITY audit entry on actual migration.
     */
    suspend fun migrateLegacyApiKeyIfPresent(now: Date = Date()) {
        val current = dao.get() ?: return
        val raw = current.apiKey
        if (raw.isBlank() || raw.startsWith(SettingsEntity.ENC_PREFIX)) return
        secureKeyStore.setApiKey(raw)
        dao.upsert(current.copy(apiKey = SettingsEntity.ENC_PREFIX + "migrated", updatedAt = now))
        database.auditLogDao().insert(
            AuditLogEntity(
                timestamp = now,
                type = AuditType.SECURITY,
                action = "settings.apiKey.migrate",
                details = "Plaintable legado criptografado e movido para SecureKeyStore.",
                status = AuditStatus.SUCCESS,
            ),
        )
    }

    /** Re-extracts the decrypted key into a transient copy; DB row still has the sentinel. */
    private fun SettingsEntity.withResolvedKey(): SettingsEntity {
        val key = secureKeyStore.getApiKey() ?: return this
        return copy(apiKey = key)
    }

    private fun String.normalizedForDisk(): String = when {
        isBlank() -> ""
        startsWith(SettingsEntity.ENC_PREFIX) -> this
        // Defensive: never let a plaintext key hit SQLite. Suffix with marker so observe()
        // still reads isConfigured=true and the UI keeps working.
        else -> SettingsEntity.ENC_PREFIX + "present"
    }
}
