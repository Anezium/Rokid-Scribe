package com.rokidscribe

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ScribeSettingsStore(context: Context) {
    private val prefs = createPrefs(context.applicationContext)

    fun getElevenLabsApiKey(): String = getApiKey(TranscriptProvider.ELEVENLABS)

    fun setElevenLabsApiKey(value: String) {
        setApiKey(TranscriptProvider.ELEVENLABS, value)
    }

    fun getApiKey(provider: TranscriptProvider): String {
        val scopedKey = prefs.getString(apiKeyName(provider), "").orEmpty()
        if (scopedKey.isNotBlank()) {
            return scopedKey
        }
        return if (provider == TranscriptProvider.ELEVENLABS) {
            prefs.getString(KEY_ELEVENLABS_API_KEY, "").orEmpty()
        } else {
            ""
        }
    }

    fun setApiKey(provider: TranscriptProvider, value: String) {
        val trimmed = value.trim()
        prefs.edit()
            .putString(apiKeyName(provider), trimmed)
            .apply()

        if (provider == TranscriptProvider.ELEVENLABS) {
            prefs.edit()
                .putString(KEY_ELEVENLABS_API_KEY, trimmed)
                .apply()
        }
    }

    fun getSelectedTranscriptProvider(): String =
        prefs.getString(KEY_SELECTED_TRANSCRIPT_PROVIDER, "").orEmpty()

    fun setSelectedTranscriptProvider(providerName: String) {
        prefs.edit()
            .putString(KEY_SELECTED_TRANSCRIPT_PROVIDER, providerName.trim())
            .apply()
    }

    fun getSelectedDeviceAddress(): String = prefs.getString(KEY_SELECTED_DEVICE_ADDRESS, "").orEmpty()

    fun setSelectedDeviceAddress(address: String) {
        prefs.edit()
            .putString(KEY_SELECTED_DEVICE_ADDRESS, address.trim())
            .apply()
    }

    fun getPreferredTransportMode(): String = prefs.getString(KEY_PREFERRED_TRANSPORT_MODE, "").orEmpty()

    fun setPreferredTransportMode(mode: String) {
        prefs.edit()
            .putString(KEY_PREFERRED_TRANSPORT_MODE, mode.trim())
            .apply()
    }

    fun getDeepgramProjectId(): String = prefs.getString(KEY_DEEPGRAM_PROJECT_ID, "").orEmpty()

    fun setDeepgramProjectId(projectId: String) {
        prefs.edit()
            .putString(KEY_DEEPGRAM_PROJECT_ID, projectId.trim())
            .apply()
    }

    fun clearSelectedDeviceAddress() {
        prefs.edit()
            .remove(KEY_SELECTED_DEVICE_ADDRESS)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "rokid_scribe_prefs"
        private const val KEY_ELEVENLABS_API_KEY = "elevenlabs_api_key"
        private const val KEY_SELECTED_DEVICE_ADDRESS = "selected_device_address"
        private const val KEY_PREFERRED_TRANSPORT_MODE = "preferred_transport_mode"
        private const val KEY_SELECTED_TRANSCRIPT_PROVIDER = "selected_transcript_provider"
        private const val KEY_DEEPGRAM_PROJECT_ID = "deepgram_project_id"
    }

    private fun apiKeyName(provider: TranscriptProvider): String =
        "api_key_${provider.name.lowercase()}"

    private fun createPrefs(context: Context): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
