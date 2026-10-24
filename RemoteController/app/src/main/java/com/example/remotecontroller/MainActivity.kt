package com.example.remotecontroller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.example.remotecontroller.data.ControllerRepository
import com.example.remotecontroller.data.TokenStore

class MainActivity : ComponentActivity() {
    @Suppress("UNCHECKED_CAST")
    private fun makeViewModel() = ViewModelProvider(this, object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val anonymous = ControllerRepository.create(BuildConfig.API_BASE_URL)
            val factory: (String) -> ControllerRepository = { ControllerRepository.create(BuildConfig.API_BASE_URL, it) }
            return ControllerViewModel(anonymous, factory) as T
        }
    })[ControllerViewModel::class.java]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = makeViewModel()
        TokenStore.get(this)?.let(model::restoreToken)
        setContent {
            MaterialTheme {
                val state by model.state.collectAsState()
                Surface(Modifier.fillMaxSize()) {
                    if (state.token == null) {
                        var email by remember { mutableStateOf("") }
                        var password by remember { mutableStateOf("") }
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("RemoteController", style = MaterialTheme.typography.headlineMedium)
                            OutlinedTextField(email, { email = it }, label = { Text("Email") })
                            OutlinedTextField(password, { password = it }, label = { Text("Password") })
                            Button(enabled = !state.busy, onClick = {
                                model.login(email, password) { TokenStore.set(this@MainActivity, it) }
                            }) { Text("Sign in") }
                        }
                    } else {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Devices", style = MaterialTheme.typography.headlineMedium)
                            Text("Actions send requests only; the device owner must approve each session.")
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(state.devices) { device ->
                                    Column {
                                        Text(device.name, style = MaterialTheme.typography.titleMedium)
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
