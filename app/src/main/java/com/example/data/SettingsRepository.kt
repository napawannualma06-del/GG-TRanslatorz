package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    companion object {
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val PRONOUN_THEME = stringPreferencesKey("pronoun_theme")
        val BOUNDING_BOX = stringPreferencesKey("bounding_box") // "x,y,w,h"
    }

    val selectedModel: Flow<String> = context.dataStore.data.map { it[SELECTED_MODEL] ?: "deepseek-chat" }
    val pronounTheme: Flow<String> = context.dataStore.data.map { it[PRONOUN_THEME] ?: "Neutral" }
    val boundingBox: Flow<String> = context.dataStore.data.map { it[BOUNDING_BOX] ?: "0,0,100,100" } // percentages

    suspend fun updateModel(model: String) {
        context.dataStore.edit { it[SELECTED_MODEL] = model }
    }

    suspend fun updatePronounTheme(theme: String) {
        context.dataStore.edit { it[PRONOUN_THEME] = theme }
    }

    suspend fun updateBoundingBox(box: String) {
        context.dataStore.edit { it[BOUNDING_BOX] = box }
    }
}
