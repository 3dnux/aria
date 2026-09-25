package com.aria.cookie.core

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.exp

/**
 * Un recuerdo: algo concreto que Cookie sabe de ti ("mi hermana se llama Laura",
 * "estoy preparando un maratón", "hoy estaba agobiado por el trabajo").
 */
@Serializable
data class Memory(
    val id: String = UUID.randomUUID().toString().take(8),
    val kind: String, // hecho, persona, gusto, meta, ánimo, rutina, decisión, estilo
    var text: String,
    var weight: Double = 1.0,
    val createdAt: Long,
    var lastSeen: Long,
    var mentions: Int = 1,
    val source: String = "chat", // chat, spotify, calendario, lugar, salud, apps, prueba
)

val MEMORY_KINDS = listOf("hecho", "persona", "gusto", "meta", "ánimo", "rutina", "decisión", "estilo")

private const val MAX_MEMORIES = 500

/** Lo que Claude sacó de una conversación. */
@Serializable
data class MemoryDigest(
    val name: String? = null,
    val occupation: String? = null,
    val location: String? = null,
    val likes: List<String> = emptyList(),
    val dislikes: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val memories: List<MemoryDraft> = emptyList(),
)

@Serializable
data class MemoryDraft(val kind: String = "hecho", val text: String = "")

/**
 * Guarda un recuerdo; si ya existe uno casi igual del mismo tipo lo refuerza
 * en lugar de duplicarlo. Devuelve el recuerdo resultante.
 */
fun MutableList<Memory>.remember(kind: String, text: String, now: Long, source: String = "chat"): Memory? {
    val clean = text.trim().trimEnd('.')
    if (clean.length < 3) return null
    val k = if (kind in MEMORY_KINDS) kind else "hecho"
    val kw = keywords(clean).toSet()
    val same = firstOrNull { it.kind == k && similarity(kw, keywords(it.text).toSet()) >= 0.6 }
    if (same != null) {
        same.mentions++
        same.lastSeen = now
        same.weight += 0.5
        if (clean.length > same.text.length) same.text = clean // la versión más completa gana
        return same
    }
    val m = Memory(kind = k, text = clean, createdAt = now, lastSeen = now, source = source)
    add(m)
    if (size > MAX_MEMORIES) {
        // Olvida lo menos importante (como una memoria humana).
        sortByDescending { it.score(now) }
        subList(MAX_MEMORIES, size).clear()
    }
    return m
}

/** Importancia actual: peso × menciones, con decaimiento suave (vida media ~90 días). */
fun Memory.score(now: Long): Double {
    val days = (now - lastSeen).coerceAtLeast(0) / 86_400_000.0
    val durable = kind == "persona" || kind == "hecho" || kind == "estilo"
    return weight * (1 + mentions * 0.3) * if (durable) 1.0 else exp(-days / 130)
}

/** Recuerdos relevantes para una pregunta (palabras en común + importancia). */
fun List<Memory>.relevant(query: String, now: Long, n: Int = 25): List<Memory> {
    val q = keywords(query).toSet()
    return sortedByDescending { m ->
        val overlap = keywords(m.text).count { it in q }
        overlap * 2.0 + m.score(now)
    }.take(n)
}

private fun similarity(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    return a.intersect(b).size.toDouble() / minOf(a.size, b.size)
}

/** Aplica lo que Claude entendió de una conversación al perfil y a la memoria. */
fun applyDigest(s: CookieState, d: MemoryDigest, now: Long) {
    val p = s.profile
    d.name?.takeIf { it.isNotBlank() }?.let { p.name = it.trim() }
    d.occupation?.takeIf { it.isNotBlank() }?.let { p.occupation = it.trim(); p.reinforce(it, "trabajo", 3.0, now) }
    d.location?.takeIf { it.isNotBlank() }?.let { p.location = it.trim() }
    d.likes.forEach { p.reinforce(it, "gusto", 2.0, now) }
    d.dislikes.forEach { p.dislike(it, now) }
    d.activities.forEach { p.recordActivity(it, now); p.reinforce(it, "actividad", 1.0, now) }
    d.memories.forEach { s.memories.remember(it.kind, it.text, now) }
}
