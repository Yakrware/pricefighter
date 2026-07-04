package com.pricefighter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pricefighter.data.agent.PriceCheckAgent
import com.pricefighter.data.db.HistoryEntity
import com.pricefighter.data.repo.PriceCheckRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** State of the landing-page voice/text price-check agent. */
sealed interface AgentUiState {
    data object Idle : AgentUiState

    /** The agent is running; [step] is the current human-readable stage. */
    data class Working(val step: String) : AgentUiState

    /** Finished — the report is now the newest item in History. */
    data class Done(val query: String, val judgedByNano: Boolean) : AgentUiState

    data class Error(val message: String) : AgentUiState
}

class MainViewModel(
    private val repository: PriceCheckRepository,
    private val agent: PriceCheckAgent,
) : ViewModel() {

    val history: StateFlow<List<HistoryEntity>> = repository.history.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _agentState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    val agentState: StateFlow<AgentUiState> = _agentState.asStateFlow()

    /** Run the on-device agent on a spoken or typed request; the saved report lands in [history]. */
    fun runAgent(request: String) {
        if (request.isBlank() || _agentState.value is AgentUiState.Working) return
        viewModelScope.launch {
            runCatching {
                agent.run(request) { step -> _agentState.value = AgentUiState.Working(step) }
            }.onSuccess { outcome ->
                _agentState.value = AgentUiState.Done(outcome.query, outcome.judgedByNano)
            }.onFailure {
                _agentState.value = AgentUiState.Error(it.message ?: "Something went wrong.")
            }
        }
    }

    fun dismissAgentState() {
        _agentState.value = AgentUiState.Idle
    }

    fun deleteEntry(id: Long) = viewModelScope.launch { repository.deleteEntry(id) }

    fun clearHistory() = viewModelScope.launch { repository.clearHistory() }

    companion object {
        fun factory(repository: PriceCheckRepository, agent: PriceCheckAgent) = viewModelFactory {
            initializer { MainViewModel(repository, agent) }
        }
    }
}
