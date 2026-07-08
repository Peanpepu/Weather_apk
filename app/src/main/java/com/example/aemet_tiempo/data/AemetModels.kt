package com.example.aemet_tiempo.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Some AEMET endpoints type the same `value` field as a JSON string in one
 * array and a JSON number in another (e.g. `estadoCielo[].value = "11"` but
 * `probPrecipitacion[].value = 0` inside the very same daily document).
 * This serializer normalises both flavours into `String?` so the rest of
 * the codebase can stay simple.
 */
object FlexibleStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return runCatching { decoder.decodeString() }.getOrNull()
        val element = jsonDecoder.decodeJsonElement()
        return when {
            element is JsonNull -> null
            element is JsonPrimitive -> element.content
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/**
 * AEMET OpenData first-call envelope. The endpoint returns this small JSON
 * pointing to the real payload URL in `datos`. On error `estado` is >= 400
 * and `descripcion` carries the message.
 */
@Serializable
data class AemetOpenDataResponse(
    val descripcion: String? = null,
    val estado: Int? = null,
    val datos: String? = null,
    val metadatos: String? = null,
)

/**
 * Hourly forecast top-level object. AEMET wraps it in a JSON array; the
 * client unwraps to the first element.
 *
 * Note: AEMET ships almost every numeric value as a *string* (e.g. `"21"`,
 * `"25.4"`), so we model them as `String?` and parse on demand. This
 * matches the web version's behaviour and avoids parse crashes when a
 * field is missing or formatted unexpectedly.
 */
@Serializable
data class AemetMunicipioHoraria(
    @SerialName("nombre") val nombre: String = "",
    @SerialName("provincia") val provincia: String = "",
    @SerialName("prediccion") val prediccion: HorariaPrediccion = HorariaPrediccion(),
)

@Serializable
data class HorariaPrediccion(
    @SerialName("dia") val dia: List<HorariaDia> = emptyList(),
)

@Serializable
data class HorariaDia(
    @SerialName("fecha") val fecha: String = "",
    @SerialName("orto") val orto: String? = null,
    @SerialName("ocaso") val ocaso: String? = null,
    @SerialName("temperatura") val temperatura: List<HoraValor> = emptyList(),
    @SerialName("sensTermica") val sensTermica: List<HoraValor> = emptyList(),
    @SerialName("humedadRelativa") val humedadRelativa: List<HoraValor> = emptyList(),
    @SerialName("precipitacion") val precipitacion: List<HoraValor> = emptyList(),
    @SerialName("probPrecipitacion") val probPrecipitacion: List<HoraValor> = emptyList(),
    @SerialName("probTormenta") val probTormenta: List<HoraValor> = emptyList(),
    @SerialName("nieve") val nieve: List<HoraValor> = emptyList(),
    @SerialName("probNieve") val probNieve: List<HoraValor> = emptyList(),
    @SerialName("vientoAndRachaMax") val vientoAndRachaMax: List<VientoHora> = emptyList(),
    @SerialName("estadoCielo") val estadoCielo: List<EstadoCieloHora> = emptyList(),
)

/**
 * Generic `{ periodo, value }` entry used for most hourly variables.
 * AEMET always sends `value` as a string (`"21"`, `"0.4"`, `"100"` ...).
 */
@Serializable
data class HoraValor(
    @SerialName("periodo") val periodo: String = "",
    @SerialName("value") @Serializable(with = FlexibleStringSerializer::class) val value: String? = null,
)

/**
 * Hourly cloud-state entry. `value` is the AEMET cielo code (e.g. "11",
 * "23n"); `descripcion` is a human-readable string ("Despejado", ...).
 */
@Serializable
data class EstadoCieloHora(
    @SerialName("periodo") val periodo: String = "",
    @SerialName("descripcion") val descripcion: String? = null,
    @SerialName("value") @Serializable(with = FlexibleStringSerializer::class) val value: String? = null,
)

/**
 * AEMET ships two flavours of `vientoAndRachaMax` entries per period:
 *  - the main wind: `{ periodo, velocidad: ["7"], direccion: ["NE"] }`
 *  - the max gust:   `{ periodo, value: "13" }`
 *
 * Both are modelled here; consumers pick the entry whose `velocidad` is
 * non-empty for the main wind.
 */
@Serializable
data class VientoHora(
    @SerialName("periodo") val periodo: String = "",
    @SerialName("direccion") val direccion: List<String> = emptyList(),
    @SerialName("velocidad") val velocidad: List<String> = emptyList(),
    @SerialName("value") @Serializable(with = FlexibleStringSerializer::class) val value: String? = null,
)

/* ───────────────────── Daily forecast ───────────────────── */

@Serializable
data class AemetMunicipioDiaria(
    @SerialName("nombre") val nombre: String = "",
    @SerialName("provincia") val provincia: String = "",
    @SerialName("prediccion") val prediccion: DiariaPrediccion = DiariaPrediccion(),
)

@Serializable
data class DiariaPrediccion(
    @SerialName("dia") val dia: List<DiariaDia> = emptyList(),
)

@Serializable
data class DiariaDia(
    @SerialName("fecha") val fecha: String = "",
    @SerialName("temperatura") val temperatura: TempMinMax? = null,
    @SerialName("estadoCielo") val estadoCielo: List<EstadoCieloPeriodo> = emptyList(),
    @SerialName("probPrecipitacion") val probPrecipitacion: List<EstadoCieloPeriodo> = emptyList(),
)

/**
 * Daily min/max temperature. AEMET sometimes ships them as numbers and
 * sometimes as strings, so use `String?` for robustness.
 */
@Serializable
data class TempMinMax(
    @SerialName("minima") @Serializable(with = FlexibleStringSerializer::class) val minima: String? = null,
    @SerialName("maxima") @Serializable(with = FlexibleStringSerializer::class) val maxima: String? = null,
)

@Serializable
data class EstadoCieloPeriodo(
    @SerialName("periodo") val periodo: String? = null,
    @SerialName("descripcion") val descripcion: String? = null,
    @SerialName("value") @Serializable(with = FlexibleStringSerializer::class) val value: String? = null,
)

/* ───────────────────── Maestro municipios ───────────────────── */

/**
 * One row of the AEMET maestro/municipios list. AEMET ids look like
 * "id29067"; we strip the "id" prefix in the repository so callers see
 * the bare 5-digit INE code.
 */
@Serializable
data class Municipio(
    @SerialName("ine") val ine: String,
    @SerialName("nombre") val nombre: String,
    @SerialName("destacada") val destacada: Boolean = false,
    @SerialName("hab") val hab: Int = 0,
)

/**
 * Raw AEMET maestro entry — accepts the loose, partial shape AEMET ships
 * (extra fields are ignored at parse time via `ignoreUnknownKeys`).
 */
@Serializable
data class AemetMaestroMunicipio(
    @SerialName("id") val id: String? = null,
    @SerialName("nombre") val nombre: String? = null,
    @SerialName("destacada") val destacada: String? = null,
    @SerialName("num_hab") val numHab: String? = null,
)

