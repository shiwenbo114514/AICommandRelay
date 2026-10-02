package com.aicmd.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aicmd_settings")

class SettingsRepo(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val STREAM = booleanPreferencesKey("stream")
        val RISK_CONFIRM = booleanPreferencesKey("risk_confirm")
        val MAX_ITERS = intPreferencesKey("max_iters")
        val ROOT_MODE = stringPreferencesKey("root_mode")
        val THINKING_STRENGTH = stringPreferencesKey("thinking_strength")
        val VISION_MODE = stringPreferencesKey("vision_mode")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { p ->
            AppSettings(
                baseUrl = p[Keys.BASE_URL] ?: "https://api.deepseek.com/v1",
                apiKey = p[Keys.API_KEY] ?: "",
                model = p[Keys.MODEL] ?: "deepseek-reasoner",
                streamEnabled = p[Keys.STREAM] ?: true,
                highRiskConfirm = p[Keys.RISK_CONFIRM] ?: true,
                maxIterations = (p[Keys.MAX_ITERS] ?: 12).coerceIn(1, 30),
                rootMode = p[Keys.ROOT_MODE] ?: "auto",
                thinkingStrength = p[Keys.THINKING_STRENGTH] ?: "high",
                visionMode = p[Keys.VISION_MODE] ?: "auto",
                systemPrompt = p[Keys.SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
            )
        }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings.first())
        context.dataStore.edit { p ->
            p[Keys.BASE_URL] = next.baseUrl
            p[Keys.API_KEY] = next.apiKey
            p[Keys.MODEL] = next.model
            p[Keys.STREAM] = next.streamEnabled
            p[Keys.RISK_CONFIRM] = next.highRiskConfirm
            p[Keys.MAX_ITERS] = next.maxIterations
            p[Keys.ROOT_MODE] = next.rootMode
            p[Keys.THINKING_STRENGTH] = next.thinkingStrength
            p[Keys.VISION_MODE] = next.visionMode
            p[Keys.SYSTEM_PROMPT] = next.systemPrompt
        }
    }
}
