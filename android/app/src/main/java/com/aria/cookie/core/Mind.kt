package com.aria.cookie.core

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId

// ==================== SIGNIFICADO (sin conexión) ====================

/** Sin tildes y en minúsculas. */
fun fold(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** Raíz aproximada en español ("corriendo", "correr", "corrí" → "corr"). */
fun stem(word: String): String {
    var w = fold(word)
    if (w.length <= 4) return w
    for (suf in listOf("amientos", "imientos", "amiento", "imiento", "aciones", "iciones", "acion", "icion", "mente",
        "ando", "iendo", "ados", "idos", "adas", "idas", "ador", "edor", "idor", "ar", "er", "ir",
        "ado", "ido", "ada", "ida", "aba", "ia", "es", "as", "os", "a", "o", "e", "s")) {
        if (w.endsWith(suf) && w.length - suf.length >= 3) { w = w.dropLast(suf.length); break }
    }
    return w
}

/** Grupos de palabras que significan lo mismo (se amplía con las etiquetas de Claude). */
private val SYNONYMS: List<Set<String>> = listOf(
    setOf("mama", "madre", "mami", "jefa"), setOf("papa", "padre", "papi"),
    setOf("novia", "novio", "pareja", "esposa", "esposo", "mujer", "marido"),
    setOf("hermano", "hermana", "hermanos"), setOf("hijo", "hija", "hijos", "nino", "nina"),
    setOf("amigo", "amiga", "cuate", "compa", "pana", "colega"),
    setOf("trabajo", "chamba", "curro", "empleo", "oficina", "jefe"),
    setOf("gimnasio", "gym", "gimnasia", "pesas", "entrenar", "entreno"),
    setOf("correr", "running", "maraton", "trotar", "carrera"),
    setOf("dinero", "lana", "plata", "pasta", "ahorro", "sueldo"),
    setOf("comida", "comer", "cena", "cenar", "almuerzo", "desayuno"),
    setOf("musica", "cancion", "canciones", "escuchar", "spotify", "playlist"),
    setOf("dormir", "sueno", "cansado", "cansada", "desvelo", "insomnio"),
    setOf("triste", "bajon", "deprimido", "agobiado", "estres", "ansiedad", "preocupado"),
    setOf("feliz", "contento", "alegre", "emocionado"),
    setOf("viaje", "viajar", "vacaciones", "vuelo"),
    setOf("medico", "doctor", "salud", "enfermo", "dentista"),
    setOf("estudiar", "escuela", "universidad", "clase", "examen", "curso"),
)
private val SYN_INDEX: Map<String, Set<String>> = SYNONYMS.flatMap { g -> g.map { stem(it) to g.map(::stem).toSet() } }
    .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.flatten().toSet() }

/** Conceptos de un texto: raíces de sus palabras clave más sinónimos. */
fun concepts(text: String): Set<String> {
    val stems = keywords(fold(text)).map(::stem).filter { it.length >= 3 }
    return (stems + stems.flatMap { SYN_INDEX[it].orEmpty() }).toSet()
}

/** Parecido de letras (0..1) para tolerar faltas y variantes. */
fun trigramSimilarity(a: String, b: String): Double {
    fun grams(s: String) = " $s ".windowed(3).toSet()
    val ga = grams(a)
    val gb = grams(b)
    if (ga.isEmpty() || gb.isEmpty()) return 0.0
    return ga.intersect(gb).size.toDouble() / ga.union(gb).size
}

// ==================== LÍNEA DE TIEMPO ====================

/** Algo que pasó en tu vida y Cookie notó (la base del diario). */
@Serializable
data class TimelineEvent(val at: Long, val kind: String, val text: String)

private const val MAX_TIMELINE = 3000

fun CookieState.log(at: Long, kind: String, text: String) {
    if (text.isBlank()) return
    val last = timeline.lastOrNull()
    if (last != null && last.kind == kind && last.text == text && at - last.at < 3_600_000) return
    timeline += TimelineEvent(at, kind, text.take(200))
    if (timeline.size > MAX_TIMELINE) timeline.subList(0, timeline.size - MAX_TIMELINE).clear()
}

fun CookieState.dayEvents(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<TimelineEvent> {
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return timeline.filter { it.at in start until end }
}

// ==================== DIARIO ====================

/** Resumen de un día de tu vida escrito por tu copia. */
@Serializable
data class DayEntry(val date: String, val summary: String, val mood: String = "", val highlights: List<String> = emptyList())

@Serializable
data class DayDigest(val summary: String = "", val mood: String = "", val highlights: List<String> = emptyList())

/** Texto con lo que pasó un día, para que Claude escriba el diario. */
fun dayContext(s: CookieState, date: LocalDate): String = buildString {
    val events = s.dayEvents(date)
    appendLine("Fecha: $date")
    events.forEach { appendLine("${zoned(it.at).toLocalTime().toString().take(5)} [${it.kind}] ${it.text}") }
    s.health.sleep.lastOrNull { zoned(it.end).toLocalDate() == date }?.let { appendLine("Durmió ${it.minutes / 60}h ${it.minutes % 60}min") }
}

fun hasDayMaterial(s: CookieState, date: LocalDate) = s.dayEvents(date).size >= 3

// ==================== QUIÉN SOY ====================

/** El retrato que tu copia hace de ti; se reescribe cada semana y guarda su historia. */
@Serializable
data class SelfModel(
    val summary: String = "",
    val values: List<String> = emptyList(),
    val decisionStyle: String = "",
    val communication: String = "",
    val worries: List<String> = emptyList(),
    val goals: List<String> = emptyList(),
    val changes: String = "", // cómo has cambiado desde el retrato anterior
    val at: Long = 0,
)

const val REFLECTION_EVERY_MS = 7L * 24 * 3600 * 1000

/** Material para reflexionar: recuerdos importantes, diario reciente y retrato anterior. */
fun reflectionContext(s: CookieState, now: Long): String = buildString {
    appendLine("PERFIL")
    append(describeProfile(s.profile, now))
    appendLine("\nRECUERDOS (los más importantes)")
    s.memories.sortedByDescending { it.score(now) }.take(120).forEach { appendLine("- [${it.kind}] ${it.text}") }
    if (s.days.isNotEmpty()) {
        appendLine("\nDIARIO RECIENTE")
        s.days.takeLast(14).forEach { appendLine("- ${it.date}: ${it.summary} ${if (it.mood.isNotBlank()) "(ánimo: ${it.mood})" else ""}") }
    }
    s.quiz.takeLast(15).takeIf { it.isNotEmpty() }?.let { q ->
        appendLine("\nRESPUESTAS REALES A '¿QUÉ HARÍAS?'")
        q.forEach { appendLine("- ${it.question} → ${it.answer}") }
    }
    s.selfModels.lastOrNull()?.let {
        appendLine("\nRETRATO ANTERIOR (${zoned(it.at).toLocalDate()})")
        appendLine(it.summary)
    }
}
