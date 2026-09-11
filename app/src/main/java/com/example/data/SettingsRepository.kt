package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    companion object {
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val PRONOUN_THEME = stringPreferencesKey("pronoun_theme")
        val CUSTOM_PRONOUNS = stringPreferencesKey("custom_pronouns")
        val OVERLAY_OPACITY = intPreferencesKey("overlay_opacity")
        val BOUNDING_BOX = stringPreferencesKey("bounding_box") // "x,y,w,h"
        val API_KEY = stringPreferencesKey("api_key")
        val AUTO_HIDE_SECONDS = intPreferencesKey("auto_hide_seconds")
        val TEXT_POS_X = intPreferencesKey("text_pos_x")
        val TEXT_POS_Y = intPreferencesKey("text_pos_y")
    }

    val selectedModel: Flow<String> = context.dataStore.data.map { it[SELECTED_MODEL] ?: "deepseek-v4-flash" }
    val customPronouns: Flow<String> = context.dataStore.data.map { 
        it[CUSTOM_PRONOUNS] ?: it[PRONOUN_THEME] ?: "ฉัน / เธอ" 
    }
    val pronounTheme: Flow<String> = customPronouns
    val overlayOpacity: Flow<Int> = context.dataStore.data.map { it[OVERLAY_OPACITY] ?: 85 }
    val boundingBox: Flow<String> = context.dataStore.data.map { it[BOUNDING_BOX] ?: "0,0,100,100" } // percentages
    val apiKey: Flow<String> = context.dataStore.data.map { it[API_KEY] ?: "" }
    val autoHideSeconds: Flow<Int> = context.dataStore.data.map { it[AUTO_HIDE_SECONDS] ?: 5 }
    val textPosX: Flow<Int> = context.dataStore.data.map { it[TEXT_POS_X] ?: -1 }
    val textPosY: Flow<Int> = context.dataStore.data.map { it[TEXT_POS_Y] ?: -1 }

    suspend fun updateApiKey(key: String) {
        context.dataStore.edit { it[API_KEY] = key }
    }

    suspend fun updateModel(model: String) {
        context.dataStore.edit { it[SELECTED_MODEL] = model }
    }

    suspend fun updateCustomPronouns(pronouns: String) {
        context.dataStore.edit { 
            it[CUSTOM_PRONOUNS] = pronouns 
            it[PRONOUN_THEME] = pronouns
        }
    }

    suspend fun updatePronounTheme(theme: String) {
        updateCustomPronouns(theme)
    }

    suspend fun updateOverlayOpacity(opacity: Int) {
        context.dataStore.edit { it[OVERLAY_OPACITY] = opacity.coerceIn(10, 100) }
    }

    suspend fun updateBoundingBox(box: String) {
        context.dataStore.edit { it[BOUNDING_BOX] = box }
    }

    suspend fun updateAutoHideSeconds(seconds: Int) {
        context.dataStore.edit { it[AUTO_HIDE_SECONDS] = seconds }
    }

    suspend fun updateTextPos(x: Int, y: Int) {
        context.dataStore.edit {
            it[TEXT_POS_X] = x
            it[TEXT_POS_Y] = y
        }
    }
}
