package com.aria.cookie.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.min
import kotlin.math.pow

/** Tiempo en que un interés pierde la mitad de su peso si no vuelve a aparecer. */
const val INTEREST_HALF_LIFE_MS = 30L * 24 * 3600 * 1000

/** Hora del resumen mientras Cookie aún no conoce tu ritmo. */
const val DEFAULT_DELIVERY_HOUR = 8

/** Tema que le importa al dueño, con peso que decae con el tiempo. */
@Serializable
data class Interest(
    val topic: String,
    var weight: Double = 0.0,
    var mentions: Int = 0,
    var kind: String = "palabra", // trabajo, gusto, música, actividad, palabra, feedback
    val firstSeen: Long = 0,
    var lastSeen: Long = 0,
) {
    fun effectiveWeight(now: Long): Double {
        val age = (now - lastSeen).coerceAtLeast(0)
        return weight * 0.5.pow(age.toDouble() / INTEREST_HALF_LIFE_MS)
    }
}

/** Actividad recurrente con su distribución horaria. */
@Serializable
data class Routine(
    val activity: String,
    val hourCounts: MutableList<Int> = MutableList(24) { 0 },
    val dayCounts: MutableList<Int> = MutableList(7) { 0 },
    var count: Int = 0,
    var lastSeen: Long = 0,
)

/** Lo que Cookie sabe de tu música (Spotify). */
@Serializable
data class MusicTaste(
    var connected: Boolean = false,
    var spotifyName: String? = null,
    var topArtists: List<String> = emptyList(),
    var topTracks: List<String> = emptyList(),
    var genres: List<String> = emptyList(),
    var nowPlaying: String? = null,
    /** hora (0-23) → artista → reproducciones */
    val byHour: MutableMap<Int, MutableMap<String, Int>> = mutableMapOf(),
    var lastPlayedAt: Long = 0,
    var lastSync: Long = 0,
) {
    fun favoriteAt(hour: Int): String? = byHour[hour]?.maxByOrNull { it.value }?.key
}

/** El "yo digital": todo lo que Cookie sabe de su dueño. */
@Serializable
data class Profile(
    var name: String? = null,
    var occupation: String? = null,
    var location: String? = null,
    val interests: MutableMap<String, Interest> = mutableMapOf(),
    val dislikes: MutableMap<String, Long> = mutableMapOf(),
    /** interacciones por [día de la semana 0=domingo][hora] */
    val rhythm: MutableList<MutableList<Int>> = MutableList(7) { MutableList(24) { 0 } },
    val routines: MutableMap<String, Routine> = mutableMapOf(),
    val transitions: MutableMap<String, MutableMap<String, Int>> = mutableMapOf(),
    var lastActivity: String? = null,
    var lastActiveAt: Long = 0,
    var observations: Int = 0,
    val music: MusicTaste = MusicTaste(),
    /** Frases que le contaste, para que tu copia hable como tú. */
    val diary: MutableList<String> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun reinforce(rawTopic: String, kind: String, delta: Double, now: Long) {
        val topic = normalizeTopic(rawTopic)
        if (topic.isEmpty()) return
        if (delta > 0 && topic in dislikes) return
        val interest = interests.getOrPut(topic) { Interest(topic, kind = kind, firstSeen = now, lastSeen = now) }
        interest.weight = interest.effectiveWeight(now) + delta
        interest.lastSeen = now
        if (delta > 0) interest.mentions++
        if (kindRank(kind) > kindRank(interest.kind)) interest.kind = kind
        if (interest.weight <= 0.01) interests.remove(topic)
    }

    fun dislike(rawTopic: String, now: Long) {
        val topic = normalizeTopic(rawTopic)
        if (topic.isEmpty()) return
        dislikes[topic] = now
        interests.remove(topic)
    }

    fun forget(rawTopic: String): Boolean {
        val topic = normalizeTopic(rawTopic)
        val a = interests.remove(topic) != null
        val b = dislikes.remove(topic) != null
        val existed = a || b
        routines.remove(topic)
        return existed
    }

    fun topInterests(n: Int, now: Long): List<Interest> =
        interests.values.sortedWith(compareByDescending<Interest> { it.effectiveWeight(now) }.thenBy { it.topic })
            .let { if (n > 0) it.take(n) else it }

    /** Registra que estás activo en ese momento (tu ritmo de vida). */
    fun markActive(at: Long) {
        val t = zoned(at)
        rhythm[t.dayOfWeek.value % 7][t.hour]++
        if (at > lastActiveAt) lastActiveAt = at
    }

    /** Aprende una actividad: cuándo ocurre y qué la precede. */
    fun recordActivity(raw: String, at: Long) {
        val activity = normalizeTopic(raw)
        if (activity.isEmpty()) return
        val t = zoned(at)
        val r = routines.getOrPut(activity) { Routine(activity) }
        r.hourCounts[t.hour]++
        r.dayCounts[t.dayOfWeek.value % 7]++
        r.count++
        r.lastSeen = at
        val prev = lastActivity
        if (prev != null && prev != activity) {
            val next = transitions.getOrPut(prev) { mutableMapOf() }
            next[activity] = (next[activity] ?: 0) + 1
        }
        lastActivity = activity
    }

    /** Combina rutina horaria y transición desde la última actividad. */
    fun predictNext(now: Long): Prediction? {
        val h = zoned(now).hour
        val scores = mutableMapOf<String, Double>()
        val reasons = mutableMapOf<String, String>()
        for ((name, r) in routines) {
            if (r.count < 2) continue
            val hits = r.hourCounts[h] + r.hourCounts[(h + 1) % 24] + r.hourCounts[(h + 23) % 24]
            if (hits == 0) continue
            scores[name] = (scores[name] ?: 0.0) + 0.6 * hits / r.count
            reasons[name] = "sueles hacerlo a esta hora"
        }
        val last = lastActivity
        transitions[last]?.let { next ->
            val total = next.values.sum()
            for ((name, c) in next) {
                if (c < 2) continue
                scores[name] = (scores[name] ?: 0.0) + 0.4 * c / total
                reasons[name] = reasons[name]?.let { "$it y después de $last" } ?: "suele venir después de $last"
            }
        }
        val best = scores.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .firstOrNull() ?: return null
        return Prediction(best.key, min(best.value, 1.0), reasons[best.key].orEmpty())
    }

    /** Horas del día en que sueles estar activo, de más a menos. */
    fun peakHours(dayOfWeek: Int): List<Int> =
        (0 until 24).map { h -> h to (rhythm[dayOfWeek][h] * 3 + (0 until 7).sumOf { rhythm[it][h] }) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
            .map { it.first }

    /** ¿Es buen momento para avisarte? Sí si es una de tus 3 horas más activas. */
    fun isGoodMoment(now: Long): Boolean {
        val t = zoned(now)
        val peaks = peakHours(t.dayOfWeek.value % 7)
        if (observations < 10 || peaks.isEmpty()) return t.hour == DEFAULT_DELIVERY_HOUR
        return t.hour in peaks.take(3)
    }

    fun addDiary(text: String) {
        diary.add(text.take(300))
        while (diary.size > 60) diary.removeAt(0)
    }

    private fun kindRank(kind: String) = when (kind) {
        "trabajo" -> 4
        "gusto", "feedback", "música" -> 3
        "actividad" -> 2
        else -> 1
    }
}

data class Prediction(val activity: String, val confidence: Double, val reason: String)

fun zoned(at: Long): ZonedDateTime = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())

private val leadingFiller = setOf(
    "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "al", "a", "mucho", "mucha",
    "muchos", "muchas", "muchísimo", "bastante", "todo", "toda", "lo", "mi", "mis", "sobre", "que",
    "ver", "leer", "hacer",
)
private val trailingFiller = setOf("también", "tambien", "mucho", "muchísimo", "hoy", "ayer", "ahora", "siempre", "bastante")

internal fun isLeadingFiller(w: String) = w in leadingFiller

/** Minúsculas, sin artículos ni signos, máx. 4 palabras. */
fun normalizeTopic(s: String): String {
    var words = s.lowercase().trim().trim(' ', '.', ',', ';', ':', '!', '¡', '?', '¿', '"', '\'', '(', ')', '[', ']')
        .split(Regex("\\s+")).filter { it.isNotEmpty() }
    while (words.isNotEmpty() && words.first() in leadingFiller) words = words.drop(1)
    while (words.isNotEmpty() && words.last() in trailingFiller) words = words.dropLast(1)
    return words.take(4).joinToString(" ")
}
