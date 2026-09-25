package com.aria.cookie.core

import java.time.format.DateTimeFormatter

/** Algo que Cookie se adelanta a decirte, con una acción opcional de un toque. */
data class Nudge(
    val key: String, // para no repetir el mismo aviso
    val title: String,
    val text: String,
    val action: PendingAction? = null,
)

private val HM = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Se anticipa según el contexto: tu agenda, cómo dormiste, dónde acabas de
 * llegar y lo que sueles hacer a esta hora. Solo devuelve avisos nuevos.
 */
fun nudges(s: CookieState, now: Long, arrivedAt: Place? = null): List<Nudge> {
    val out = mutableListOf<Nudge>()
    val t = zoned(now)
    val day = t.toLocalDate().toString()
    val p = s.profile

    // 1. Un evento empieza pronto.
    s.upcoming.filter { !it.allDay && it.start - now in 20 * 60_000L..75 * 60_000L }.forEach { e ->
        val min = (e.start - now) / 60_000
        val where = e.location?.takeIf { it.isNotBlank() }?.let { " en $it" } ?: ""
        out += Nudge(
            "evento:${e.id}:${e.start}", "📅 En $min min: ${e.title}",
            "Empieza a las ${zoned(e.start).format(HM)}$where. Si tienes que moverte, sal con tiempo.",
            e.location?.takeIf { it.isNotBlank() }?.let {
                PendingAction(type = ActionTypes.OPEN, params = mapOf("url" to "https://www.google.com/maps/dir/?api=1&destination=" + java.net.URLEncoder.encode(it, "UTF-8")), description = "🗺️ Cómo llegar a $it")
            },
        )
    }

    // 2. Dormiste poco (aviso de la mañana).
    val night = s.health.lastNight(now)
    if (night != null && t.hour in 6..11 && night.minutes < 360) {
        val avg = s.health.averageSleepMinutes()
        out += Nudge(
            "sueño:$day", "😴 Dormiste ${night.minutes / 60}h ${night.minutes % 60}min",
            "Menos de lo normal${if (avg > 0) " (sueles dormir ${avg / 60}h ${avg % 60}min)" else ""}. " +
                "Hoy tómatelo con calma; si puedes, acuéstate antes.",
        )
    }

    // 3. Acabas de llegar a un sitio donde sueles escuchar algo concreto.
    if (arrivedAt != null && arrivedAt.known) {
        val music = arrivedAt.favoriteMusic() ?: p.music.favoriteAt(t.hour)
        out += Nudge(
            "llegada:${arrivedAt.id}:$day:${t.hour}", "📍 Llegaste a ${arrivedAt.label}",
            music?.let { "¿Pongo $it, como sueles hacer aquí?" } ?: "Aquí estoy si me necesitas.",
            music?.let { PendingAction(type = ActionTypes.PLAY_MUSIC, params = mapOf("consulta" to it), description = describeAction(ActionTypes.PLAY_MUSIC, mapOf("consulta" to it))) },
        )
    }

    // 4. A esta hora sueles hacer algo que hoy aún no has hecho.
    p.predictNext(now)?.takeIf { it.confidence >= 0.6 }?.let { pred ->
        val r = p.routines[pred.activity]
        val doneToday = r != null && zoned(r.lastSeen).toLocalDate() == t.toLocalDate()
        if (!doneToday && pred.activity !in setOf("dormir", "despertar", "casa")) {
            val music = p.music.favoriteAt(t.hour)
            out += Nudge(
                "rutina:${pred.activity}:$day", "🔁 Normalmente ahora: ${pred.activity}",
                "Lo sé porque ${pred.reason}." + (music?.let { " ¿Te pongo $it?" } ?: ""),
                music?.let { PendingAction(type = ActionTypes.PLAY_MUSIC, params = mapOf("consulta" to it), description = describeAction(ActionTypes.PLAY_MUSIC, mapOf("consulta" to it))) },
            )
        }
    }

    // 5. Poco movimiento a media tarde.
    if (t.hour in 17..19 && s.health.stepsAvg > 0 && s.health.stepsToday < s.health.stepsAvg / 2) {
        out += Nudge("pasos:$day", "🚶 Hoy te has movido poco", "Llevas ${s.health.stepsToday} pasos (sueles hacer ${s.health.stepsAvg}). ¿Un paseo corto?")
    }

    return out.filter { it.key !in s.nudged }
}
