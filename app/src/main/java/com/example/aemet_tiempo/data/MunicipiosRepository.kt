package com.example.aemet_tiempo.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads the full AEMET municipios list and caches it on disk so the
 * search picker works offline after the first successful fetch.
 *
 * The web version equivalent is the `/api/aemet/municipios` proxy
 * endpoint in `web_version/server.mjs`, which loads the maestro list
 * once a day and falls back to a stale copy when AEMET is unreachable.
 *
 * On-disk format: a plain JSON array of [Municipio] objects, written to
 * `<app filesDir>/municipios.json`.
 */
class MunicipiosRepository(
    private val context: Context,
    private val aemet: AemetClient,
) {
    private val cacheFile: File
        get() = File(context.filesDir, CACHE_FILENAME)

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val listSer = ListSerializer(Municipio.serializer())

    /**
     * Loads the persisted municipios from disk, returning `null` if no
     * cache file exists or it is unreadable.
     */
    suspend fun loadFromDisk(): List<Municipio>? = withContext(Dispatchers.IO) {
        val f = cacheFile
        if (!f.exists() || f.length() == 0L) return@withContext null
        runCatching { json.decodeFromString(listSer, f.readText(Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "Failed to read cached municipios: ${it.message}") }
            .getOrNull()
    }

    /**
     * Fetches the full maestro list from AEMET and writes it to disk.
     * Returns the parsed list. Throws if both the network and the cache
     * are unavailable (callers should fall back to [FALLBACK_MUNIS]
     * in [com.example.aemet_tiempo.ui]).
     */
    suspend fun refresh(): List<Municipio> {
        val cached = aemet.getMunicipios()
        if (cached.data.isNotEmpty()) {
            saveToDisk(cached.data)
        }
        return cached.data
    }

    private suspend fun saveToDisk(list: List<Municipio>) = withContext(Dispatchers.IO) {
        runCatching {
            cacheFile.writeText(json.encodeToString(listSer, list), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "Failed to persist municipios: ${it.message}") }
    }

    companion object {
        private const val CACHE_FILENAME = "municipios.json"
        private const val TAG = "MunicipiosRepository"
    }
}
