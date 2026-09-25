package com.aria.cookie.aria

import kotlin.math.ln
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ARIA M1 · Enrutador de cómputo adaptativo (puerto fiel de internal/liquid).
 *
 * Decide si un mensaje se responde en el teléfono (sin IA), o con Claude y con
 * qué esfuerzo, y si necesita buscar en la web. Comparte casos de prueba con la
 * versión Go (testdata/aria/router_cases.json) para dar exactamente lo mismo.
 */
object Router {
    enum class Tier { LOCAL, CLAUDE }
    enum class Effort { LOW, MEDIUM, HIGH }

    data class Route(
        val tier: Tier,
        val intent: String = "",
        val effort: Effort = Effort.LOW,
        val webSearches: Int = 0,
        val complexity: Double = 0.0,
        val reason: String = "",
    )

    private val I = setOf(RegexOption.IGNORE_CASE)
    private const val WB = "(?:^|[^\\p{L}\\p{N}])"
    private const val WE = "(?:[^\\p{L}\\p{N}]|$)"

    private val localIntents = listOf(
        "agenda" to Regex("(qu[eé] tengo (hoy|mañana|ahora|despu[eé]s)|pr[oó]xim[oa] (evento|reuni[oó]n|cita)|mi agenda|a qu[eé] hora es mi)", I),
        "rutina" to Regex("(qu[eé] (suelo|acostumbro|toca) (hacer)?|qu[eé] hago normalmente|qu[eé] me toca)", I),
        "musica" to Regex("(qu[eé] (suelo escuchar|escucho|estoy escuchando)|mi m[uú]sica|qu[eé] (canci[oó]n|artista) (escucho|me gusta m[aá]s))", I),
        "lugar" to Regex("(d[oó]nde estoy|en qu[eé] lugar estoy)", I),
        "sueno" to Regex("(c[oó]mo dorm[ií]|cu[aá]nto dorm[ií]|mi sue[nñ]o)", I),
        "clima" to Regex("^(\\W*)(qu[eé] (tiempo|clima) hace|va a llover|hace (fr[ií]o|calor))\\W*$", I),
        "pasos" to Regex("(cu[aá]ntos pasos|me he movido)", I),
    )
    private val reWeb = Regex(WB + "(noticias?|[uú]ltim[oa]s?|hoy en|actual(es|mente)?|precio|cu[aá]nto cuesta|resultado|marcador|estreno|concierto|gira|lanz(a|ó|amiento)|qui[eé]n gan[oó]|20[2-3]\\d|busca(r|me)?|investiga|partido|cu[aá]ndo (es|juega|sale|abre|empieza|estrena))" + WE, I)
    private val reDeep = Regex(WB + "(por qu[eé]|planifica|plan de|estrategia|ay[uú]dame a (decidir|pensar|organizar)|compara|pros y contras|analiza|deber[ií]a|qu[eé] har[ií]as|consejo|reflexiona)" + WE, I)
    private val reAction = Regex("^\\W*(pon|ponme|recu[eé]rdame|av[ií]same|crea|agenda|enciende|apaga|abre|sube|baja|pausa|alarma)$WE", I)

    fun route(message: String): Route {
        val raw = message.trim()
        val (complexity, words) = analyze(raw)
        val msg = raw.lowercase()
        val c = complexity.coerceIn(0.0, 1.0)

        if (words <= 12 && !reDeep.containsMatchIn(msg)) {
            localIntents.firstOrNull { it.second.containsMatchIn(msg) }?.let { (name, _) ->
                return Route(Tier.LOCAL, name, Effort.LOW, 0, c, "pregunta sobre ti ($name): la respondo con lo que ya sé")
            }
        }
        var (effort, reason) = when {
            reAction.containsMatchIn(msg) && words <= 20 -> Effort.LOW to "orden directa: poco razonamiento"
            reDeep.containsMatchIn(msg) || c >= 0.6 || words > 60 -> Effort.HIGH to "pide razonar o decidir"
            c >= 0.3 || words > 20 -> Effort.MEDIUM to "conversación normal"
            else -> Effort.LOW to "mensaje sencillo"
        }
        var web = 0
        if (reWeb.containsMatchIn(msg)) {
            web = if (effort == Effort.HIGH) 5 else 3
            reason += " + necesita datos actuales de la web"
        }
        return Route(Tier.CLAUDE, "", effort, web, c, reason)
    }

    // ---------- Analizador de texto de ARIA (topology.go) ----------

    /** Devuelve (puntuación de complejidad 0..1, número de palabras). */
    fun analyze(query: String): Pair<Double, Int> {
        val words = tokenize(query)
        val chars = query.codePointCount(0, query.length)
        val entropy = entropy(words)
        val zipf = zipfDeviation(words)
        val syntactic = syntacticDepth(query)
        val density = if (chars > 0) words.size.toDouble() / chars * entropy else 0.0
        val punct = query.count { isPunct(it) }.toDouble() / max(chars, 1)
        val hasNumbers = Regex("\\d").containsMatchIn(query)
        val hasSpecial = Regex("[+\\-*/=<>{}\\[\\]()@#$%^&*]").containsMatchIn(query)

        var score = entropy * 0.25 + zipf * 0.20 + syntactic * 0.20
        if (density > 0.5) score += 0.15
        if (punct > 0.1) score += 0.10
        if (hasNumbers) score += 0.05
        if (hasSpecial) score += 0.05
        if (words.size <= 3) score *= 0.3 else if (words.size >= 30) score = min(score * 1.2, 1.0)
        return min(max(score, 0.0), 1.0) to words.size
    }

    private fun isPunct(c: Char) = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    private fun tokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp) || Character.isDigit(cp)) cur.appendCodePoint(Character.toLowerCase(cp))
            else if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
            i += Character.charCount(cp)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)

    private fun entropy(words: List<String>): Double {
        if (words.isEmpty()) return 0.0
        val total = words.size.toDouble()
        var h = 0.0
        words.groupingBy { it }.eachCount().values.forEach { c -> val p = c / total; h -= p * log2(p) }
        val maxH = log2(total)
        return if (maxH > 0) h / maxH else 0.0
    }

    private fun zipfDeviation(words: List<String>): Double {
        if (words.size < 10) return 0.5
        val freqs = words.groupingBy { it }.eachCount().values.sortedDescending()
        var dev = 0.0
        freqs.take(10).forEachIndexed { i, f ->
            val expected = words.size.toDouble() / (i + 1) / 10
            dev += abs(f - expected) / max(expected, 1.0)
        }
        return min(dev / 10.0, 1.0)
    }

    private fun syntacticDepth(q: String): Double {
        var depth = 0
        var maxDepth = 0
        for (c in q) when (c) {
            '(', '[', '{' -> { depth++; maxDepth = max(maxDepth, depth) }
            ')', ']', '}' -> depth--
        }
        val sentences = Regex("[.!?]\\s+[A-ZÁÉÍÓÚÀÈÌÒÙ]").findAll(q).count() + 1
        return min(maxDepth * 0.3 + (sentences - 1) * 0.1, 1.0)
    }
}
