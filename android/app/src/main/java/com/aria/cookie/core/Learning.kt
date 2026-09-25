package com.aria.cookie.core

import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

// ==================== AVISOS QUE APRENDEN (Thompson sampling) ====================

/** Cuántas veces un tipo de aviso te sirvió o no, por franja del día. */
@Serializable
data class NudgeStat(var useful: Int = 0, var useless: Int = 0, var shown: Int = 0)

fun nudgeType(key: String) = key.substringBefore(':')

fun dayBucket(hour: Int) = when (hour) {
    in 5..11 -> "mañana"
    in 12..17 -> "tarde"
    in 18..22 -> "noche"
    else -> "madrugada"
}

fun nudgeStatKey(key: String, at: Long) = "${nudgeType(key)}@${dayBucket(zoned(at).hour)}"

/**
 * Decide si mostrar un aviso muestreando de su distribución Beta(útil+2, inútil+1):
 * los avisos que te sirven salen más, los que ignoras o rechazas se apagan solos,
 * y de vez en cuando se prueba de nuevo por si cambiaste (exploración).
 */
fun shouldShowNudge(stats: Map<String, NudgeStat>, key: String, at: Long, rng: Random = Random.Default): Boolean {
    val s = stats[nudgeStatKey(key, at)] ?: return true
    // Los avisos mostrados sin respuesta cuentan como medio "no me sirvió".
    val ignored = (s.shown - s.useful - s.useless).coerceAtLeast(0)
    val a = s.useful + 2.0
    val b = s.useless + ignored * 0.5 + 1.0
    return sampleBeta(a, b, rng) > 0.35
}

fun nudgeUsefulness(stats: Map<String, NudgeStat>, type: String): Double? {
    val all = stats.filterKeys { it.startsWith("$type@") }.values
    val u = all.sumOf { it.useful }
    val n = all.sumOf { it.useless }
    return if (u + n == 0) null else u.toDouble() / (u + n)
}

private fun sampleBeta(a: Double, b: Double, rng: Random): Double {
    val x = sampleGamma(a, rng)
    val y = sampleGamma(b, rng)
    return x / (x + y)
}

/** Marsaglia–Tsang. */
private fun sampleGamma(shape: Double, rng: Random): Double {
    if (shape < 1) return sampleGamma(shape + 1, rng) * rng.nextDouble().pow(1 / shape)
    val d = shape - 1.0 / 3
    val c = 1 / sqrt(9 * d)
    while (true) {
        var x: Double
        var v: Double
        do {
            x = gaussian(rng)
            v = 1 + c * x
        } while (v <= 0)
        v = v * v * v
        val u = rng.nextDouble()
        if (u < 1 - 0.0331 * x * x * x * x) return d * v
        if (ln(u) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v
    }
}

private fun gaussian(rng: Random): Double {
    val u1 = max(rng.nextDouble(), 1e-12)
    val u2 = rng.nextDouble()
    return sqrt(-2 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
}

// ==================== PREDICTOR DE CONTEXTO (Bayes ingenuo) ====================

data class ContextGuess(val activity: String, val probability: Double, val because: String)

private val PREDICTABLE = setOf("actividad", "lugar", "calendario", "movimiento")

/**
 * Predice qué harás según el contexto (franja horaria, entre semana o finde, y
 * dónde estás) aprendiendo de tu línea de tiempo. Necesita algo de historia.
 */
fun predictContext(s: CookieState, now: Long, place: String? = currentPlace(s)?.label): ContextGuess? {
    val events = s.timeline.filter { it.kind in PREDICTABLE }
    if (events.size < 15) return null
    fun slot(at: Long) = zoned(at).hour / 2
    fun weekend(at: Long) = zoned(at).dayOfWeek.value >= 6
    // Lugar en el que estabas al ocurrir cada evento (el último evento de "lugar" anterior).
    var lastPlace: String? = null
    data class Obs(val act: String, val slot: Int, val weekend: Boolean, val place: String?)
    val obs = events.sortedBy { it.at }.mapNotNull { e ->
        if (e.kind == "lugar") { lastPlace = e.text.removePrefix("llegaste a "); null }
        else Obs(normalizeTopic(e.text), slot(e.at), weekend(e.at), lastPlace)
    }
    if (obs.size < 10) return null
    val slotNow = slot(now)
    val weekendNow = weekend(now)
    val acts = obs.groupBy { it.act }
    val total = obs.size.toDouble()
    val scores = acts.mapValues { (_, list) ->
        val n = list.size.toDouble()
        val pSlot = (list.count { it.slot == slotNow } + 0.5) / (n + 6)
        val pWeek = (list.count { it.weekend == weekendNow } + 0.5) / (n + 1)
        val pPlace = if (place == null) 1.0 else (list.count { it.place == place } + 0.5) / (n + 3)
        (n / total) * pSlot * pWeek * pPlace
    }
    val sum = scores.values.sum().takeIf { it > 0 } ?: return null
    val best = scores.maxBy { it.value }
    val count = acts[best.key]!!.count { it.slot == slotNow }
    return ContextGuess(
        best.key, best.value / sum,
        buildString {
            append("lo has hecho $count veces a esta hora")
            if (weekendNow) append(" en fin de semana")
            place?.let { append(" y estando en $it") }
        },
    )
}

// ==================== CLIMA Y TRÁFICO ====================

@Serializable
data class Weather(
    val temperature: Double = 0.0,
    val code: Int = 0,
    val rainProbNext3h: Int = 0,
    val maxToday: Double = 0.0,
    val minToday: Double = 0.0,
    val at: Long = 0,
) {
    val description get() = weatherText(code)
    val rainy get() = code in 51..67 || code in 80..82 || code in 95..99
}

fun weatherText(code: Int) = when (code) {
    0 -> "despejado"; 1, 2 -> "parcialmente nublado"; 3 -> "nublado"
    45, 48 -> "niebla"; in 51..57 -> "llovizna"; in 61..67 -> "lluvia"; in 71..77 -> "nieve"
    in 80..82 -> "chubascos"; in 95..99 -> "tormenta"; else -> "variable"
}

/** Tiempo de viaje estimado a un evento (Google Routes, opcional). */
@Serializable
data class TravelEstimate(val eventKey: String, val minutes: Int, val at: Long)
