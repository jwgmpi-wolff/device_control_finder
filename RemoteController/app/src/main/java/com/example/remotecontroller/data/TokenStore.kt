package com.example.remotecontroller.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object TokenStore {
    private fun preferences(context: Context) = EncryptedSharedPreferences.create(
        context,
        "controller_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun get(context: Context) = preferences(context).getString("token", null)
    fun set(context: Context, token: String) {
        preferences(context).edit().putString("token", token).apply()
    }
    fun getBaseUrl(context: Context) = preferences(context).getString("base_url", "").orEmpty()
    fun setBaseUrl(context: Context, baseUrl: String) {
        preferences(context).edit().putString("base_url", baseUrl.trim().trimEnd('/')).apply()
    }
}
