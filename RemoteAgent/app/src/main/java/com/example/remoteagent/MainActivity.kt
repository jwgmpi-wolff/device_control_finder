package com.example.remoteagent

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.example.remoteagent.data.AgentRepository
import com.example.remoteagent.data.AgentTokenStore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
        setContent {
            MaterialTheme {
                Surface {
                    var token by remember { mutableStateOf(AgentTokenStore.get(this).orEmpty()) }
                    var baseUrl by remember { mutableStateOf(AgentTokenStore.getBaseUrl(this)) }
                    var enabled by remember { mutableStateOf(false) }
                    var firstRunAccepted by remember {
                        mutableStateOf(getSharedPreferences("agent-settings", MODE_PRIVATE)
                            .getBoolean("first-run-consent", false))
                    }
                    var status by remember { mutableStateOf("Remote session requests are disabled.") }
                    var policyChecked by remember { mutableStateOf(false) }
                    val repository = remember(token, baseUrl) {
                        if (token.isBlank() || baseUrl.isBlank()) null
                        else runCatching { AgentRepository.create(baseUrl, token) }.getOrNull()
                    }
                    LaunchedEffect(repository) {
                        if (repository != null) {
                            runCatching { repository.access() }
                                .onSuccess { enabled = it.enabled; status = "Access setting loaded." }
                                .onFailure { status = "Could not load access setting: ${it.message}" }
                            AgentTokenStore.getFcmToken(this@MainActivity)?.let { fcmToken ->
                                runCatching { repository.updateFcmToken(fcmToken) }
                                    .onFailure { status = "Could not register push token: ${it.message}" }
                            }
                        }
                    }
                    Column(Modifier.padding(24.dp)) {
                        Text("RemoteAgent", style = MaterialTheme.typography.headlineMedium)
                        if (!firstRunAccepted) {
                            Text("Privacy and remote access")
                            Text("This app only shares a capability after you approve its specific request on this device. Push messages never start sensors. You can decline any request or disable access at any time.")
                            androidx.compose.foundation.layout.Row {
                                Checkbox(checked = policyChecked, onCheckedChange = { policyChecked = it })
                                Text("I understand and consent to receive session requests.")
                            }
                            Button(enabled = policyChecked, onClick = {
                                getSharedPreferences("agent-settings", MODE_PRIVATE).edit()
                                    .putBoolean("first-run-consent", true).apply()
                                firstRunAccepted = true
                                if (Build.VERSION.SDK_INT >= 33
                                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }) { Text("Continue") }
                        } else {
                            Text("Every camera, microphone, or location request requires your approval.")
                            Text("No sensor starts from a push notification. You can decline every request.")
                        TextField(value = baseUrl, onValueChange = { baseUrl = it },
                            label = { Text("RemoteHub HTTPS URL") })
                        TextField(value = token, onValueChange = { token = it },
                            label = { Text("Paired agent token") })
                        Button(onClick = {
                            val checkedRepository = runCatching { AgentRepository.create(baseUrl, token) }
                                .getOrElse {
                                    status = "Enter a valid HTTPS server URL and agent token."
                                    return@Button
                                }
                            AgentTokenStore.setBaseUrl(this@MainActivity, baseUrl)
                            AgentTokenStore.set(this@MainActivity, token)
                            status = "Pairing saved. Connecting to RemoteHub..."
                            val fcmToken = AgentTokenStore.getFcmToken(this@MainActivity)
                            lifecycleScope.launch {
                                runCatching {
                                    checkedRepository.access()
                                    fcmToken?.let { checkedRepository.updateFcmToken(it) }
                                }.onSuccess { status = "Connected. Pairing saved securely." }
                                    .onFailure { status = "Could not connect/register push token: ${it.message}" }
                            }
                            val tokenTask = runCatching { FirebaseMessaging.getInstance().token }
                                .getOrElse {
                                    status = "Connected. FCM is unavailable until Firebase is configured for this build."
                                    null
                                }
                            tokenTask?.addOnSuccessListener { refreshedToken ->
                                    AgentTokenStore.setFcmToken(this@MainActivity, refreshedToken)
                                    lifecycleScope.launch {
                                        runCatching { checkedRepository.updateFcmToken(refreshedToken) }
                                            .onFailure { status = "Connected, but push registration failed: ${it.message}" }
                                    }
                                }?.addOnFailureListener {
                                    status = "Connected. FCM is unavailable until Firebase is configured for this build."
                                }
                        }) { Text("Connect and save pairing") }
                        Text("Accepting requests")
                        Switch(checked = enabled, enabled = repository != null, onCheckedChange = { checked ->
                            lifecycleScope.launch {
                                runCatching { repository?.setAccess(checked) }
                                    .onSuccess {
                                        enabled = it?.enabled ?: false
                                        status = if (enabled) "Requests enabled; each still needs approval."
                                            else "Requests disabled."
                                    }
                                    .onFailure { status = "Could not update access: ${it.message}" }
                            }
                        })
                        Text(status)
                        }
                    }
                }
            }
        }
    }
}
