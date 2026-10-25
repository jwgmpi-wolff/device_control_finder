package com.example.remotecontroller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import com.example.remotecontroller.data.ControllerRepository
import com.example.remotecontroller.data.TokenStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @Suppress("UNCHECKED_CAST")
    private fun makeViewModel() = ViewModelProvider(this, object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val anonymousFactory: (String) -> ControllerRepository = { ControllerRepository.create(it) }
            val factory: (String, String) -> ControllerRepository = { url, token ->
                ControllerRepository.create(url, token)
            }
            return ControllerViewModel(anonymousFactory, factory) as T
        }
    })[ControllerViewModel::class.java]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = makeViewModel()
        val savedUrl = TokenStore.getBaseUrl(this)
        if (savedUrl.isNotBlank()) TokenStore.get(this)?.let { model.restoreToken(it, savedUrl) }
        setContent {
            MaterialTheme {
                val state by model.state.collectAsState()
                Surface(Modifier.fillMaxSize()) {
                    if (state.token == null) {
                        var email by remember { mutableStateOf("") }
                        var password by remember { mutableStateOf("") }
                        var baseUrl by remember { mutableStateOf(savedUrl) }
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("RemoteController", style = MaterialTheme.typography.headlineMedium)
                            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("RemoteHub HTTPS URL") })
                            OutlinedTextField(email, { email = it }, label = { Text("Email") })
                            OutlinedTextField(password, { password = it }, label = { Text("Password") })
                            Button(enabled = !state.busy, onClick = {
                                model.login(email, password, baseUrl) { token, url ->
                                    TokenStore.set(this@MainActivity, token)
                                    TokenStore.setBaseUrl(this@MainActivity, url)
                                }
                            }) { Text("Sign in") }
                            Button(enabled = !state.busy, onClick = {
                                model.register(email, password, baseUrl) { token, url ->
                                    TokenStore.set(this@MainActivity, token)
                                    TokenStore.setBaseUrl(this@MainActivity, url)
                                }
                            }) { Text("Create account") }
                            Button(enabled = !state.busy, onClick = {
                                if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "Google sign-in is not configured in this APK.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    return@Button
                                }
                                lifecycleScope.launch {
                                    try {
                                        val option = GetGoogleIdOption.Builder()
                                            .setFilterByAuthorizedAccounts(false)
                                            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                                            .setAutoSelectEnabled(false)
                                            .build()
                                        val request = GetCredentialRequest.Builder()
                                            .addCredentialOption(option).build()
                                        val result = CredentialManager.create(this@MainActivity)
                                            .getCredential(this@MainActivity, request)
                                        if (result.credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                            return@launch
                                        }
                                        val idToken = GoogleIdTokenCredential.createFrom(result.credential.data).idToken
                                        model.googleLogin(idToken, baseUrl) { token, url ->
                                            TokenStore.set(this@MainActivity, token)
                                            TokenStore.setBaseUrl(this@MainActivity, url)
                                        }
                                    } catch (error: GetCredentialException) {
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            "Google sign-in failed: ${error.message}",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }) { Text("Continue with Google") }
                            state.message?.let { Text(it) }
                        }
                    } else {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Devices", style = MaterialTheme.typography.headlineMedium)
                            Text("Actions send requests only; the device owner must approve each session.")
                            var deviceName by remember { mutableStateOf("") }
                            OutlinedTextField(deviceName, { deviceName = it }, label = { Text("New target device name") })
                            Button(enabled = !state.busy && deviceName.isNotBlank(),
                                onClick = { model.registerDevice(deviceName.trim()); deviceName = "" }) {
                                Text("Register and pair a target")
                            }
                            state.agentToken?.let {
                                Text("Copy this pairing token to the target device now:")
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(state.devices) { device ->
                                    Column {
                                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                                        Text("Device ID: ${device.id}", style = MaterialTheme.typography.bodySmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Button(enabled = !state.busy, onClick = { model.request(device.id, "CAMERA") }) { Text("Request camera") }
                                            Button(enabled = !state.busy, onClick = { model.request(device.id, "MICROPHONE") }) { Text("Request audio") }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Button(enabled = !state.busy, onClick = { model.request(device.id, "LOCATION_ONCE") }) { Text("Request one-time location") }
                                        }
                                    }
                                }
                            }
                            if (state.sessionId != null) {
                                Text("Session status: ${state.sessionStatus}")
                                Button(enabled = !state.busy, onClick = { model.checkSession() }) {
                                    Text("Check owner approval")
                                }
                            }
                            state.message?.let { Text(it) }
                        }
                    }
                }
            }
        }
    }
}
