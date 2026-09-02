package dev.mindmax.v4.ui.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mindmax.v4.core.di.ServiceLocator
import dev.mindmax.v4.data.entity.AuditLogEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuditViewModel : ViewModel() {

    private val _state = MutableStateFlow(AuditState())
    val state: StateFlow<AuditState> = _state

    init {
        viewModelScope.launch {
            ServiceLocator.auditRepository.observeRecent(500).collect { entries ->
                _state.update { it.copy(entries = entries) }
            }
        }
    }
}

data class AuditState(
    val entries: List<AuditLogEntity> = emptyList(),
)
