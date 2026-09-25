package com.aria.cookie.core

import kotlinx.serialization.Serializable
import java.time.LocalDate

// ==================== MOVIMIENTO ====================

private val MOVEMENTS = mapOf(
    "WALKING" to "caminar", "ON_FOOT" to "caminar", "RUNNING" to "correr",
    "ON_BICYCLE" to "bici", "IN_VEHICLE" to "en coche",
)

/** Aprende cómo te mueves (detectado por el teléfono). */
fun learnMovement(s: CookieState, type: String, at: Long): String? {
    val act = MOVEMENTS[type] ?: return null
    s.profile.recordActivity(act, at)
    s.profile.markActive(at)
    s.log(at, "movimiento", act)
    return act
}

// ==================== CON QUIÉN HABLAS ====================

/** Una persona con la que hablas (por las notificaciones de tus apps de mensajes). */
@Serializable
data class Contact(val name: String, var count: Int = 0, var lastAt: Long = 0, val apps: MutableSet<String> = mutableSetOf())

private val NOT_A_PERSON = Regex("(?i)^(\\d+ (mensajes|messages|new)|whatsapp|telegram|gmail|messenger|instagram|tú|you|null)$")

/**
 * Aprende de una notificación de mensaje SIN guardar su texto: solo quién te
 * escribe, desde qué app, cuándo, y los temas (palabras clave) de forma suave.
 */
fun learnNotification(s: CookieState, app: String, sender: String?, text: String?, at: Long) {
    s.profile.markActive(at)
    val who = sender?.substringBefore(":")?.trim()?.take(40)
    if (!who.isNullOrBlank() && !NOT_A_PERSON.matches(who)) {
        val c = s.contacts.getOrPut(who) { Contact(who) }
        c.count++
        c.lastAt = at
        c.apps += app
        if (c.count == 10) s.memories.remember("persona", "Hablo mucho con $who (por $app)", at, "notificaciones", listOf("amigo", "contacto"))
        s.log(at, "mensaje", "mensaje de $who ($app)")
    }
    text?.let { t -> keywords(t).take(5).forEach { s.profile.reinforce(it, "palabra", 0.05, at) } }
    if (s.contacts.size > 300) s.contacts.entries.minByOrNull { it.value.lastAt }?.let { s.contacts.remove(it.key) }
}

fun topContacts(s: CookieState, n: Int = 6) = s.contacts.values.sortedByDescending { it.count }.take(n)

// ==================== FOTOS ====================

@Serializable
data class PhotoInfo(val takenAt: Long, val lat: Double? = null, val lon: Double? = null)

/** Los días en que haces muchas fotos suelen ser especiales: los recuerda. */
fun learnPhotos(s: CookieState, photos: List<PhotoInfo>, now: Long): Int {
    val fresh = photos.filter { it.takenAt > s.lastPhotoSync }
    if (fresh.isEmpty()) return 0
    fresh.groupBy { zoned(it.takenAt).toLocalDate() }.forEach { (day, list) ->
        s.photoDays[day.toString()] = (s.photoDays[day.toString()] ?: 0) + list.size
        s.log(list.first().takenAt, "fotos", "${list.size} fotos")
        val total = s.photoDays[day.toString()]!!
        if (total >= 8 && day != LocalDate.now()) {
            val place = list.firstNotNullOfOrNull { p ->
                if (p.lat == null || p.lon == null) null
                else s.places.firstOrNull { distanceMeters(p.lat, p.lon, it.lat, it.lon) < PLACE_RADIUS_M && it.known }?.label
            }
            s.memories.remember("diario", "El $day fue un día especial: hice $total fotos" + (place?.let { " en $it" } ?: ""), now, "fotos", listOf("recuerdo", "momento", "fotos"))
        }
    }
    s.lastPhotoSync = fresh.maxOf { it.takenAt }
    while (s.photoDays.size > 120) s.photoDays.remove(s.photoDays.keys.minOrNull())
    return fresh.size
}

// ==================== CORAZÓN Y ESTRÉS ====================

@Serializable
data class HeartSample(val at: Long, val bpm: Long)

/**
 * Pulso en reposo y un indicador de estrés: pulso reciente comparado con tu
 * línea base (sube cuando estás tenso, enfermo o después de ejercicio).
 */
fun learnHeart(s: CookieState, samples: List<HeartSample>, now: Long) {
    val h = s.health
    samples.filter { it.at > h.lastHeartAt }.sortedBy { it.at }.forEach {
        h.heart += it
        h.lastHeartAt = it.at
    }
    while (h.heart.size > 2000) h.heart.removeAt(0)
    val week = h.heart.filter { now - it.at < 7 * 86_400_000L }.map { it.bpm }
    if (week.size >= 20) h.restingBpm = week.sorted()[week.size / 10] // percentil 10 ≈ reposo
    val recent = h.heart.filter { now - it.at < 2 * 3_600_000L }.map { it.bpm }
    h.stress = if (recent.size >= 3 && h.restingBpm > 0) {
        val ratio = recent.average() / h.restingBpm
        when {
            ratio > 1.35 -> "alto"
            ratio > 1.15 -> "medio"
            else -> "bajo"
        }
    } else null
}
