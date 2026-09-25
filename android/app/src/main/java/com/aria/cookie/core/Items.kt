package com.aria.cookie.core

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Un hallazgo que Cookie cree que te importa. */
@Serializable
data class Item(
    val id: String,
    var topic: String = "",
    val title: String,
    val url: String = "",
    var summary: String = "",
    var why: String = "",
    val source: String = "",
    val published: Long = 0,
    var foundAt: Long = 0,
    var score: Double = 0.0,
    var delivered: Boolean = false,
    var feedback: Int = 0,
)

/** Tema a investigar con su peso en tu perfil. */
data class Topic(val name: String, val weight: Double, val kind: String)

fun itemId(url: String, title: String): String {
    val key = url.ifEmpty { title.lowercase() }
    return MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        .take(6).joinToString("") { "%02x".format(it) }
}

private val aliases = mapOf(
    "inteligencia artificial" to listOf("ia", "ai", "chatgpt", "claude", "llm"),
    "fórmula 1" to listOf("f1"),
    "formula 1" to listOf("f1"),
    "criptomonedas" to listOf("bitcoin", "cripto"),
    "programación" to listOf("software", "código"),
)

/**
 * Puntúa y filtra: relevancia con tus intereses × peso del interés × frescura.
 * Descarta lo que rechazas, lo ya visto y la misma noticia de otro medio.
 */
fun rank(items: List<Item>, p: Profile, topics: List<Topic>, seen: Map<String, Long>, now: Long): List<Item> {
    val maxW = topics.maxOfOrNull { it.weight }?.takeIf { it > 0 } ?: 1.0
    val out = mutableListOf<Item>()
    val ids = mutableSetOf<String>()
    val kept = mutableListOf<List<String>>()
    for (it in items) {
        if (it.id in seen || it.id in ids || it.title.isBlank()) continue
        val text = "${it.title} ${it.summary} ${it.topic}".lowercase()
        if (p.dislikes.keys.any { d -> containsWord(text, d) }) continue

        var bestTopic = ""
        var bestScore = 0.0
        for (t in topics) {
            var m = matchScore(text, t.name)
            if (t.name == it.topic) m = max(m, 0.6)
            val s = m * (t.weight / maxW)
            if (s > bestScore) { bestTopic = t.name; bestScore = s }
        }
        if (bestScore < 0.15) continue
        val kw = keywords(it.title)
        if (nearDuplicate(kw, kept)) continue

        var fresh = 1.0
        if (it.published > 0) {
            val ageH = (now - it.published) / 3_600_000.0
            if (ageH > 14 * 24) continue
            fresh = exp(-max(ageH, 0.0) / 96)
        }
        it.topic = bestTopic
        it.score = bestScore * (0.4 + 0.6 * fresh)
        if (it.summary.startsWith(it.title)) it.summary = ""
        if (it.why.isEmpty()) it.why = whyText(p, bestTopic)
        if (it.foundAt == 0L) it.foundAt = now
        ids += it.id
        kept += kw
        out += it
    }
    return out.sortedByDescending { it.score }
}

private fun nearDuplicate(kw: List<String>, kept: List<List<String>>): Boolean {
    if (kw.size < 3) return false
    val set = kw.toSet()
    return kept.any { other -> other.count { it in set } > 0.5 * min(kw.size, other.size) }
}

private fun whyText(p: Profile, topic: String): String {
    val i = p.interests[topic] ?: return "relacionado con lo que te interesa"
    return when {
        i.kind == "trabajo" -> "tiene que ver con tu trabajo ($topic)"
        i.kind == "música" -> "escuchas mucho a $topic en Spotify"
        i.kind == "actividad" -> "es algo que haces seguido ($topic)"
        i.mentions > 1 -> "has hablado de $topic ${i.mentions} veces"
        else -> "dijiste que te interesa $topic"
    }
}

/** Fracción de palabras del tema presentes en el texto (con raíz aproximada). */
fun matchScore(text: String, topic: String): Double {
    val words = topic.split(" ").filter { it.isNotEmpty() }
    if (words.isEmpty()) return 0.0
    if (text.contains(topic)) return 1.0
    if (aliases[topic]?.any { containsWord(text, it) } == true) return 1.0
    val hits = words.count { it.length >= 3 && (containsWord(text, it) || containsStem(text, it)) }
    return hits.toDouble() / words.size
}

fun containsWord(text: String, w: String): Boolean =
    Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(w) + "(?![\\p{L}\\p{N}])").containsMatchIn(text)

private fun containsStem(text: String, w: String): Boolean =
    w.length >= 6 && Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(w.take(5))).containsMatchIn(text)
