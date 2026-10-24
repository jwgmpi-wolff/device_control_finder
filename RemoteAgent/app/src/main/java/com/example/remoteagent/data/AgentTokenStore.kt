package com.example.remoteagent.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

    fun getFcmToken(context: Context): String? = preferences(context).getString("fcm_token", null)
    fun setFcmToken(context: Context, token: String) {
        preferences(context).edit().putString("fcm_token", token).apply()
    }
}
