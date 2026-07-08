package com.example.aemet_tiempo.data

import com.example.aemet_tiempo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Result envelope returned by [AemetClient] cached endpoints. `stale` is
 * true when we couldn't refresh and served a previously cached payload,
 * and `ageMs` is the age of that payload (always non-negative).
 */
data class AemetCached<T>(
    val data: T,
    val stale: Boolean,
    val ageMs: Long,
)

/**
 * AEMET OpenData client.
 *
 * Notes about the AEMET API quirks (kept consistent with the web proxy
 * in `web_version/server.mjs`):
 *  - It's a 2-step flow: first call (with api_key header) returns a JSON
 *    envelope containing the real payload URL in `datos`; second call
 *    fetches that URL.
 *  - The server is an old Tomcat behind a BIG-IP load balancer that
 *    occasionally returns transient 500s or just drops the socket. We
 *    retry with exponential backoff (capped at 2 s, max 8 attempts).
 *  - `datos` payloads are served as ISO-8859-1 (latin1) without charset
 *    in the Content-Type header. OkHttp would otherwise default to
 *    UTF-8 and mangle accented characters, so we read the bytes and
 *    decode manually.
 *  - Hourly/daily forecast payloads are returned as a JSON array of one
 *    element; we unwrap it.
 *
 * The TLS 1.2 + no-HTTP/2 ALPN quirk is handled in [MainActivity] when
 * building the shared OkHttpClient — Android's OkHttp + Conscrypt stack
 * is more permissive than Node's, but we still restrict to TLS 1.2 +
 * HTTP/1.1 to be safe.
 */
class AemetClient(
    private val okHttp: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    // Path -> CacheEntry. The path is the trailing AEMET API path
    // (e.g. "/prediccion/especifica/municipio/horaria/29067") so the same
    // entry is reused regardless of which getter requested it.
    private val cache = mutableMapOf<String, CacheEntry<*>>()
    private val cacheLock = Mutex()

    private data class CacheEntry<T>(val data: T, val ts: Long)

    /* ───────── Public API ───────── */

    /**
     * Hourly forecast for a 5-digit AEMET municipio id (e.g. "29067").
     * Returns the freshly fetched payload, or a stale cached one if
     * AEMET is unreachable and we have a recent cache.
     */
    suspend fun getMunicipioHoraria(municipioId: String): AemetCached<AemetMunicipioHoraria> =
        getCachedJson(
            path = "/prediccion/especifica/municipio/horaria/$municipioId",
            freshMs = CACHE_FRESH_MS,
            staleMs = CACHE_STALE_MS,
        ) { raw ->
            json.decodeFromString<List<AemetMunicipioHoraria>>(raw).first()
        }

    /** Daily 7-day forecast for a municipio. Same cache rules as horaria. */
    suspend fun getMunicipioDiaria(municipioId: String): AemetCached<AemetMunicipioDiaria> =
        getCachedJson(
            path = "/prediccion/especifica/municipio/diaria/$municipioId",
            freshMs = CACHE_FRESH_MS,
            staleMs = CACHE_STALE_MS,
        ) { raw ->
            json.decodeFromString<List<AemetMunicipioDiaria>>(raw).first()
        }

    /**
     * Full AEMET maestro/municipios list. The result is the trimmed shape
     * the UI uses; the AEMET-side ids are 5 chars prefixed with "id"
     * which we strip here.
     */
    suspend fun getMunicipios(): AemetCached<List<Municipio>> =
        getCachedJson(
            path = "/maestro/municipios",
            freshMs = MUNI_FRESH_MS,
            staleMs = MUNI_STALE_MS,
        ) { raw ->
            val rows = json.decodeFromString<List<AemetMaestroMunicipio>>(raw)
            rows.mapNotNull { m ->
                val rawId = m.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val ine = rawId.removePrefix("id")
                val nombre = m.nombre?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Municipio(
                    ine = ine,
                    nombre = nombre,
                    destacada = m.destacada == "1",
                    hab = m.numHab?.toIntOrNull() ?: 0,
                )
            }.sortedByDescending { it.hab }
        }

    /* ───────── Cache wrapper ───────── */

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> getCachedJson(
        path: String,
        freshMs: Long,
        staleMs: Long,
        parser: (String) -> T,
    ): AemetCached<T> {
        val now = System.currentTimeMillis()
        cacheLock.withLock {
            val entry = cache[path] as CacheEntry<T>?
            if (entry != null && now - entry.ts < freshMs) {
                return AemetCached(entry.data, stale = false, ageMs = now - entry.ts)
            }
        }
        return try {
            val data = fetchAemetJson(path, parser)
            cacheLock.withLock { cache[path] = CacheEntry(data, System.currentTimeMillis()) }
            AemetCached(data, stale = false, ageMs = 0)
        } catch (t: Throwable) {
            val fallback = cacheLock.withLock { cache[path] as CacheEntry<T>? }
            val age = if (fallback != null) System.currentTimeMillis() - fallback.ts else Long.MAX_VALUE
            if (fallback != null && age < staleMs) {
                AemetCached(fallback.data, stale = true, ageMs = age)
            } else {
                throw t
            }
        }
    }

    /* ───────── HTTP layer ───────── */

    private suspend fun <T> fetchAemetJson(path: String, parser: (String) -> T): T {
        val url = "$BASE_URL$path"
        val firstRaw = httpGetStringWithRetry(url, withApiKey = true)
        val first = json.decodeFromString<AemetOpenDataResponse>(firstRaw)
        if (first.estado != null && first.estado >= 400) {
            throw AemetException(
                "AEMET ${first.estado}: ${first.descripcion ?: "sin descripción"}",
                aemetStatus = first.estado,
            )
        }
        val datos = first.datos
            ?: throw AemetException("AEMET response without 'datos' (estado=${first.estado} ${first.descripcion})")
        val payload = httpGetStringWithRetry(datos, withApiKey = false)
        // AEMET sometimes prefixes the JSON payload with a UTF-8 BOM.
        val cleaned = payload.trimStart().removePrefix("\uFEFF")
        return parser(cleaned)
    }

    private suspend fun httpGetStringWithRetry(url: String, withApiKey: Boolean): String {
        var lastError: Throwable? = null
        var backoff = 200L
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return httpGetString(url, withApiKey)
            } catch (retryable: RetryableHttpException) {
                lastError = retryable
            } catch (io: java.io.IOException) {
                lastError = io
            } catch (t: AemetException) {
                // Non-retryable upstream error (e.g. 404 from AEMET first call).
                throw t
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(2_000L)
            }
        }
        throw lastError ?: AemetException("AEMET request failed: $url")
    }

    private suspend fun httpGetString(url: String, withApiKey: Boolean): String =
        withContext(Dispatchers.IO) {
            val reqBuilder = Request.Builder().url(url).get()
                .header("Accept", "application/json")
                .header("User-Agent", "aemet-tiempo-android/1.0")
            if (withApiKey) reqBuilder.header("api_key", BuildConfig.AEMET_API_KEY)

            okHttp.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body ?: throw java.io.IOException("Empty body from $url")
                if (resp.code >= 500 || resp.code == 0) {
                    throw RetryableHttpException("HTTP ${resp.code} from $url")
                }
                if (!resp.isSuccessful) {
                    throw AemetException("HTTP ${resp.code} from $url")
                }
                // AEMET ships `datos` payloads as ISO-8859-1 without
                // declaring it in Content-Type; OkHttp's `body.string()`
                // would default to UTF-8 and corrupt accented chars.
                val ct = resp.header("Content-Type")?.lowercase().orEmpty()
                val charset = if (ct.contains("utf-8")) Charsets.UTF_8 else Charsets.ISO_8859_1
                String(body.bytes(), charset)
            }
        }

    companion object {
        private const val BASE_URL = "https://opendata.aemet.es/opendata/api"
        private const val MAX_ATTEMPTS = 8

        // Cache TTLs match the web proxy (`web_version/server.mjs`).
        private const val CACHE_FRESH_MS = 5L * 60L * 1000L           // 5 min
        private const val CACHE_STALE_MS = 60L * 60L * 1000L          // 1 h
        private const val MUNI_FRESH_MS = 24L * 60L * 60L * 1000L     // 24 h
        private const val MUNI_STALE_MS = 7L * 24L * 60L * 60L * 1000L // 7 d
    }
}

/** Recoverable HTTP error worth a retry (5xx, transient resets, ...). */
private class RetryableHttpException(message: String) : java.io.IOException(message)

/** Non-recoverable error returned from AEMET (4xx, malformed envelope). */
class AemetException(
    message: String,
    val aemetStatus: Int? = null,
) : RuntimeException(message)

