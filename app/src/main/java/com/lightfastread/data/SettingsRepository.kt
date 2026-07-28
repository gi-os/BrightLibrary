package com.lightfastread.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.json.Json

class SettingsRepository private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val state = mutableStateOf(load())

    private fun load(): Settings {
        val raw = prefs.getString(KEY, null) ?: return Settings()
        return runCatching { json.decodeFromString<Settings>(raw) }.getOrElse { Settings() }
    }

    fun update(transform: (Settings) -> Settings) {
        val newSettings = transform(state.value)
        state.value = newSettings
        prefs.edit().putString(KEY, json.encodeToString(Settings.serializer(), newSettings)).apply()
    }

    companion object {
        private const val PREFS = "fastread_settings"
        private const val KEY = "settings_json"

        @Volatile private var instance: SettingsRepository? = null
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}
