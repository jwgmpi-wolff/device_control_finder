package com.example.remotecontroller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.remotecontroller.data.ControllerRepository
import com.example.remotecontroller.data.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ControllerState(
    val busy: Boolean = false,
    val token: String? = null,
    val devices: List<Device> = emptyList(),
    val sessionId: String? = null,
    val sessionStatus: String? = null,
    val message: String? = null
)

class ControllerViewModel(
    private val anonymousRepository: ControllerRepository,
    private val repositoryForToken: (String) -> ControllerRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ControllerState())
    val state = _state.asStateFlow()

    fun login(email: String, password: String, onToken: (String) -> Unit) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true, message = null)
        runCatching { anonymousRepository.login(email, password) }
            .onSuccess {
                onToken(it.token)
                _state.value = _state.value.copy(busy = false, token = it.token, message = "Signed in.")
                loadDevices()
            }
            .onFailure { _state.value = _state.value.copy(busy = false, message = "Login failed: ${it.message}") }
    }

    fun restoreToken(token: String) {
        _state.value = _state.value.copy(token = token)
        loadDevices()
    }

    fun loadDevices() = viewModelScope.launch {
        val token = _state.value.token ?: return@launch
        _state.value = _state.value.copy(busy = true)
        runCatching { repositoryForToken(token).devices() }
            .onSuccess { _state.value = _state.value.copy(busy = false, devices = it) }
            .onFailure { _state.value = _state.value.copy(busy = false, message = "Could not load devices: ${it.message}") }
    }

    fun request(deviceId: String, capability: String) = viewModelScope.launch {
        val token = _state.value.token ?: return@launch
        _state.value = _state.value.copy(busy = true)
        runCatching { repositoryForToken(token).request(deviceId, capability) }
            .onSuccess {
                _state.value = _state.value.copy(
                    busy = false, sessionId = it.sessionId, sessionStatus = it.status,
                    message = "Request sent; waiting for owner approval (${it.sessionId})."
                )
            }
            .onFailure { _state.value = _state.value.copy(busy = false, message = "Request failed: ${it.message}") }
    }

    fun checkSession() = viewModelScope.launch {
        val token = _state.value.token ?: return@launch
        val sessionId = _state.value.sessionId ?: return@launch
        _state.value = _state.value.copy(busy = true)
        runCatching { repositoryForToken(token).session(sessionId) }
            .onSuccess {
                _state.value = _state.value.copy(
                    busy = false, sessionStatus = it.status, message = "Session status: ${it.status}."
                )
            }
            .onFailure { _state.value = _state.value.copy(busy = false, message = "Could not check session: ${it.message}") }
    }
}
