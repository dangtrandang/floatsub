package com.floatsub.probe.cloud

import android.content.Context
import android.content.SharedPreferences

class ApiKeyManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("spike_c_api_keys", Context.MODE_PRIVATE)

    fun getApiKey(provider: String): String {
        return prefs.getString("key_${provider.lowercase()}", "") ?: ""
    }

    fun saveApiKey(provider: String, key: String) {
        prefs.edit().putString("key_${provider.lowercase()}", key.trim()).apply()
    }

    fun clearApiKey(provider: String) {
        prefs.edit().remove("key_${provider.lowercase()}").apply()
    }

    companion object {
        fun maskKey(key: String): String {
            if (key.isBlank()) return ""
            return if (key.length <= 8) {
                "********"
            } else {
                val prefix = key.take(4)
                val suffix = key.takeLast(4)
                "$prefix...$suffix"
            }
        }
    }
}
