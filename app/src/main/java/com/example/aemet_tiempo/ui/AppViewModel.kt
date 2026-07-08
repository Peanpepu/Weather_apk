package com.example.aemet_tiempo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aemet_tiempo.data.AemetClient
import com.example.aemet_tiempo.data.AemetMunicipioDiaria
import com.example.aemet_tiempo.data.AemetMunicipioHoraria
import com.example.aemet_tiempo.data.Municipio
import com.example.aemet_tiempo.data.MunicipiosRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Top-level UI state. Everything the App composable needs to render
 * lives here. Derived values (multi-day series, summary, etc.) are
 * computed in the composable from this single source of truth.
 */
data class UiState(
    val municipioId: String = DEFAULT_MUNICIPIO_ID,
    val favoriteInes: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val horaria: AemetMunicipioHoraria? = null,
    val diaria: AemetMunicipioDiaria? = null,
    val stale: Boolean = false,
    val cacheAgeMs: Long? = null,
    /** Full AEMET maestro/municipios list (or the bundled fallback). */
    val municipios: List<Municipio> = FALLBACK_MUNIS,
    /** True while we are still trying to load the full municipios list. */
    val municipiosLoading: Boolean = true,
    val dayMode: DayMode = DayMode.TODAY,
    val tab: WeatherTab = WeatherTab.TEMP,
    val now: LocalDateTime = LocalDateTime.now(),
)

class AppViewModel(
    private val aemet: AemetClient,
    private val prefs: Prefs,
    private val municipiosRepo: MunicipiosRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        // 1) Restore the persisted municipio (most recent favourite, or
        //    last selected, or the default fallback) and kick off a refresh.
        viewModelScope.launch {
            val favs = prefs.getFavoriteInes()
            val last = prefs.getLastMunicipioId()
            // Mirrors the web's selection rule: most-recently added favourite,
            // else the persisted "last selected" id, else Málaga.
            val initial = favs.lastOrNull() ?: last ?: DEFAULT_MUNICIPIO_ID
            _state.update {
                it.copy(municipioId = initial, favoriteInes = favs)
            }
            refresh()
        }

        // 2) Load the full municipios list — first from disk, then refresh
        //    from AEMET in the background. Falls back silently to the
        //    bundled FALLBACK_MUNIS if both fail.
        viewModelScope.launch {
            val onDisk = municipiosRepo.loadFromDisk()
            if (!onDisk.isNullOrEmpty()) {
                _state.update { it.copy(municipios = onDisk, municipiosLoading = false) }
            }
            runCatching { municipiosRepo.refresh() }
                .onSuccess { fresh ->
                    if (fresh.isNotEmpty()) {
                        _state.update { it.copy(municipios = fresh, municipiosLoading = false) }
                    } else {
                        _state.update { it.copy(municipiosLoading = false) }
                    }
                }
                .onFailure {
                    _state.update { it.copy(municipiosLoading = false) }
                }
        }

        // 3) Re-evaluate "now" every minute so the highlighted hour and
        //    the time label stay fresh without re-fetching the forecast.
        viewModelScope.launch {
            while (true) {
                delay(60_000L)
                _state.update { it.copy(now = LocalDateTime.now()) }
            }
        }
    }

    /* ───────── Imperative API used by the UI ───────── */

    fun setMunicipioId(value: String) {
        _state.update { it.copy(municipioId = value) }
        viewModelScope.launch { prefs.setLastMunicipioId(value) }
        refresh()
    }

    fun toggleFavorite(ine: String) {
        val cur = state.value.favoriteInes
        val next = if (cur.contains(ine)) cur - ine else cur + ine
        _state.update { it.copy(favoriteInes = next) }
        viewModelScope.launch { prefs.setFavoriteInes(next) }
    }

    fun setDayMode(mode: DayMode) {
        _state.update { it.copy(dayMode = mode) }
    }

    fun setTab(tab: WeatherTab) {
        _state.update { it.copy(tab = tab) }
    }

    /**
     * Re-fetch horaria + diaria for the current municipio. Concurrent
     * requests cancel the previous one. Errors are stored on the state
     * but don't blank out previously loaded data — same as the web.
     */
    fun refresh() {
        val municipioId = state.value.municipioId.trim()
        if (municipioId.length != 5 || municipioId.any { !it.isDigit() }) {
            _state.update { it.copy(error = "El municipioId debe tener 5 dígitos (ej: 29067).") }
            return
        }

        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                // Use coroutineScope so a deserialization error in one child
                // is rethrown into our try/catch instead of propagating up
                // to viewModelScope (where it would crash the app).
                val (h, d) = coroutineScope {
                    val horariaD = async { aemet.getMunicipioHoraria(municipioId) }
                    val diariaD = async { aemet.getMunicipioDiaria(municipioId) }
                    horariaD.await() to diariaD.await()
                }
                val stale = h.stale || d.stale
                val age = listOfNotNull(
                    h.ageMs.takeIf { it > 0L },
                    d.ageMs.takeIf { it > 0L },
                ).maxOrNull()
                _state.update {
                    it.copy(
                        isLoading = false,
                        horaria = h.data,
                        diaria = d.data,
                        stale = stale,
                        cacheAgeMs = age,
                        error = null,
                        now = LocalDateTime.now(),
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(isLoading = false, error = t.message ?: "Error desconocido")
                }
            }
        }
    }
}

