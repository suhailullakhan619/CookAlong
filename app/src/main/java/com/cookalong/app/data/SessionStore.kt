package com.cookalong.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "cookalong")

/** Local-only persistence (DataStore): the in-progress cooking session, theme, read-aloud and Alexa link. */
class SessionStore(private val context: Context) {

    suspend fun loadSession(): CookingSession? {
        val raw = context.dataStore.data.first()[SESSION] ?: return null
        return runCatching { RecipeRepository.json.decodeFromString(CookingSession.serializer(), raw) }.getOrNull()
    }

    suspend fun saveSession(session: CookingSession?) {
        context.dataStore.edit { prefs ->
            if (session == null) prefs.remove(SESSION)
            else prefs[SESSION] = RecipeRepository.json.encodeToString(CookingSession.serializer(), session)
        }
    }

    /** Read-aloud in cooking mode; on unless the user turned it off. */
    suspend fun loadReadAloud(): Boolean = context.dataStore.data.first()[READ_ALOUD] ?: true

    suspend fun saveReadAloud(on: Boolean) {
        context.dataStore.edit { it[READ_ALOUD] = on }
    }

    /** Random id that identifies this TV to the Alexa bridge. Created on first use; never shown on screen. */
    suspend fun deviceId(): String {
        context.dataStore.data.first()[DEVICE_ID]?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { it[DEVICE_ID] = id }
        return id
    }

    suspend fun loadAlexaLinked(): Boolean = context.dataStore.data.first()[ALEXA_LINKED] ?: false

    suspend fun saveAlexaLinked(linked: Boolean) {
        context.dataStore.edit { it[ALEXA_LINKED] = linked }
    }

    private companion object {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val ALEXA_LINKED = booleanPreferencesKey("alexa_linked")
        val READ_ALOUD = booleanPreferencesKey("read_aloud")
        val SESSION = stringPreferencesKey("session")
    }
}
