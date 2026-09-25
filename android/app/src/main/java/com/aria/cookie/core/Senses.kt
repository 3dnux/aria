package com.aria.cookie.core

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ==================== LUGARES ====================

/**
 * Un sitio donde pasas tiempo. No se guarda tu recorrido: solo el centro
 * aproximado de cada lugar frecuente y a qué horas estás ahí.
 */
@Serializable
data class Place(
    val id: String,
    var lat: Double,
    var lon: Double,
    var label: String,
    var custom: Boolean = false,
    val hourCounts: MutableList<Int> = MutableList(24) { 0 },
    val dayCounts: MutableList<Int> = MutableList(7) { 0 },
    var samples: Int = 0,
    var visits: Int = 0,
    var lastSeen: Long = 0,
    /** artista → veces que sonaba mientras estabas aquí */
    val music: MutableMap<String, Int> = mutableMapOf(),
) {
    fun favoriteMusic(): String? = music.maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.key
    val known get() = custom || label == "casa" || label == "trabajo"
}

/** Resultado de observar tu ubicación. */
data class PlaceObservation(val place: Place, val arrived: Boolean)

const val PLACE_RADIUS_M = 150.0

fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

/** Aprende de una muestra de ubicación: agrupa en lugares y detecta llegadas. */
fun observeLocation(s: CookieState, lat: Double, lon: Double, now: Long): PlaceObservation {
    val t = zoned(now)
    val place = s.places.minByOrNull { distanceMeters(lat, lon, it.lat, it.lon) }
        ?.takeIf { distanceMeters(lat, lon, it.lat, it.lon) <= PLACE_RADIUS_M }
        ?: Place(id = "p${s.places.size + 1}_${now % 100000}", lat = lat, lon = lon, label = "lugar ${s.places.size + 1}")
            .also { s.places += it }

    // El centro se mueve poco a poco hacia donde realmente estás.
    val n = place.samples.coerceAtMost(50) + 1
    place.lat += (lat - place.lat) / n
    place.lon += (lon - place.lon) / n
    place.samples++
    place.hourCounts[t.hour]++
    place.dayCounts[t.dayOfWeek.value % 7]++
    place.lastSeen = now
    s.profile.music.nowPlaying?.substringAfter(" — ", "")?.takeIf { it.isNotBlank() }?.let {
        place.music[it] = (place.music[it] ?: 0) + 1
    }
    if (!place.custom) autoLabel(s, place)

    val arrived = s.currentPlaceId != place.id
    if (arrived) {
        place.visits++
        s.currentPlaceId = place.id
        if (place.known) s.profile.recordActivity(place.label, now)
    }
    s.profile.markActive(now)
    return PlaceObservation(place, arrived)
}

/** "casa" = donde estás de noche; "trabajo" = entre semana de 9 a 18. */
private fun autoLabel(s: CookieState, p: Place) {
    if (p.samples < 6) return
    val night = (listOf(22, 23) + (0..6)).sumOf { p.hourCounts[it] }.toDouble() / p.samples
    val office = (9..18).sumOf { p.hourCounts[it] }.toDouble() / p.samples
    val weekdays = (1..5).sumOf { p.dayCounts[it] }.toDouble() / p.dayCounts.sum().coerceAtLeast(1)
    val label = when {
        night > 0.5 && s.places.none { it !== p && it.label == "casa" } -> "casa"
        office > 0.6 && weekdays > 0.7 && s.places.none { it !== p && it.label == "trabajo" } -> "trabajo"
        else -> return
    }
    p.label = label
}

fun currentPlace(s: CookieState): Place? = s.places.firstOrNull { it.id == s.currentPlaceId }

// ==================== CALENDARIO ====================

@Serializable
data class CalendarEvent(
    val id: Long,
    val title: String,
    val start: Long,
    val end: Long,
    val location: String? = null,
    val allDay: Boolean = false,
)

/**
 * Aprende de tu agenda: los eventos pasados enseñan rutinas (lo que se
 * repite sube de peso) y los próximos sirven para anticiparse.
 */
fun learnCalendar(s: CookieState, events: List<CalendarEvent>, now: Long): Int {
    var learned = 0
    for (e in events.sortedBy { it.start }) {
        if (e.start > now || e.allDay) continue
        val key = "${e.id}@${e.start}"
        if (key in s.calendarLearned) continue
        s.calendarLearned += key
        s.profile.recordActivity(e.title, e.start)
        keywords(e.title).forEach { s.profile.reinforce(it, "palabra", 0.3, e.start) }
        learned++
    }
    while (s.calendarLearned.size > 1000) s.calendarLearned.removeAt(0)
    s.upcoming.clear()
    s.upcoming += events.filter { it.end >= now }.sortedBy { it.start }.take(30)
    return learned
}

// ==================== SALUD ====================

@Serializable
data class SleepNight(val start: Long, val end: Long) {
    val minutes get() = ((end - start) / 60_000).toInt()
}

@Serializable
data class Health(
    val sleep: MutableList<SleepNight> = mutableListOf(),
    var stepsToday: Long = 0,
    var stepsAvg: Long = 0,
    var lastSync: Long = 0,
) {
    fun lastNight(now: Long): SleepNight? = sleep.lastOrNull { now - it.end < 18 * 3_600_000L }
    fun averageSleepMinutes(): Int = sleep.takeLast(14).map { it.minutes }.average().takeIf { !it.isNaN() }?.toInt() ?: 0
}

fun learnSleep(s: CookieState, nights: List<SleepNight>, stepsToday: Long?, stepsAvg: Long?, now: Long): Int {
    var learned = 0
    for (n in nights.sortedBy { it.start }) {
        if (n.minutes < 60 || s.health.sleep.any { it.start == n.start }) continue
        s.health.sleep += n
        s.profile.recordActivity("dormir", n.start)
        s.profile.recordActivity("despertar", n.end)
        learned++
    }
    while (s.health.sleep.size > 60) s.health.sleep.removeAt(0)
    stepsToday?.let { s.health.stepsToday = it }
    stepsAvg?.let { s.health.stepsAvg = it }
    s.health.lastSync = now
    return learned
}

// ==================== USO DE APPS ====================

@Serializable
data class AppSession(val pkg: String, val label: String, val category: String?, val start: Long, val end: Long)

@Serializable
data class AppStat(
    val label: String,
    var minutes: Double = 0.0, // media móvil diaria aproximada
    val hourMinutes: MutableList<Int> = MutableList(24) { 0 },
    var category: String? = null,
    var lastUsed: Long = 0,
)

/** Qué apps usas y a qué hora: señal fuerte de tu ritmo y tus intereses. */
fun learnApps(s: CookieState, sessions: List<AppSession>, now: Long): Int {
    var learned = 0
    val activeHours = mutableSetOf<Long>()
    for (x in sessions.sortedBy { it.start }) {
        if (x.start <= s.lastUsageSync || x.end <= x.start) continue
        val min = ((x.end - x.start) / 60_000.0)
        if (min < 0.5) continue
        val stat = s.apps.getOrPut(x.pkg) { AppStat(x.label) }
        stat.minutes = stat.minutes * 0.97 + min
        stat.hourMinutes[zoned(x.start).hour] += min.toInt().coerceAtLeast(1)
        stat.category = x.category ?: stat.category
        stat.lastUsed = x.end
        activeHours += x.start / 3_600_000
        learned++
    }
    activeHours.forEach { s.profile.markActive(it * 3_600_000 + 1_800_000) }
    sessions.maxOfOrNull { it.end }?.let { if (it > s.lastUsageSync) s.lastUsageSync = it }
    // Las categorías que más usas se vuelven intereses suaves.
    s.apps.values.filter { it.category != null && it.minutes > 60 }
        .groupBy { it.category!! }
        .forEach { (cat, list) -> s.profile.reinforce(cat, "apps", 0.1 * list.size, now) }
    return learned
}

fun topApps(s: CookieState, n: Int = 5) = s.apps.values.sortedByDescending { it.minutes }.take(n)
