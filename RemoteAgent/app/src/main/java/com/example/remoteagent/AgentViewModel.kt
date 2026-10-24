package com.example.remoteagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.remoteagent.data.AgentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConsentUiState(
    val busy: Boolean = false,
    val accepted: Boolean = false,
    val message: String? = null
)

class AgentViewModel(private val repository: AgentRepository) : ViewModel() {
    private val _state = MutableStateFlow(ConsentUiState())
    val state = _state.asStateFlow()

    fun decide(sessionId: String, accept: Boolean) = viewModelScope.launch {
        _state.value = ConsentUiState(busy = true)
        runCatching { repository.decide(sessionId, accept) }
            .onSuccess {
                _state.value = ConsentUiState(
                    accepted = it.status == "accepted",
                    message = "Request ${it.status}."
                )
            }
            .onFailure { _state.value = ConsentUiState(message = "Could not send decision: ${it.message}") }
    }

    fun end(sessionId: String) = viewModelScope.launch {
        _state.value = ConsentUiState(busy = true)
        runCatching { repository.end(sessionId) }
            .onSuccess { _state.value = ConsentUiState(message = "Session ended.") }
            .onFailure { _state.value = ConsentUiState(message = "Could not end session: ${it.message}") }
    }
}
