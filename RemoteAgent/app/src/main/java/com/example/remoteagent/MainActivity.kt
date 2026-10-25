package com.example.remoteagent

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
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
import com.example.remoteagent.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
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
                    var deviceId by remember { mutableStateOf(AgentTokenStore.getDeviceId(this)) }
                    var ownerEmail by remember { mutableStateOf(AgentTokenStore.getOwnerEmail(this)) }
                    var deviceName by remember {
                        mutableStateOf("${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    }
                    var registeringWithGoogle by remember { mutableStateOf(false) }
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
                            Text("To identify this target, use the same verified Google account on this device and the requesting app.")
                            TextField(value = baseUrl, onValueChange = { baseUrl = it },
                                label = { Text("RemoteHub HTTPS URL") })
                            TextField(value = deviceName, onValueChange = { deviceName = it },
                                label = { Text("Target device name") })
                            Button(
                                enabled = !registeringWithGoogle && token.isBlank()
                                    && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank(),
                                onClick = {
                                    if (deviceName.isBlank()) {
                                        status = "Enter a name for this target device."
                                        return@Button
                                    }
                                    registeringWithGoogle = true
                                    lifecycleScope.launch {
                                        try {
                                            val option = GetGoogleIdOption.Builder()
                                                .setFilterByAuthorizedAccounts(false)
                                                .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                                                .setAutoSelectEnabled(false)
                                                .build()
                                            val request = GetCredentialRequest.Builder()
                                                .addCredentialOption(option).build()
                                            val credential = CredentialManager.create(this@MainActivity)
                                                .getCredential(this@MainActivity, request).credential
                                            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                                status = "Choose a Google account to register this target."
                                                return@launch
                                            }
                                            val idToken = runCatching {
                                                GoogleIdTokenCredential.createFrom(credential.data).idToken
                                            }.getOrElse {
                                                status = "Google did not return a valid account token."
                                                return@launch
                                            }
                                            status = "Verifying Google account and registering this target..."
                                            runCatching {
                                                val account = AgentRepository.create(baseUrl)
                                                    .googleLogin(idToken)
                                                val registration = AgentRepository.create(baseUrl, account.token)
                                                    .registerAgent(
                                                        deviceName.trim(),
                                                        AgentTokenStore.getOrCreateInstallId(this@MainActivity)
                                                    )
                                                account.email to registration
                                            }.onSuccess { accountAndDevice ->
                                                val agentToken = accountAndDevice.second.agentToken
                                                token = agentToken
                                                deviceId = accountAndDevice.second.device.id
                                                ownerEmail = accountAndDevice.first
                                                AgentTokenStore.setBaseUrl(this@MainActivity, baseUrl)
                                                AgentTokenStore.set(this@MainActivity, agentToken)
                                                AgentTokenStore.setDeviceId(this@MainActivity, deviceId)
                                                AgentTokenStore.setOwnerEmail(this@MainActivity, ownerEmail)
                                                status = "Target identified with Google and linked to your account. Enable requests below when ready."
                                                val fcmTokenTask = runCatching {
                                                    FirebaseMessaging.getInstance().token
                                                }.getOrElse {
                                                    status = "Target linked. FCM push is unavailable until Firebase is configured."
                                                    null
                                                }
                                                fcmTokenTask?.addOnSuccessListener { fcmToken ->
                                                    AgentTokenStore.setFcmToken(this@MainActivity, fcmToken)
                                                    lifecycleScope.launch {
                                                        runCatching {
                                                            AgentRepository.create(baseUrl, agentToken)
                                                                .updateFcmToken(fcmToken)
                                                        }.onFailure {
                                                            status = "Target linked, but push registration failed: ${it.message}"
                                                        }
                                                    }
                                                }?.addOnFailureListener {
                                                    status = "Target linked. FCM push is unavailable until Firebase is configured."
                                                }
                                            }.onFailure {
                                                status = "Could not register target: ${it.message}"
                                            }
                                        } catch (error: GetCredentialException) {
                                            status = "Google sign-in failed: ${error.message}"
                                        } finally {
                                            registeringWithGoogle = false
                                        }
                                    }
                                }
                            ) {
                                Text(if (registeringWithGoogle) "Registering target..." else "Continue with Google to register this target")
                            }
                            if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
                                Text("Google sign-in is not configured in this build. Use the pairing token option or rebuild with GOOGLE_WEB_CLIENT_ID.")
                            }
                            if (ownerEmail.isNotBlank()) Text("Linked Google account: $ownerEmail")
                            if (deviceId.isNotBlank()) Text("Target device ID: $deviceId")
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
                        Text("Persistent remote authorization")
                        Text(
                            if (enabled) {
                                "ON: the linked account can send session requests until you explicitly revoke access here. This stays enabled across app restarts."
                            } else {
                                "OFF: no remote session requests are accepted."
                            }
                        )
                        if (enabled) {
                            Button(
                                enabled = repository != null,
                                onClick = {
                                    lifecycleScope.launch {
                                        runCatching { repository?.setAccess(false) }
                                            .onSuccess {
                                                enabled = it?.enabled ?: false
                                                status = "Remote authorization revoked. Pending and active sessions were ended."
                                            }
                                            .onFailure {
                                                status = "Could not revoke remote authorization: ${it.message}"
                                            }
                                    }
                                }
                            ) { Text("Revoke remote authorization") }
                        }
                        Text("Turning this on does not approve camera, microphone, or location access. You must still approve each request.")
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
