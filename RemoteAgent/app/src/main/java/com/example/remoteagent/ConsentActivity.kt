package com.example.remoteagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.remoteagent.data.AgentRepository
import com.example.remoteagent.data.AgentTokenStore

class ConsentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionId = intent.getStringExtra("sessionId").orEmpty()
        val capability = intent.getStringExtra("capability").orEmpty()
        val token = AgentTokenStore.get(this)
        val repository = token?.let { AgentRepository.create(BuildConfig.API_BASE_URL, it) }
        setContent {
            MaterialTheme {
                if (repository == null) {
                    Text("Pair this device before responding to a request.")
                } else {
                    val model: AgentViewModel = viewModel(factory = AgentViewModelFactory(repository))
                    val state by model.state.collectAsState()
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Remote access request", style = MaterialTheme.typography.headlineMedium)
                        Text("A controller is requesting: ${capability.replace('_', ' ').lowercase()}.")
                        Text("Nothing is shared unless you choose Allow. You can decline or end access at any time.")
                        Text("Request reference: $sessionId")
                        Text(state.message ?: "Review the request before allowing access.")
                        Button(onClick = { model.decide(sessionId, true) }, enabled = sessionId.isNotBlank() && !state.busy) {
                            Text("Allow this session")
                        }
                        OutlinedButton(onClick = { model.decide(sessionId, false) }, enabled = sessionId.isNotBlank() && !state.busy) {
                            Text("Decline")
                        }
                        Button(onClick = { model.end(sessionId) }, enabled = state.accepted && !state.busy) {
                            Text("End approved session")
                        }
                    }
                }
            }
        }
    }
}

private class AgentViewModelFactory(private val repository: AgentRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(AgentViewModel::class.java)) {
            throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
        }
        @Suppress("UNCHECKED_CAST")
        return AgentViewModel(repository) as T
    }
}
