package com.aria.cookie.aria

import com.aria.cookie.core.CookieState
import com.aria.cookie.core.currentPlace
import com.aria.cookie.core.eventKey
import com.aria.cookie.core.predictContext
import com.aria.cookie.core.zoned
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * ARIA M2 · Cascada con salida temprana (puerto de internal/earlyexit).
 *
 * Genera respuestas con lo que Cookie ya sabe de ti y decide si bastan para
 * contestar sin IA: umbral, consenso entre fuentes y umbral adaptativo que
 * aprende de tus correcciones.
 */
data class Candidate(val source: String, val answer: String, val key: String, val confidence: Double)

data class ExitDecision(val exit: Boolean, val answer: String = "", val confidence: Double = 0.0, val reason: String)

@Serializable
data class Gate(
    var threshold: Double = 0.7,
    val min: Double = 0.55,
    val max: Double = 0.95,
    var correct: Int = 0,
    var wrong: Int = 0,
) {
    fun decide(cands: List<Candidate>): ExitDecision {
        if (cands.isEmpty()) return ExitDecision(false, reason = "no sé responder esto sin pensar más")
        val sorted = cands.sortedByDescending { it.confidence }
        val best = sorted.first()
        var conf = best.confidence
        var reason = "umbral (${best.source} ${(conf * 100).toInt()}%)"
        sorted.drop(1).firstOrNull { it.source != best.source && best.key.isNotEmpty() && it.key.equals(best.key, ignoreCase = true) }?.let {
            conf = min(1.0, conf + CONSENSUS_BOOST)
            reason = "consenso ${best.source} + ${it.source}"
        }
        return if (conf >= threshold) ExitDecision(true, best.answer, conf, reason)
        else ExitDecision(false, best.answer, conf, "confianza ${(conf * 100).toInt()}% < umbral ${(threshold * 100).toInt()}%: escalo")
    }

    /** Un error sube el umbral más de lo que un acierto lo baja. */
    fun observe(confidence: Double, correct: Boolean) {
        if (correct) {
            this.correct++
            threshold = max(min, threshold - 0.01)
        } else {
            wrong++
            threshold = min(max, max(threshold + 0.05, confidence + 0.02))
        }
    }

    fun accuracy(): Double? = (correct + wrong).takeIf { it > 0 }?.let { correct.toDouble() / it }

    companion object { const val CONSENSUS_BOOST = 0.15 }
}

/** Respuestas locales por intención, a partir del estado de Cookie. */
object LocalAnswerer {
    private val DAY_HM = DateTimeFormatter.ofPattern("EEEE HH:mm")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun candidates(intent: String, s: CookieState, now: Long): List<Candidate> = buildList {
        val p = s.profile
        when (intent) {
            "agenda" -> {
                val today = zoned(now).toLocalDate()
                val next = s.upcoming.filter { it.end >= now }.take(5)
                // Solo si el calendario se ha leído alguna vez (si no, no sé nada de tu agenda).
                if (s.upcoming.isNotEmpty() || s.calendarLearned.isNotEmpty()) {
                    val todays = next.filter { zoned(it.start).toLocalDate() == today }
                    val text = when {
                        todays.isNotEmpty() -> "Hoy tienes: " + todays.joinToString("; ") { "${zoned(it.start).format(HM)} ${it.title}" } + "."
                        next.isNotEmpty() -> "Hoy nada más. Lo próximo: ${next.first().title}, el ${zoned(next.first().start).format(DAY_HM)}."
                        else -> "No veo nada en tu agenda."
                    }
                    // Fiable si el calendario se leyó hace poco.
                    add(Candidate("agenda", text, next.firstOrNull()?.let { eventKey(it) } ?: "vacía", if (s.upcoming.isNotEmpty()) 0.9 else 0.6))
                }
            }
            "rutina" -> {
                predictContext(s, now)?.let { g ->
                    add(Candidate("contexto", "Normalmente a esta hora: ${g.activity} (${g.because}).", g.activity, g.probability))
                }
                p.predictNext(now)?.let { r ->
                    add(Candidate("rutina", "Sueles ${r.activity} ahora (${r.reason}).", r.activity, r.confidence * 0.9))
                }
            }
            "musica" -> {
                p.music.nowPlaying?.let { add(Candidate("spotify", "Ahora suena $it.", it, 0.95)) }
                p.music.favoriteAt(zoned(now).hour)?.let { add(Candidate("hábito", "A esta hora sueles escuchar a $it.", it, 0.75)) }
                p.music.topArtists.firstOrNull()?.let { add(Candidate("top", "Lo que más escuchas últimamente: ${p.music.topArtists.take(3).joinToString(", ")}.", it, 0.7)) }
            }
            "lugar" -> currentPlace(s)?.takeIf { it.known && now - it.lastSeen < 2 * 3_600_000L }?.let {
                add(Candidate("ubicación", "Estás en ${it.label}.", it.label, 0.9))
            }
            "sueno" -> s.health.lastNight(now)?.let { n ->
                val avg = s.health.averageSleepMinutes()
                add(Candidate("salud", "Anoche dormiste ${n.minutes / 60}h ${n.minutes % 60}min" +
                    (if (avg > 0) " (tu media es ${avg / 60}h ${avg % 60}min)." else "."), "sueño", 0.95))
            }
            "clima" -> s.weather?.takeIf { now - it.at < 3 * 3_600_000L }?.let { w ->
                add(Candidate("clima", "Ahora: ${w.description}, ${w.temperature.toInt()}°. Hoy ${w.minToday.toInt()}–${w.maxToday.toInt()}°" +
                    (if (w.rainProbNext3h >= 30) ", ${w.rainProbNext3h}% de lluvia en las próximas horas." else "."), "clima", 0.9))
            }
            "pasos" -> if (s.health.stepsToday > 0) add(Candidate("salud", "Llevas ${s.health.stepsToday} pasos hoy" +
                (if (s.health.stepsAvg > 0) " (sueles hacer ${s.health.stepsAvg})." else "."), "pasos", 0.9))
        }
    }
}
