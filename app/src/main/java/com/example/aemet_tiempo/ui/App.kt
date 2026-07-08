package com.example.aemet_tiempo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.example.aemet_tiempo.data.AemetClient
import com.example.aemet_tiempo.data.DiariaDia
import com.example.aemet_tiempo.data.HorariaDia
import com.example.aemet_tiempo.data.Municipio
import com.example.aemet_tiempo.data.MunicipiosRepository

/**
 * AEMET Tiempo home screen — faithful Compose port of
 * `web_version/src/App.tsx`. Layout is a vertical scrollable column
 * inside a phone-shaped card:
 *
 *  ┌──────────────────────────────────────┐
 *  │ ★ Málaga · Málaga          [↻]       │  top bar
 *  │ [search input … ▾]                   │  searchable dropdown
 *  │ ⚠ datos en caché …                   │  optional stale banner
 *  │ ☀️ 21° ┌ Precipitaciones: 0% ┐       │  summary
 *  │        │ Humedad: 65%        │       │
 *  │        │ Viento: 12 km/h     │       │
 *  │ Tiempo                  hoy · 14:32  │  headline
 *  │ ( Hoy | Mañana | mié )              │  day pills
 *  │ ( Temperatura | Precip. | Viento )   │  tab bar
 *  │ [─── line / bar / wind chart ───]    │  active chart
 *  │ [── 8-day daily forecast strip ──]   │
 *  │ Fuente: AEMET OpenData               │  footer
 *  └──────────────────────────────────────┘
 */

private val AccentYellow = Color(0xFFF2B400)
private val AccentYellowSoft = Color(0xFFFFF5D6)
private val AccentBlue = Color(0xFF1E6CFF)
private val MutedBlue = Color(0xFF9FBCFF)
private val Border = Color(0xFFE1E4E8)
private val SoftBg = Color(0xFFF6F7F9)
private val WarnBg = Color(0xFFFFF3C2)
private val WarnBorder = Color(0xFFE6C04A)
private val WarnText = Color(0xFF7A5D00)
private val PageBg = Color(0xFFF6F7FB)
private val MaxColdMin = Color(0xFF2563EB)
private val MaxWarmMax = Color(0xFFD97706)

@Composable
fun App(
    aemet: AemetClient,
    prefs: Prefs,
    municipiosRepo: MunicipiosRepository,
) {
    val vm: AppViewModel = viewModel(
        factory = simpleFactory { AppViewModel(aemet, prefs, municipiosRepo) }
    )
    val state by vm.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFE6E6E6), RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            TopBar(
                title = state.horaria?.let { "${it.nombre} · ${it.provincia}" } ?: "Elegir zona",
                isFavorite = state.favoriteInes.contains(state.municipioId),
                onToggleFavorite = { vm.toggleFavorite(state.municipioId) },
                onRefresh = { vm.refresh() },
                refreshing = state.isLoading,
            )

            Spacer(Modifier.height(10.dp))

            SearchBar(
                state = state,
                onPick = { vm.setMunicipioId(it.ine) },
                onToggleFav = { vm.toggleFavorite(it) },
            )

            val multiSeries = remember(state.horaria) {
                buildMultiDaySeries(state.horaria?.prediccion?.dia.orEmpty())
            }
            val currentDayOffset by remember(multiSeries, state.now) {
                derivedStateOf {
                    multiSeries.fechaByDay.indexOfFirst { isSameCalendarDay(it, state.now) }
                }
            }
            val hasCurrentData = currentDayOffset >= 0
            val currentHour = state.now.hour

            if (state.stale && !state.isLoading) {
                WarnBanner(
                    text = buildString {
                        append("AEMET no responde · datos en caché")
                        append(cacheAgeLabel(state.cacheAgeMs))
                        if (!hasCurrentData) append(" · no incluye la hora actual")
                    }
                )
            } else if (!state.stale && !state.isLoading && state.horaria != null && !hasCurrentData) {
                WarnBanner(
                    text = "El pronóstico en caché no incluye la hora actual " +
                            "(probablemente anterior a las 00:00 de hoy). Pulsa ↻ para refrescar."
                )
            }

            Spacer(Modifier.height(14.dp))

            val base = if (currentDayOffset >= 0) currentDayOffset else 0
            val targetDayOffset = when (state.dayMode) {
                DayMode.TODAY -> base
                DayMode.TOMORROW -> base + 1
                DayMode.DAY_AFTER -> base + 2
            }

            val view = remember(multiSeries, state.dayMode, currentHour, currentDayOffset) {
                filterByDayMode(multiSeries, state.dayMode, currentHour, currentDayOffset)
            }

            val precipBands = remember(state.horaria, targetDayOffset) {
                precipBandsForDay(state.horaria?.prediccion?.dia?.getOrNull(targetDayOffset))
            }

            val summary = remember(
                state.dayMode, hasCurrentData, currentDayOffset, targetDayOffset,
                view, currentHour, precipBands, state.horaria, state.diaria, state.now
            ) {
                buildSummary(
                    dayMode = state.dayMode,
                    multiSeries = multiSeries,
                    view = view,
                    precipBands = precipBands,
                    hasCurrentData = hasCurrentData,
                    currentDayOffset = currentDayOffset,
                    targetDayOffset = targetDayOffset,
                    currentHour = currentHour,
                    horaria = state.horaria,
                    diaria = state.diaria,
                    afterLabel = run {
                        val fAfter = multiSeries.fechaByDay.getOrNull(base + 2)
                        fmtAfterWeekday(fAfter)
                    },
                    nowLabel = fmtNowTimeLabel(state.now),
                )
            }

            SummaryHeader(summary)

            Spacer(Modifier.height(14.dp))

            val fTomorrow = multiSeries.fechaByDay.getOrNull(base + 1)
            val fAfter = multiSeries.fechaByDay.getOrNull(base + 2)
            DayPills(
                selected = state.dayMode,
                tomorrowEnabled = fTomorrow != null,
                afterEnabled = fAfter != null,
                afterLabel = fmtAfterWeekday(fAfter),
                onSelect = { vm.setDayMode(it) },
            )

            Spacer(Modifier.height(8.dp))

            TabBar(
                selected = state.tab,
                onSelected = { vm.setTab(it) },
            )

            Spacer(Modifier.height(8.dp))

            val currentIdxInView = remember(view, state.dayMode, currentHour, currentDayOffset) {
                if (state.dayMode != DayMode.TODAY || currentDayOffset < 0) -1
                else view.hours.indexOfFirst {
                    it.dayOffset == currentDayOffset && it.hour == currentHour
                }
            }

            ChartSection(
                tab = state.tab,
                view = view,
                precipBands = precipBands,
                multiPrecipMm = multiSeries.precipMm,
                currentIdx = currentIdxInView,
            )

            Spacer(Modifier.height(14.dp))

            DailyStrip(state.diaria?.prediccion?.dia.orEmpty())

            Spacer(Modifier.height(12.dp))

            if (state.isLoading) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                }
                Spacer(Modifier.height(6.dp))
            }
            state.error?.let { err ->
                val isWarn = state.horaria != null
                Text(
                    text = if (isWarn) "No se pudo refrescar: $err" else err,
                    color = if (isWarn) WarnText else Color(0xFFB00020),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "Fuente: AEMET OpenData",
                color = Color(0xFF666666),
                fontSize = 12.sp,
            )
        }
    }
}

/* ───────────────────── Top bar (favourite star + title + refresh) ───────────────────── */

@Composable
private fun TopBar(
    title: String,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = true),
        ) {
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(32.dp),
            ) {
                Text(
                    text = if (isFavorite) "★" else "☆",
                    color = if (isFavorite) AccentYellow else Color(0xFFC4C7CF),
                    fontSize = 22.sp,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(SoftBg)
                .border(1.dp, Border, CircleShape)
                .clickable(enabled = !refreshing, onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "↻",
                color = Color(0xFF444444),
                fontSize = 18.sp,
            )
        }
    }
}

/* ───────────────────── Search bar with dropdown ───────────────────── */

@Composable
private fun SearchBar(
    state: UiState,
    onPick: (Municipio) -> Unit,
    onToggleFav: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Debounced version of `query`. The filter only re-runs when this value
    // changes (~120 ms after the last keystroke), so rapid typing doesn't
    // hammer the 8 000-entry list on every char.
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(120L)
        debouncedQuery = query
    }

    var open by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Pre-compute the municipios index ONCE per change of the municipios
    // list (basically: once on launch, once again when the AEMET maestro
    // finishes downloading). Storing the pre-normalised name on each row
    // turns per-keystroke filtering from O(N · normalize) into O(N · contains),
    // which is dozens of times faster.
    val index = remember(state.municipios) { buildMunicipioIndex(state.municipios) }
    val byIne = index.byIne
    val selectedName = byIne[state.municipioId]?.nombre

    val filtered = remember(index, state.favoriteInes, debouncedQuery) {
        filterMunicipios(index, state.favoriteInes, debouncedQuery)
    }
    val hasContent = filtered.favs.isNotEmpty() || filtered.rest.isNotEmpty()

    fun dismiss() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                open = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                // Mirror the dropdown open-state to the field's focus:
                // tapping the field shows the dropdown immediately (even
                // with an empty query), and tapping elsewhere collapses it.
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) open = true
                },
            placeholder = {
                Text(
                    text = if (selectedName != null) "Buscar… (actual: $selectedName)"
                    else "Buscar municipio…",
                    fontSize = 13.sp,
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentYellow,
                unfocusedBorderColor = Border,
                cursorColor = AccentYellow,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { dismiss() }),
            trailingIcon = if (query.isNotEmpty() || open) {
                {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable {
                                query = ""
                                open = false
                                dismiss()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "✕", color = Color(0xFF888888), fontSize = 14.sp)
                    }
                }
            } else null,
        )
        if (open && hasContent) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                shadowElevation = 6.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                SearchDropdown(
                    filtered = filtered,
                    currentMunicipioId = state.municipioId,
                    favoriteInes = state.favoriteInes,
                    onPick = {
                        onPick(it)
                        query = ""
                        debouncedQuery = ""
                        open = false
                        dismiss()
                    },
                    onToggleFav = onToggleFav,
                )
            }
        }
    }
}

/**
 * Pre-normalised view of the municipios list. Built once per list change
 * and reused for every keystroke.
 */
private class MunicipioIndex(
    val all: List<IndexedMunicipio>,
    val byIne: Map<String, Municipio>,
)

private data class IndexedMunicipio(val m: Municipio, val normalizedName: String)

private fun buildMunicipioIndex(munis: List<Municipio>): MunicipioIndex {
    val indexed = ArrayList<IndexedMunicipio>(munis.size)
    val map = HashMap<String, Municipio>(munis.size)
    for (m in munis) {
        indexed.add(IndexedMunicipio(m, normalize(m.nombre)))
        map[m.ine] = m
    }
    return MunicipioIndex(indexed, map)
}

private data class FilteredMunis(val favs: List<Municipio>, val rest: List<Municipio>)

private fun filterMunicipios(
    index: MunicipioIndex,
    favoriteInes: List<String>,
    query: String,
): FilteredMunis {
    val q = normalize(query.trim())
    val favoriteSet = favoriteInes.toHashSet()
    val favs = favoriteInes.mapNotNull { ine -> index.byIne[ine] }.let { favList ->
        if (q.isEmpty()) favList
        else favList.filter { m -> normalize(m.nombre).contains(q) || m.ine.startsWith(q) }
    }
    val rest = if (q.isEmpty()) {
        index.all.asSequence()
            .filter { it.m.ine !in favoriteSet }
            .sortedByDescending { it.m.hab }
            .take(25)
            .map { it.m }
            .toList()
    } else {
        index.all.asSequence()
            .filter { it.m.ine !in favoriteSet }
            .filter { it.normalizedName.contains(q) || it.m.ine.startsWith(q) }
            .sortedWith(
                compareByDescending<IndexedMunicipio> { it.normalizedName.startsWith(q) }
                    .thenByDescending { it.m.hab }
            )
            .take(30)
            .map { it.m }
            .toList()
    }
    return FilteredMunis(favs, rest)
}

@Composable
private fun SearchDropdown(
    filtered: FilteredMunis,
    currentMunicipioId: String,
    favoriteInes: List<String>,
    onPick: (Municipio) -> Unit,
    onToggleFav: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .heightIn(max = 320.dp)
            .padding(4.dp),
    ) {
        if (filtered.favs.isNotEmpty()) {
            item { DropdownHeader(text = "Favoritos") }
            items(filtered.favs, key = { "fav-${it.ine}" }) { m ->
                SearchRow(
                    m = m,
                    isCurrent = m.ine == currentMunicipioId,
                    isFav = true,
                    onPick = onPick,
                    onToggleFav = onToggleFav,
                )
            }
        }
        if (filtered.favs.isNotEmpty() && filtered.rest.isNotEmpty()) {
            item { DropdownHeader(text = "Todos los municipios") }
        }
        items(filtered.rest, key = { it.ine }) { m ->
            SearchRow(
                m = m,
                isCurrent = m.ine == currentMunicipioId,
                isFav = favoriteInes.contains(m.ine),
                onPick = onPick,
                onToggleFav = onToggleFav,
            )
        }
    }
}

@Composable
private fun DropdownHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF888888),
    )
}

@Composable
private fun SearchRow(
    m: Municipio,
    isCurrent: Boolean,
    isFav: Boolean,
    onPick: (Municipio) -> Unit,
    onToggleFav: (String) -> Unit,
) {
    val bg = if (isCurrent) AccentYellowSoft else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onPick(m) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = m.nombre,
            modifier = Modifier.weight(1f, fill = true),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF111111),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "INE ${m.ine}",
            fontSize = 11.sp,
            color = Color(0xFF777777),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable { onToggleFav(m.ine) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isFav) "★" else "☆",
                color = if (isFav) AccentYellow else Color(0xFFC4C7CF),
                fontSize = 16.sp,
            )
        }
    }
}

/* ───────────────────── Stale-cache warning banner ───────────────────── */

@Composable
private fun WarnBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(WarnBg)
            .border(1.dp, WarnBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = "⚠", fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = WarnText,
            fontSize = 12.sp,
        )
    }
}

/* ───────────────────── Summary header ───────────────────── */

private data class SummaryValues(
    val mode: SummaryMode,
    val temp: Int?,
    val hum: Int?,
    val wind: Int?,
    val precip: Int?,
    val tempMin: Int?,
    val tempMax: Int?,
    val cieloIcon: String,
    val headline: String,
    val dayLabel: String,
    val timeLabel: String,
)

private enum class SummaryMode { NOW, AVG }

private fun buildSummary(
    dayMode: DayMode,
    multiSeries: MultiSeries,
    view: MultiSeries,
    precipBands: List<PrecipBand>,
    hasCurrentData: Boolean,
    currentDayOffset: Int,
    targetDayOffset: Int,
    currentHour: Int,
    horaria: com.example.aemet_tiempo.data.AemetMunicipioHoraria?,
    diaria: com.example.aemet_tiempo.data.AemetMunicipioDiaria?,
    afterLabel: String,
    nowLabel: String,
): SummaryValues {
    val dias = horaria?.prediccion?.dia.orEmpty()
    val diaForSummary = dias.getOrNull(targetDayOffset)
    if (dayMode == DayMode.TODAY) {
        val tempNow = if (hasCurrentData) valueAtHour(view.temp, currentHour, currentDayOffset) else null
        val humNow = if (hasCurrentData) valueAtHour(view.hum, currentHour, currentDayOffset) else null
        val windNow = if (hasCurrentData) valueAtHour(view.wind, currentHour, currentDayOffset) else null
        val precipNow: Int? = if (hasCurrentData) {
            val band = probForHour(currentHour, precipBands)
            avgOf(listOf(adjustProbByMm(band, currentHour, precipBands, multiSeries.precipMm)))
        } else null
        val code = if (hasCurrentData) estadoCieloAtHour(dias.getOrNull(currentDayOffset), currentHour) else null
        val desc = estadoCieloDescriptionAtHour(dias.getOrNull(currentDayOffset), currentHour).orEmpty()

        // Today's daily min/max — prefer the daily forecast endpoint, fall
        // back to deriving from the hourly series for today. Mirrors the
        // strategy used in the AVG branch below.
        val todayIdx = if (currentDayOffset >= 0) currentDayOffset else 0
        val dMax = toIntOrNullSafe(diaria?.prediccion?.dia?.getOrNull(todayIdx)?.temperatura?.maxima)
        val dMin = toIntOrNullSafe(diaria?.prediccion?.dia?.getOrNull(todayIdx)?.temperatura?.minima)
        val todayTempVals = multiSeries.temp.filter { it.dayOffset == todayIdx }.map { it.v }

        return SummaryValues(
            mode = SummaryMode.NOW,
            temp = tempNow?.toInt(),
            hum = humNow?.toInt(),
            wind = windNow?.toInt(),
            precip = precipNow,
            tempMin = dMin ?: todayTempVals.minOrNull()?.toInt(),
            tempMax = dMax ?: todayTempVals.maxOrNull()?.toInt(),
            cieloIcon = iconForCielo(code),
            headline = desc,
            dayLabel = "hoy",
            timeLabel = nowLabel,
        )
    }

    fun <T : Any> onlyDay(xs: List<T>, dayOf: (T) -> Int): List<T> =
        xs.filter { dayOf(it) == targetDayOffset }

    val tempVals = onlyDay(multiSeries.temp) { it.dayOffset }.map { it.v }
    val humVals = onlyDay(multiSeries.hum) { it.dayOffset }.map { it.v }
    val windVals = onlyDay(multiSeries.wind) { it.dayOffset }.map { it.v }
    val probsForDay = onlyDay(multiSeries.hours) { it.dayOffset }.map { p ->
        val band = probForHour(p.hour, precipBands)
        adjustProbByMm(band, p.hour, precipBands, multiSeries.precipMm)
    }

    val dMax = toIntOrNullSafe(diaria?.prediccion?.dia?.getOrNull(targetDayOffset)?.temperatura?.maxima)
    val dMin = toIntOrNullSafe(diaria?.prediccion?.dia?.getOrNull(targetDayOffset)?.temperatura?.minima)

    val cieloIcon = diaForSummary?.let { iconForCielo(dailyCieloCode(diaForSummary.toDiariaShape())) }
        ?: iconForCielo(null)
    val headline = diaForSummary?.estadoCielo?.firstOrNull { !it.descripcion.isNullOrBlank() }?.descripcion
        ?: ""

    return SummaryValues(
        mode = SummaryMode.AVG,
        temp = avgOf(tempVals),
        hum = avgOf(humVals),
        wind = avgOf(windVals),
        precip = if (probsForDay.isNotEmpty()) probsForDay.max().toInt() else null,
        tempMin = dMin ?: tempVals.minOrNull()?.toInt(),
        tempMax = dMax ?: tempVals.maxOrNull()?.toInt(),
        cieloIcon = cieloIcon,
        headline = headline,
        dayLabel = if (dayMode == DayMode.TOMORROW) "mañana" else afterLabel,
        timeLabel = "día completo",
    )
}

// HorariaDia.estadoCielo -> a fake DiariaDia just so `dailyCieloCode` can be
// reused (the daily helper looks at estadoCielo periods). Avoids duplicating
// the same lookup logic in two places.
private fun HorariaDia.toDiariaShape(): com.example.aemet_tiempo.data.DiariaDia =
    com.example.aemet_tiempo.data.DiariaDia(
        fecha = fecha,
        estadoCielo = estadoCielo.map {
            com.example.aemet_tiempo.data.EstadoCieloPeriodo(
                periodo = it.periodo,
                descripcion = it.descripcion,
                value = it.value,
            )
        },
    )

@Composable
private fun SummaryHeader(s: SummaryValues) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left column auto-sizes to its content (emoji + big temperature),
        // so the metrics column on the right gets all the leftover width.
        // This is what lets "Precipitaciones (máx): 0%" still fit on one
        // line when in "Mañana"/"+1d" mode, where labels become longer.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = s.cieloIcon, fontSize = 44.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = s.temp?.let { "$it°" } ?: "--°",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF111111),
                )
                if (s.tempMin != null || s.tempMax != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${s.tempMin ?: "--"}°",
                            color = MaxColdMin,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = " / ",
                            color = Color(0xFFAAAAAA),
                            fontSize = 13.sp,
                        )
                        Text(
                            text = "${s.tempMax ?: "--"}°",
                            color = MaxWarmMax,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MetricRow(
                label = "Precipitaciones" + if (s.mode == SummaryMode.AVG) " (máx)" else "",
                value = s.precip?.let { "$it%" } ?: "--",
            )
            MetricRow(
                label = "Humedad" + if (s.mode == SummaryMode.AVG) " (media)" else "",
                value = s.hum?.let { "$it%" } ?: "--",
            )
            MetricRow(
                label = "Viento" + if (s.mode == SummaryMode.AVG) " (medio)" else "",
                value = s.wind?.let { "$it km/h" } ?: "--",
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = "Tiempo",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${s.dayLabel} · ",
                    color = Color(0xFF555555),
                    fontSize = 13.sp,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(AccentYellowSoft)
                        .border(1.dp, Color(0xFFF2D27A), RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = s.timeLabel,
                        color = Color(0xFF111111),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (s.headline.isNotBlank()) {
                Text(
                    text = s.headline,
                    color = Color(0xFF555555),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Label takes the rest of the row's width and ellipsizes if the
        // longer "Precipitaciones (máx):" still wouldn't fit; the value
        // is given its full intrinsic width on the right so it's never
        // truncated.
        Text(
            text = "$label:",
            modifier = Modifier.weight(1f, fill = true),
            color = Color(0xFF666666),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = value,
            color = Color(0xFF111111),
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

/* ───────────────────── Day pills + Tab bar ───────────────────── */

@Composable
private fun DayPills(
    selected: DayMode,
    tomorrowEnabled: Boolean,
    afterEnabled: Boolean,
    afterLabel: String,
    onSelect: (DayMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Pill("Hoy", selected == DayMode.TODAY, enabled = true) { onSelect(DayMode.TODAY) }
        Pill("Mañana", selected == DayMode.TOMORROW, enabled = tomorrowEnabled) {
            if (tomorrowEnabled) onSelect(DayMode.TOMORROW)
        }
        Pill(afterLabel, selected == DayMode.DAY_AFTER, enabled = afterEnabled) {
            if (afterEnabled) onSelect(DayMode.DAY_AFTER)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Pill(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) AccentYellowSoft else Color.White
    val border = if (selected) AccentYellow else Border
    val fg = when {
        !enabled -> Color(0xFFCCCCCC)
        selected -> Color(0xFF7A5D00)
        else -> Color(0xFF555555)
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TabBar(
    selected: WeatherTab,
    onSelected: (WeatherTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        TabItem("Temperatura", selected == WeatherTab.TEMP) { onSelected(WeatherTab.TEMP) }
        Spacer(Modifier.width(14.dp))
        TabItem("Precipitaciones", selected == WeatherTab.PRECIP) { onSelected(WeatherTab.PRECIP) }
        Spacer(Modifier.width(14.dp))
        TabItem("Viento", selected == WeatherTab.WIND) { onSelected(WeatherTab.WIND) }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFFEEEEEE))
    )
}

@Composable
private fun TabItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = if (selected) Color(0xFF111111) else Color(0xFF666666),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(if (selected) 60.dp else 0.dp)
                .background(AccentYellow)
        )
    }
}

/* ───────────────────── Chart section (dispatches to one of 3 charts) ───────────────────── */

@Composable
private fun ChartSection(
    tab: WeatherTab,
    view: MultiSeries,
    precipBands: List<PrecipBand>,
    multiPrecipMm: List<HourPoint>,
    currentIdx: Int,
) {
    when (tab) {
        WeatherTab.TEMP -> {
            val points = view.temp.map { s ->
                ChartPoint(
                    label = fmtHourLabel(s.periodo),
                    value = s.v,
                    dayOffset = s.dayOffset,
                )
            }
            ScrollLineChart(
                points = points,
                lineColor = AccentYellow,
                fillColor = Color(0xFFFFF1C2),
                currentIdx = currentIdx,
                valueFormatter = { "${it.toInt()}°" },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        WeatherTab.PRECIP -> {
            val mmByHour = remember(view) {
                buildMap {
                    for (p in view.precipMm) put("${p.dayOffset}:${p.hour}", p.v)
                }
            }
            val points = view.hours.map { slot ->
                val band = probForHour(slot.hour, precipBands)
                val adj = adjustProbByMm(band, slot.hour, precipBands, multiPrecipMm)
                val mm = mmByHour["${slot.dayOffset}:${slot.hour}"] ?: 0.0
                ChartPoint(
                    label = fmtHourLabel(slot.periodo),
                    value = adj,
                    sub = "💧 ${fmtMm(mm)}",
                    dayOffset = slot.dayOffset,
                )
            }
            ScrollBarChart(
                points = points,
                barColor = AccentBlue,
                mutedColor = MutedBlue,
                currentIdx = currentIdx,
                valueFormatter = { "${it.toInt()}%" },
                yMax = 100.0,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        WeatherTab.WIND -> {
            val items = view.wind.map { w ->
                WindItem(
                    periodo = w.periodo,
                    speed = w.v.toInt(),
                    dir = w.dir.orEmpty(),
                    dayOffset = w.dayOffset,
                )
            }
            WindScroll(
                items = items,
                currentIdx = currentIdx,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/* ───────────────────── 8-day daily strip ───────────────────── */

@Composable
private fun DailyStrip(dias: List<DiariaDia>) {
    if (dias.isEmpty()) return
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        dias.take(8).forEach { dia ->
            DayCard(dia)
        }
    }
}

@Composable
private fun DayCard(dia: DiariaDia) {
    val max = dia.temperatura?.maxima
    val min = dia.temperatura?.minima
    val icon = iconForCielo(dailyCieloCode(dia))
    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SoftBg)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = fmtDow(dia.fecha),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF111111),
        )
        Spacer(Modifier.height(6.dp))
        Text(text = icon, fontSize = 22.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = max?.let { "$it°" } ?: "--°",
                color = MaxWarmMax,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = min?.let { "$it°" } ?: "--°",
                color = MaxColdMin,
                fontSize = 12.sp,
            )
        }
    }
}

