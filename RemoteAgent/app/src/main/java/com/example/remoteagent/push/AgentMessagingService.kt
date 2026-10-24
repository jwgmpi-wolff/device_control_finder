package com.example.remoteagent.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.remoteagent.ConsentActivity
import com.example.remoteagent.data.AgentRepository
import com.example.remoteagent.data.AgentTokenStore
import com.example.remoteagent.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.util.Log

class AgentMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        AgentTokenStore.setFcmToken(this, token)
        val agentToken = AgentTokenStore.get(this) ?: return
        val baseUrl = AgentTokenStore.getBaseUrl(this)
        if (baseUrl.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                AgentRepository.create(baseUrl, agentToken).updateFcmToken(token)
            }.onFailure { Log.e("RemoteAgent", "Could not refresh push token", it) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "SESSION_REQUEST") return
        val sessionId = message.data["sessionId"] ?: return
        val capability = message.data["capability"] ?: return
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = "session_requests"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Access requests", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intent = Intent(this, ConsentActivity::class.java)
            .setAction("com.example.remoteagent.CONSENT")
            .putExtra("sessionId", sessionId)
            .putExtra("capability", capability)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this, sessionId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Remote access request")
            .setContentText("${capability.replace('_', ' ').lowercase()} requires your approval")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(sessionId.hashCode(), notification)
    }
}
