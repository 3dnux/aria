package com.aria.cookie.core

import java.net.URLEncoder
import java.time.format.DateTimeFormatter

/** Algo que Cookie se adelanta a decirte, con una acción opcional de un toque. */
data class Nudge(
    val key: String, // tipo:detalle — el tipo aprende si te sirve o no
    val title: String,
    val text: String,
    val action: PendingAction? = null,
)

private val HM = DateTimeFormatter.ofPattern("HH:mm")

private fun music(q: String) = PendingAction(type = ActionTypes.PLAY_MUSIC, params = mapOf("consulta" to q), description = describeAction(ActionTypes.PLAY_MUSIC, mapOf("consulta" to q)))

fun eventKey(e: CalendarEvent) = "${e.id}:${e.start}"

/**
 * Se anticipa según el contexto: tu agenda (con el tráfico real si hay clave),
 * el clima, cómo dormiste, tu estrés, dónde acabas de llegar y lo que sueles
 * hacer en esta situación. Solo devuelve avisos que aún no te dio.
 */
fun nudges(s: CookieState, now: Long, arrivedAt: Place? = null): List<Nudge> {
    val out = mutableListOf<Nudge>()
    val t = zoned(now)
    val day = t.toLocalDate().toString()
    val p = s.profile

    // 1. Un evento: avisa cuando toca salir (con tráfico) o ~1 h antes.
    s.upcoming.filter { !it.allDay && it.start > now }.forEach { e ->
        val travel = s.travel.firstOrNull { it.eventKey == eventKey(e) }?.minutes
        val leadMin = (travel ?: 0) + 15
        val minutesTo = (e.start - now) / 60_000
        val due = if (travel != null) minutesTo in leadMin.toLong() - 10..leadMin.toLong() + 10
        else minutesTo in 20L..75L
        if (!due) return@forEach
        val where = e.location?.takeIf { it.isNotBlank() }
        val text = buildString {
            append("Empieza a las ${zoned(e.start).format(HM)}")
            where?.let { append(" en $it") }
            append(". ")
            if (travel != null) append("Con el tráfico de ahora tardas ~$travel min: sal a las ${zoned(e.start - leadMin * 60_000L).format(HM)}.")
            else if (where != null) append("Si tienes que moverte, sal con tiempo.")
            s.weather?.takeIf { it.rainProbNext3h >= 50 }?.let { append(" Lleva paraguas: ${it.rainProbNext3h}% de lluvia.") }
        }
        out += Nudge(
            "evento:${eventKey(e)}", "📅 ${if (travel != null) "Hora de salir" else "En $minutesTo min"}: ${e.title}", text,
            where?.let {
                PendingAction(type = ActionTypes.OPEN, params = mapOf("url" to "https://www.google.com/maps/dir/?api=1&destination=" + URLEncoder.encode(it, "UTF-8")), description = "🗺️ Cómo llegar a $it")
            },
        )
    }

    // 2. Mañana: clima del día y cómo dormiste.
    if (t.hour in 6..10) {
        s.weather?.takeIf { now - it.at < 3 * 3_600_000L }?.let { w ->
            val rain = w.rainProbNext3h >= 50 || w.rainy
            if (rain || w.maxToday >= 33 || w.minToday <= 5) {
                out += Nudge(
                    "clima:$day", "🌦️ Hoy: ${w.description}, ${w.minToday.toInt()}–${w.maxToday.toInt()}°",
                    when {
                        rain -> "Lluvia probable (${w.rainProbNext3h}%). Paraguas y sal un poco antes."
                        w.maxToday >= 33 -> "Va a hacer calor: agua y ropa ligera."
                        else -> "Hace frío: abrígate."
                    },
                )
            }
        }
        val night = s.health.lastNight(now)
        if (night != null && night.minutes < 360) {
            val avg = s.health.averageSleepMinutes()
            out += Nudge(
                "sueño:$day", "😴 Dormiste ${night.minutes / 60}h ${night.minutes % 60}min",
                "Menos de lo normal${if (avg > 0) " (sueles dormir ${avg / 60}h ${avg % 60}min)" else ""}. Tómatelo con calma; si puedes, acuéstate antes.",
            )
        }
    }

    // 3. Acabas de llegar a un sitio donde sueles escuchar algo concreto.
    if (arrivedAt != null && arrivedAt.known) {
        val m = arrivedAt.favoriteMusic() ?: p.music.favoriteAt(t.hour)
        out += Nudge(
            "llegada:${arrivedAt.id}:$day:${t.hour}", "📍 Llegaste a ${arrivedAt.label}",
            m?.let { "¿Pongo $it, como sueles hacer aquí?" } ?: "Aquí estoy si me necesitas.",
            m?.let(::music),
        )
    }

    // 4. Lo que sueles hacer en esta situación (contexto aprendido > rutina simple).
    val guess = predictContext(s, now)
    val (act, why) = when {
        guess != null && guess.probability >= 0.45 -> guess.activity to guess.because
        else -> p.predictNext(now)?.takeIf { it.confidence >= 0.6 }?.let { it.activity to it.reason } ?: (null to null)
    }
    if (act != null && act !in setOf("dormir", "despertar", "casa")) {
        val r = p.routines[act]
        val doneToday = r != null && zoned(r.lastSeen).toLocalDate() == t.toLocalDate()
        if (!doneToday) {
            val m = p.music.favoriteAt(t.hour)
            out += Nudge("rutina:$act:$day", "🔁 Normalmente ahora: $act", "Lo sé porque $why." + (m?.let { " ¿Te pongo $it?" } ?: ""), m?.let(::music))
        }
    }

    // 5. Estrés alto.
    if (s.health.stress == "alto" && t.hour in 8..22) {
        out += Nudge("estrés:$day:${t.hour / 4}", "💓 Tu pulso está alto", "Más alto que tu reposo (${s.health.restingBpm} ppm). Si no estás haciendo ejercicio, respira 2 minutos o sal a caminar.",
            p.music.genres.firstOrNull()?.let { music("música relajante $it") } ?: music("música relajante"))
    }

    // 6. Poco movimiento a media tarde.
    if (t.hour in 17..19 && s.health.stepsAvg > 0 && s.health.stepsToday < s.health.stepsAvg / 2) {
        val nice = s.weather?.let { !it.rainy && it.temperature in 12.0..30.0 } ?: true
        out += Nudge("pasos:$day", "🚶 Hoy te has movido poco", "Llevas ${s.health.stepsToday} pasos (sueles hacer ${s.health.stepsAvg})." + if (nice) " Hace buen tiempo para un paseo." else "")
    }

    // 7. Una misión necesita tu atención.
    s.missions.filter { it.status == "activa" }.forEach { m ->
        val last = m.updates.lastOrNull() ?: return@forEach
        if (now - last.at < 3_600_000L) out += Nudge("misión:${m.id}:${last.at}", "🎯 ${m.goal.take(40)}", last.text.take(240))
    }

    // 8. ARIA M3: un patrón tuyo acaba de activarse (p. ej. dormiste poco → suele subir tu estrés).
    out += patternNudges(s, now, day)

    return out.filter { it.key !in s.nudged }
}

private val NOTABLE = setOf("estrés:alto", "sueño:corto")

/** Avisos cuando ocurre la causa de un patrón y su efecto aún no ha pasado. */
fun patternNudges(s: CookieState, now: Long, day: String): List<Nudge> {
    if (s.patterns.isEmpty()) return emptyList()
    val recent = s.timeline.filter { now - it.at < 12 * 3_600_000L }
        .map { it.at to com.aria.cookie.aria.Patterns.symbol(it) }.filter { it.second.isNotEmpty() }
    val out = mutableListOf<Nudge>()
    for (p in s.patterns) {
        val music = p.effect.startsWith("música:")
        if (p.effect !in NOTABLE && !music) continue
        val cause = recent.lastOrNull { it.second == p.cause } ?: continue
        val limit = minOf(p.windowHours * 3_600_000L, 12 * 3_600_000L)
        if (now - cause.first > limit) continue
        if (recent.any { it.second == p.effect && it.first > cause.first }) continue // ya pasó
        val text = com.aria.cookie.aria.Patterns.describe(p) + "."
        out += if (music) {
            val artist = p.effect.removePrefix("música:")
            Nudge("patrón:${p.cause}:${p.effect}:$day", "🎧 ¿Pongo a $artist?", text,
                PendingAction(type = ActionTypes.PLAY_MUSIC, params = mapOf("consulta" to artist), description = describeAction(ActionTypes.PLAY_MUSIC, mapOf("consulta" to artist))))
        } else {
            Nudge("patrón:${p.cause}:${p.effect}:$day", "🔗 Ojo: ${com.aria.cookie.aria.Patterns.human(p.cause)}",
                "$text Tómatelo con calma hoy y date un respiro si puedes.")
        }
    }
    return out
}
