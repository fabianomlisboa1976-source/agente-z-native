package dev.mindmax.v4.ui.settings

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mindmax.v4.core.di.ServiceLocator
import dev.mindmax.v4.data.entity.SettingsEntity
import dev.mindmax.v4.service.ServiceStarter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state

    init {
        viewModelScope.launch {
            ServiceLocator.settingsRepository.observe().collect { settings ->
                if (settings != null) hydrate(settings)
            }
        }
    }

    private fun hydrate(s: SettingsEntity) {
        val key = ServiceLocator.secureKeyStore.getApiKey()
        _state.update {
            it.copy(
                providerId = s.apiProvider,
                customBaseUrl = s.apiBaseUrl.orEmpty(),
                model = s.apiModel,
                temperature = s.temperature,
                maxTokens = s.maxTokens,
                topP = s.topP,
                multiAgentEnabled = s.multiAgentEnabled,
                crossAuditEnabled = s.crossAuditEnabled,
                serviceEnabled = s.serviceEnabled,
                autoStart = s.autoStart,
                hasApiKey = !key.isNullOrBlank(),
            )
        }
    }

    fun onProviderChange(id: String) {
        _state.update { it.copy(providerId = id) }
        save()
    }

    fun onCustomBaseUrlChange(value: String) {
        _state.update { it.copy(customBaseUrl = value) }
        save()
    }

    fun onModelChange(value: String) {
        _state.update { it.copy(model = value) }
        save()
    }

    fun onTemperatureChange(value: Float) {
        _state.update { it.copy(temperature = value) }
        save()
    }

    fun onMaxTokensChange(value: Int) {
        _state.update { it.copy(maxTokens = value) }
        save()
    }

    fun onTopPChange(value: Float) {
        _state.update { it.copy(topP = value) }
        save()
    }

    fun onMultiAgentToggle(value: Boolean) {
        _state.update { it.copy(multiAgentEnabled = value) }
        save()
    }

    fun onCrossAuditToggle(value: Boolean) {
        _state.update { it.copy(crossAuditEnabled = value) }
        save()
    }

    fun onServiceToggle(value: Boolean) {
        _state.update { it.copy(serviceEnabled = value) }
        save()
        // Best-effort FGS start/stop. ServiceStarter re-reads Settings
        // idempotently on its next call, so a race between save() and the
        // service-side check is harmless.
        ServiceStarter.ensureStartedIfEnabledAsync(getApplication())
    }

    fun onAutoStartToggle(value: Boolean) {
        _state.update { it.copy(autoStart = value) }
        save()
    }

    fun onApiKeyInputChange(value: String) {
        _state.update { it.copy(apiKeyInput = value) }
    }

    fun saveApiKey() {
        val value = _state.value.apiKeyInput.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            ServiceLocator.settingsRepository.setApiKey(value)
            _state.update { it.copy(apiKeyInput = "", hasApiKey = true) }
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            ServiceLocator.settingsRepository.clearApiKey()
            _state.update { it.copy(hasApiKey = false) }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            // Wipe every table the user-generated runtime data lives in. Agents
            // and Settings stay — they're configuration, not data. Failures
            // are isolated: a table that can't be cleared doesn't block the
            // others.
            runCatching { ServiceLocator.auditRepository.clear() }
            runCatching { ServiceLocator.chatRepository.clearAll() }
            runCatching { ServiceLocator.memoryRepository.clearAll() }
            runCatching { ServiceLocator.taskRepository.clearAll() }
        }
    }

    private fun save() {
        val s = _state.value
        viewModelScope.launch {
            val now = java.util.Date()
            ServiceLocator.settingsRepository.upsert(
                SettingsEntity(
                    apiProvider = s.providerId,
                    apiBaseUrl = s.customBaseUrl.takeIf { it.isNotBlank() },
                    apiModel = s.model,
                    temperature = s.temperature,
                    maxTokens = s.maxTokens,
                    topP = s.topP,
                    multiAgentEnabled = s.multiAgentEnabled,
                    crossAuditEnabled = s.crossAuditEnabled,
                    serviceEnabled = s.serviceEnabled,
                    autoStart = s.autoStart,
                    createdAt = now,
                    updatedAt = now,
                ),
                now,
            )
        }
    }
}

data class SettingsState(
    val providerId: String = "groq",
    val customBaseUrl: String = "",
    val model: String = "llama-3.3-70b-versatile",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024,
    val topP: Float = 1.0f,
    val multiAgentEnabled: Boolean = true,
    val crossAuditEnabled: Boolean = false,
    val serviceEnabled: Boolean = false,
    val autoStart: Boolean = false,
    val hasApiKey: Boolean = false,
    val apiKeyInput: String = "",
)
