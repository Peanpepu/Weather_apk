package com.example.aemet_tiempo.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

private val Context.dataStore by preferencesDataStore(name = "aemet_tiempo_prefs")

/**
 * Persisted user preferences.
 *
 * Mirrors the web version's localStorage layout:
 *  - `favorite_municipio_ines` is a JSON array of INE strings; matches
 *    the web's `aemet-favorite-ines` key.
 *  - `last_municipio_id` is the last selected INE (so we restore it on
 *    next launch when there are no favorites yet).
 *
 * For backward compatibility we also migrate the legacy single-favorite
 * key (`favorite_municipio_id`) into the list on first read.
 */
class Prefs(private val context: Context) {
    private val keyFavoritesJson = stringPreferencesKey("favorite_municipio_ines")
    private val keyLegacyFavorite = stringPreferencesKey("favorite_municipio_id")
    private val keyLast = stringPreferencesKey("last_municipio_id")

    private val json = Json { ignoreUnknownKeys = true }
    private val listSer = ListSerializer(String.serializer())

    /** Read-only stream of favourite INE codes. Order is the user's saved order. */
    val favoriteInesFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        decodeFavorites(prefs[keyFavoritesJson], legacy = prefs[keyLegacyFavorite])
    }

    /** Read-only stream of the last selected INE (or null if never set). */
    val lastMunicipioIdFlow: Flow<String?> = context.dataStore.data.map { it[keyLast] }

    /** One-shot read of favourites. Useful at startup. */
    suspend fun getFavoriteInes(): List<String> = favoriteInesFlow.first()

    suspend fun getLastMunicipioId(): String? = lastMunicipioIdFlow.first()

    /** Replace the favourites list. Pass an empty list to clear them. */
    suspend fun setFavoriteInes(ines: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[keyFavoritesJson] = json.encodeToString(listSer, ines)
            // Drop the legacy key once we've migrated.
            prefs.remove(keyLegacyFavorite)
        }
    }

    /** Append or remove `ine` from the favourites list (toggle). */
    suspend fun toggleFavorite(ine: String) {
        val cur = getFavoriteInes()
        val next = if (cur.contains(ine)) cur - ine else cur + ine
        setFavoriteInes(next)
    }

    suspend fun setLastMunicipioId(value: String) {
        context.dataStore.edit { it[keyLast] = value }
    }

    private fun decodeFavorites(raw: String?, legacy: String?): List<String> {
        if (!raw.isNullOrBlank()) {
            return runCatching { json.decodeFromString(listSer, raw) }
                .getOrDefault(emptyList())
                .filter { it.isNotBlank() }
        }
        // Best-effort migration from the single-favorite legacy key.
        return legacy?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
    }
}

