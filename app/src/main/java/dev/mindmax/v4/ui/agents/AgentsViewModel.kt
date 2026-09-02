package dev.mindmax.v4.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mindmax.v4.core.di.ServiceLocator
import dev.mindmax.v4.data.entity.AgentEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgentsViewModel : ViewModel() {

    private val _state = MutableStateFlow(AgentsState())
    val state: StateFlow<AgentsState> = _state

    init {
        viewModelScope.launch {
            ServiceLocator.agentRepository.observeAll().collect { agents ->
                _state.update { it.copy(agents = agents) }
            }
        }
    }

    fun toggleActive(id: String, value: Boolean) {
        viewModelScope.launch {
            ServiceLocator.agentRepository.setActive(id, value)
        }
    }
}

data class AgentsState(
    val agents: List<AgentEntity> = emptyList(),
)
