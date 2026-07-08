package com.example.aemet_tiempo.ui

import com.example.aemet_tiempo.data.DiariaDia
import com.example.aemet_tiempo.data.EstadoCieloHora
import com.example.aemet_tiempo.data.EstadoCieloPeriodo
import com.example.aemet_tiempo.data.HoraValor
import com.example.aemet_tiempo.data.HorariaDia
import com.example.aemet_tiempo.data.VientoHora
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pure functions that turn the raw AEMET payload into the series and
 * lookups the UI renders. Direct Kotlin port of the helpers at the
 * bottom of `web_version/src/App.tsx`. Keep them pure so the ViewModel
 * can recompute on every state combine without surprises.
 */

private val SPANISH = Locale("es", "ES")

/* ───────────────────── enums ───────────────────── */

enum class DayMode { TODAY, TOMORROW, DAY_AFTER }
enum class WeatherTab { TEMP, PRECIP, WIND }

/* ───────────────────── primitive parsing ───────────────────── */

/** AEMET ships numerics as strings; parse leniently. Returns null when blank or unparseable. */
fun toIntOrNullSafe(s: String?): Int? {
    val v = s?.trim() ?: return null
    if (v.isEmpty()) return null
    // Tolerate decimals like "21.0" that should be 21 for integer fields.
    val d = v.toDoubleOrNull() ?: return null
    return d.toInt()
}

fun toDoubleOrNullSafe(s: String?): Double? {
    val v = s?.trim() ?: return null
    if (v.isEmpty()) return null
    return v.toDoubleOrNull()
}

/* ───────────────────── multi-day series ───────────────────── */

/**
 * One point in a per-hour series. `hour` is the local hour (0..23) and
 * `dayOffset` is the index of the source day in the AEMET hourly array
 * (0 = first day in the forecast, etc.).
 */
data class HourPoint(
    val dayOffset: Int,
    val hour: Int,
    val periodo: String,
    val v: Double,
    val dir: String? = null,
)

/** Hour position only — no value. Used as the chart's hour grid. */
data class HourSlot(
    val dayOffset: Int,
    val hour: Int,
    val periodo: String,
)

/**
 * Pre-built per-hour series spanning the first few AEMET days. The
 * `fechaByDay` array stores each day's ISO date (`"2026-05-16T00:00:00"`)
 * so the caller can pick the dayOffset that matches "today" in the local
 * calendar.
 */
data class MultiSeries(
    val hours: List<HourSlot>,
    val temp: List<HourPoint>,
    val hum: List<HourPoint>,
    val wind: List<HourPoint>,
    val precipMm: List<HourPoint>,
    val cielo: List<CieloPoint>,
    val fechaByDay: List<String?>,
)

data class CieloPoint(
    val dayOffset: Int,
    val hour: Int,
    val periodo: String,
    val code: String,
)

private val HOUR_REGEX = Regex("^\\d{1,2}$")
private val BAND_REGEX = Regex("^\\d{4}$")

private fun pickHourValues(arr: List<HoraValor>?): List<Pair<String, Double>> {
    if (arr == null) return emptyList()
    val out = ArrayList<Pair<String, Double>>(arr.size)
    for (x in arr) {
        val v = toDoubleOrNullSafe(x.value) ?: continue
        val p = x.periodo.trim()
        if (!HOUR_REGEX.matches(p)) continue
        out.add(p.padStart(2, '0') to v)
    }
    return out
}

fun buildMultiDaySeries(dias: List<HorariaDia>): MultiSeries {
    val daysCount = minOf(dias.size, 3)
    val hours = ArrayList<HourSlot>()
    val temp = ArrayList<HourPoint>()
    val hum = ArrayList<HourPoint>()
    val wind = ArrayList<HourPoint>()
    val precipMm = ArrayList<HourPoint>()
    val cielo = ArrayList<CieloPoint>()
    val fechaByDay = ArrayList<String?>(daysCount)

    for (di in 0 until daysCount) {
        val d = dias[di]
        fechaByDay.add(d.fecha.ifBlank { null })

        val tempH = pickHourValues(d.temperatura).sortedBy { it.first }
        val humH = pickHourValues(d.humedadRelativa)
        val mmH = pickHourValues(d.precipitacion)

        val windEntries = d.vientoAndRachaMax.filter { it.velocidad.isNotEmpty() }
        val cieloEntries = d.estadoCielo.filter { it.value != null && HOUR_REGEX.matches(it.periodo) }

        // Temperature series is the canonical hour grid for the day.
        for ((periodo, tValue) in tempH) {
            val hour = periodo.toInt()
            hours += HourSlot(di, hour, periodo)
            temp += HourPoint(di, hour, periodo, tValue)

            humH.firstOrNull { it.first == periodo }?.let { (_, v) ->
                hum += HourPoint(di, hour, periodo, v)
            }
            mmH.firstOrNull { it.first == periodo }?.let { (_, v) ->
                precipMm += HourPoint(di, hour, periodo, v)
            }

            val windEntry = windEntries.firstOrNull { padHour(it.periodo) == periodo }
            if (windEntry != null) {
                val speed = windEntry.velocidad.firstOrNull()?.toDoubleOrNull()
                val dir = windEntry.direccion.firstOrNull().orEmpty()
                if (speed != null) {
                    wind += HourPoint(di, hour, periodo, speed, dir = dir)
                }
            }

            val cieloEntry = cieloEntries.firstOrNull { padHour(it.periodo) == periodo }
            if (cieloEntry?.value != null) {
                cielo += CieloPoint(di, hour, periodo, cieloEntry.value)
            }
        }
    }

    return MultiSeries(
        hours = hours,
        temp = temp,
        hum = hum,
        wind = wind,
        precipMm = precipMm,
        cielo = cielo,
        fechaByDay = fechaByDay,
    )
}

private fun padHour(periodo: String): String = periodo.padStart(2, '0')

/* ───────────────────── filtering by day mode ───────────────────── */

/**
 * Filter a [MultiSeries] for the chosen view mode.
 *   - [DayMode.TODAY] = today from currentHour..23 + tomorrow's 00..05
 *   - [DayMode.TOMORROW] = full next day
 *   - [DayMode.DAY_AFTER] = full day-after
 *
 * `currentDayOffset` is the dayOffset whose `fecha` matches the local
 * calendar today; pass -1 if no day in the data matches today (we fall
 * back to dayOffset 0 in that case, like the web does).
 */
fun filterByDayMode(
    ms: MultiSeries,
    mode: DayMode,
    currentHour: Int,
    currentDayOffset: Int,
): MultiSeries {
    val base = if (currentDayOffset >= 0) currentDayOffset else 0
    val include: (Int, Int) -> Boolean = { dayOffset, hour ->
        when (mode) {
            DayMode.TODAY -> when (dayOffset) {
                base -> hour >= currentHour
                base + 1 -> hour < 6
                else -> false
            }
            DayMode.TOMORROW -> dayOffset == base + 1
            DayMode.DAY_AFTER -> dayOffset == base + 2
        }
    }
    return MultiSeries(
        hours = ms.hours.filter { include(it.dayOffset, it.hour) },
        temp = ms.temp.filter { include(it.dayOffset, it.hour) },
        hum = ms.hum.filter { include(it.dayOffset, it.hour) },
        wind = ms.wind.filter { include(it.dayOffset, it.hour) },
        precipMm = ms.precipMm.filter { include(it.dayOffset, it.hour) },
        cielo = ms.cielo.filter { include(it.dayOffset, it.hour) },
        fechaByDay = ms.fechaByDay,
    )
}

fun valueAtHour(arr: List<HourPoint>, hour: Int, dayOffset: Int): Double? =
    arr.firstOrNull { it.dayOffset == dayOffset && it.hour == hour }?.v

/* ───────────────────── precipitation probability bands ───────────────────── */

/**
 * AEMET ships precipitation probability per 6-h band (e.g. period
 * `"0006"` = 00:00..06:00). Pull the probability for `hour`.
 */
fun probForHour(hour: Int, bands: List<PrecipBand>): Double {
    for (b in bands) {
        val periodo = b.periodo
        if (!BAND_REGEX.matches(periodo)) continue
        val start = periodo.substring(0, 2).toInt()
        val end = periodo.substring(2, 4).toInt()
        val inside = if (start <= end) hour in start until end
        else hour >= start || hour < end
        if (inside) return b.v
    }
    return 0.0
}

/**
 * Adjust a hour's probability by the mm forecast in its band. If no mm
 * is forecast anywhere in the band we cap the displayed probability
 * (AEMET sometimes reports 100% with 0 mm — misleading at the hourly
 * grain).
 */
fun adjustProbByMm(
    bandProb: Double,
    hour: Int,
    bands: List<PrecipBand>,
    mm: List<HourPoint>,
): Double {
    val band = bands.firstOrNull { b ->
        BAND_REGEX.matches(b.periodo) && run {
            val s = b.periodo.substring(0, 2).toInt()
            val e = b.periodo.substring(2, 4).toInt()
            if (s <= e) hour in s until e else hour >= s || hour < e
        }
    } ?: return bandProb
    val s = band.periodo.substring(0, 2).toInt()
    val e = band.periodo.substring(2, 4).toInt()
    val maxMm = mm
        .filter { p -> if (s <= e) p.hour in s until e else p.hour >= s || p.hour < e }
        .maxOfOrNull { it.v } ?: return bandProb
    return if (maxMm > 0) bandProb else minOf(bandProb, 30.0)
}

data class PrecipBand(val periodo: String, val v: Double)

/** Build the per-band precipitation probability series for a single day. */
fun precipBandsForDay(d: HorariaDia?): List<PrecipBand> {
    if (d == null) return emptyList()
    return d.probPrecipitacion.mapNotNull { hv ->
        val v = toDoubleOrNullSafe(hv.value) ?: return@mapNotNull null
        if (!BAND_REGEX.matches(hv.periodo)) return@mapNotNull null
        PrecipBand(hv.periodo, v)
    }
}

/* ───────────────────── cielo (cloud-state) ───────────────────── */

/**
 * Map AEMET estadoCielo codes to a representative emoji. Codes ending
 * in 'n' are night variants. See
 * https://www.aemet.es/documentos/es/eltiempo/prediccion/comun/Estado_cielo.pdf
 */
fun iconForCielo(code: String?): String {
    if (code.isNullOrBlank()) return "❓"
    val isNight = code.endsWith("n")
    val n = code.trimEnd('n').toIntOrNull() ?: return "❓"
    return when {
        n in setOf(81, 82, 83) -> "🌫️"                                // Fog / haze / mist
        n in setOf(51, 52, 53, 54, 61, 62, 63, 64) -> "⛈️"             // Thunderstorms
        n in setOf(33, 34, 35, 36) -> "❄️"                            // Snow
        n in setOf(23, 24, 25, 26) -> "🌧️"                            // Rain (heavy)
        n in setOf(43, 44, 45, 46) -> "🌦️"                            // Light rain / showers
        n == 11 -> if (isNight) "🌙" else "☀️"
        n == 12 -> if (isNight) "🌙" else "🌤️"
        n == 13 -> if (isNight) "☁️" else "⛅"
        n == 14 -> "🌥️"
        n == 15 || n == 16 -> "☁️"
        n == 17 -> "🌤️"
        else -> "❓"
    }
}

/** Find an hour-specific estadoCielo code in a day's entries. */
fun estadoCieloAtHour(dia: HorariaDia?, hour: Int): String? {
    val arr = dia?.estadoCielo ?: return null
    val hh = hour.toString().padStart(2, '0')
    val exact = arr.firstOrNull { it.periodo == hh && !it.value.isNullOrBlank() }
    if (exact?.value != null) return exact.value
    return arr.firstOrNull { !it.value.isNullOrBlank() }?.value
}

/** Same, but for the daily forecast slots ("00-24", "00-12", "12-24"). */
fun dailyCieloCode(d: DiariaDia): String? {
    val full = d.estadoCielo.firstOrNull { (it.periodo == "00-24" || it.periodo.isNullOrBlank()) && !it.value.isNullOrBlank() }
    return full?.value ?: d.estadoCielo.firstOrNull { !it.value.isNullOrBlank() }?.value
}

/** Description text for the current hour (falls back to any non-empty one). */
fun estadoCieloDescriptionAtHour(dia: HorariaDia?, hour: Int): String? {
    val arr = dia?.estadoCielo ?: return null
    val hh = hour.toString().padStart(2, '0')
    return arr.firstOrNull { it.periodo == hh && !it.descripcion.isNullOrBlank() }?.descripcion
        ?: arr.firstOrNull { !it.descripcion.isNullOrBlank() }?.descripcion
}

/* ───────────────────── wind ───────────────────── */

/**
 * AEMET 16-wind-rose direction → degrees clockwise from north. AEMET
 * also uses 'C' for calm; we map it to 0 (the arrow length usually
 * collapses to nothing for calm anyway).
 */
fun dirToDeg(dir: String?): Float {
    if (dir.isNullOrBlank()) return 0f
    return when (dir.uppercase(Locale.ROOT)) {
        "N" -> 0f; "NNE" -> 22.5f; "NE" -> 45f; "ENE" -> 67.5f
        "E" -> 90f; "ESE" -> 112.5f; "SE" -> 135f; "SSE" -> 157.5f
        "S" -> 180f; "SSO" -> 202.5f; "SO" -> 225f; "OSO" -> 247.5f
        "O" -> 270f; "ONO" -> 292.5f; "NO" -> 315f; "NNO" -> 337.5f
        "C" -> 0f
        else -> 0f
    }
}

/* ───────────────────── date / time ───────────────────── */

private val ISO_DATETIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME
private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE

/** True when an AEMET `fecha` string is the same local calendar day as `now`. */
fun isSameCalendarDay(fecha: String?, now: LocalDateTime): Boolean {
    val parsed = parseAemetDate(fecha) ?: return false
    return parsed.year == now.year && parsed.dayOfYear == now.dayOfYear
}

private fun parseAemetDate(fecha: String?): LocalDate? {
    if (fecha.isNullOrBlank()) return null
    // AEMET sends "2026-05-16T00:00:00"; try a couple of fallbacks.
    return runCatching { LocalDateTime.parse(fecha, ISO_DATETIME).toLocalDate() }
        .recoverCatching { LocalDate.parse(fecha.take(10), ISO_DATE) }
        .getOrNull()
}

fun fmtDow(fecha: String?): String {
    val d = parseAemetDate(fecha) ?: return fecha?.take(10).orEmpty()
    return d.dayOfWeek.getDisplayName(TextStyle.SHORT, SPANISH)
        .lowercase(SPANISH)
        .replace(".", "")
}

fun fmtAfterWeekday(fecha: String?): String {
    return fmtDow(fecha).ifBlank { "Después" }
}

fun fmtHourLabel(periodo: String): String {
    val p = periodo.trim()
    return when {
        p.isEmpty() -> ""
        p.length == 1 -> "0$p:00"
        p.length == 2 -> "$p:00"
        p.contains(":") -> p
        else -> p
    }
}

fun fmtNowTimeLabel(now: LocalDateTime): String {
    return now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
}

fun fmtMm(mm: Double): String {
    if (mm.isNaN() || mm <= 0.0) return "0 mm"
    return if (mm < 10.0) String.format(SPANISH, "%.1f mm", mm)
    else "${mm.toInt()} mm"
}

/** Human-readable "hace 5 min" style label for the stale-cache banner. */
fun cacheAgeLabel(ms: Long?): String {
    if (ms == null || ms <= 0L) return ""
    val totalSec = (ms / 1000L).toInt()
    if (totalSec < 60) return " (hace ${totalSec}s)"
    val totalMin = ((ms + 30_000L) / 60_000L).toInt()
    if (totalMin < 60) return " (hace $totalMin min)"
    val h = totalMin / 60
    val m = totalMin % 60
    return if (m == 0) " (hace $h h)" else " (hace $h h $m min)"
}

/* ───────────────────── aggregations & misc ───────────────────── */

fun avgOf(xs: List<Double>): Int? {
    if (xs.isEmpty()) return null
    return Math.round(xs.sum() / xs.size).toInt()
}

/** Lower-case + remove diacritics for accent-insensitive substring search. */
fun normalize(s: String): String {
    val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
    return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase(SPANISH)
}

/** True when fecha matches an AEMET `fecha` (best-effort). */
fun ZoneId.nowDateTime(): LocalDateTime = LocalDateTime.now(this)

/** Convenience for places that just need the local hour (0..23). */
fun currentLocalHour(now: LocalDateTime = LocalDateTime.now()): Int = now.hour

/** Convenience for places that just need a HH:mm label of LocalDateTime.now(). */
fun currentLocalHourMinute(now: LocalDateTime = LocalDateTime.now()): String =
    now.toLocalTime().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        .format(DateTimeFormatter.ofPattern("HH:mm"))

/** Best-effort midnight-of-today helper (kept here so callers don't reinvent it). */
fun startOfToday(): LocalDateTime = LocalDate.now().atTime(LocalTime.MIDNIGHT)
