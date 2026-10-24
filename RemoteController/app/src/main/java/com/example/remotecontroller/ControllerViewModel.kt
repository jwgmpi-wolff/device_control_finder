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
    val agentToken: String? = null,
    val sessionId: String? = null,
    val sessionStatus: String? = null,
    val message: String? = null
)

class ControllerViewModel(
    private val repositoryForUrl: (String) -> ControllerRepository,
    private val repositoryForToken: (String, String) -> ControllerRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ControllerState())
    val state = _state.asStateFlow()

    fun login(email: String, password: String, baseUrl: String, onToken: (String, String) -> Unit) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true, message = null)
        runCatching { repositoryForUrl(baseUrl).login(email, password) }
            .onSuccess {
                onToken(it.token, baseUrl)
                _state.value = _state.value.copy(busy = false, token = it.token, message = "Signed in.")
                _serverUrl = baseUrl
                loadDevices()
            }
            .onFailure { _state.value = _state.value.copy(busy = false, message = "Login failed: ${it.message}") }
    }

    fun register(email: String, password: String, baseUrl: String, onToken: (String, String) -> Unit) =
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            runCatching { repositoryForUrl(baseUrl).register(email, password) }
                .onSuccess {
                    onToken(it.token, baseUrl)
                    _state.value = _state.value.copy(busy = false, token = it.token, message = "Account created.")
                    _serverUrl = baseUrl
                    loadDevices()
                }
                .onFailure { _state.value = _state.value.copy(busy = false, message = "Registration failed: ${it.message}") }
        }

    fun googleLogin(idToken: String, baseUrl: String, onToken: (String, String) -> Unit) =
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            runCatching { repositoryForUrl(baseUrl).googleLogin(idToken) }
                .onSuccess {
                    onToken(it.token, baseUrl)
                    _state.value = _state.value.copy(
                        busy = false, token = it.token, message = "Signed in with Google."
                    )
                    _serverUrl = baseUrl
                    loadDevices()
                }
                .onFailure { _state.value = _state.value.copy(busy = false, message = "Google sign-in failed: ${it.message}") }
        }

    private var _serverUrl: String = ""

    fun restoreToken(token: String, baseUrl: String) {
        _serverUrl = baseUrl
        _state.value = _state.value.copy(token = token)
        loadDevices()
    }

    fun loadDevices() = viewModelScope.launch {
        val token = _state.value.token ?: return@launch
        _state.value = _state.value.copy(busy = true)
        runCatching { repositoryForToken(_serverUrl, token).devices() }
            .onSuccess { _state.value = _state.value.copy(busy = false, devices = it) }
            .onFailure { _state.value = _state.value.copy(busy = false, message = "Could not load devices: ${it.message}") }
    }

    fun registerDevice(name: String) = viewModelScope.launch {
        val token = _state.value.token ?: return@launch
        _state.value = _state.value.copy(busy = true, agentToken = null)
        runCatching { repositoryForToken(_serverUrl, token).registerDevice(name) }
            .onSuccess {
                _state.value = _state.value.copy(
                    busy = false,
                    devices = _state.value.devices + it.device,
                    agentToken = it.agentToken,
                    message = "Device registered. Copy its pairing token into RemoteAgent."
                )
            }
            .onFailure { _state.value = _state.value.copy(busy = false, message = "Device registration failed: ${it.message}") }
    }

    fun request(deviceId: String, capability: String) = viewModelScope.launch {
        val token = _state.value.token ?: return@launch
        _state.value = _state.value.copy(busy = true)
        runCatching { repositoryForToken(_serverUrl, token).request(deviceId, capability) }
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
        runCatching { repositoryForToken(_serverUrl, token).session(sessionId) }
            .onSuccess {
                _state.value = _state.value.copy(
                    busy = false, sessionStatus = it.status, message = "Session status: ${it.status}."
                )
            }
            .onFailure { _state.value = _state.value.copy(busy = false, message = "Could not check session: ${it.message}") }
    }
}
