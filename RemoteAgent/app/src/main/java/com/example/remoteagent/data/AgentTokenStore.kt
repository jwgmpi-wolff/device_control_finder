package com.example.remoteagent.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

object AgentTokenStore {
    private fun preferences(context: Context) = EncryptedSharedPreferences.create(
        context,
        "agent_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(context: Context): String? = preferences(context).getString("agent_token", null)
    fun set(context: Context, token: String) {
        preferences(context).edit().putString("agent_token", token.trim()).apply()
    }
    fun getBaseUrl(context: Context): String = preferences(context).getString("base_url", "").orEmpty()
    fun setBaseUrl(context: Context, baseUrl: String) {
        preferences(context).edit().putString("base_url", baseUrl.trim().trimEnd('/')).apply()
    }

    fun getDeviceId(context: Context): String = preferences(context).getString("device_id", "").orEmpty()
    fun setDeviceId(context: Context, deviceId: String) {
        preferences(context).edit().putString("device_id", deviceId).apply()
    }

    fun getOwnerEmail(context: Context): String = preferences(context).getString("owner_email", "").orEmpty()
    fun setOwnerEmail(context: Context, email: String) {
        preferences(context).edit().putString("owner_email", email).apply()
    }

    fun getFcmToken(context: Context): String? = preferences(context).getString("fcm_token", null)
    fun setFcmToken(context: Context, token: String) {
        preferences(context).edit().putString("fcm_token", token).apply()
    }

    fun getOrCreateInstallId(context: Context): String {
        val prefs = preferences(context)
        return prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit().putString("install_id", id).apply()
        }
    }
}
