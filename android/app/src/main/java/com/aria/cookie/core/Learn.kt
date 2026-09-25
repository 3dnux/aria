package com.aria.cookie.core

/** Algo que Cookie aprendió de una frase. */
data class Signal(val kind: String, val value: String)

private val I = setOf(RegexOption.IGNORE_CASE)
private val reName = Regex("""\b(?:me llamo|mi nombre es)\s+(\p{L}+)""", I)
private val reOccupation = Regex("""\b(?:trabajo como|trabajo de|me dedico a|mi trabajo es|soy de profesión|trabajo en)\s+([^.,;!?]+)""", I)
private val reLocation = Regex("""\b(?:vivo en|soy de)\s+(\p{Lu}\p{L}+(?:\s+\p{Lu}\p{L}+)*)""")
private val reDislike = Regex("""\b(?:no me gustan?|no me interesan?|odio|detesto|no soporto|me aburren?)\s+([^.,;!?]+)""", I)
private val reLike = Regex("""\b(?:me gustan?|me encantan?|me interesan?|me apasionan?|disfruto(?: de)?|soy fan de|sigo mucho|estoy aprendiendo|quiero aprender)\s+([^.,;!?]+)""", I)
private val reActivity = Regex("""\b(?:voy a|voy al|fui a|fui al|estoy en|salgo a|salí a|acabo de|vengo de|vengo del|terminé de|empiezo a|me voy a)\s+(\p{L}+(?:\s+\p{L}+){0,3})""", I)
private val reLikePrefix = Regex("""^(?:me gustan?|me encantan?|me interesan?|me apasionan?|disfruto(?: de)?|soy fan de)\s+""", I)
private val reSplitList = Regex("""\s*(?:,|\by\b|\be\b|\bo\b)\s*""", I)

private val motionVerbs = setOf("llegar", "ir", "salir", "volver", "regresar", "entrar", "pasar", "estar")

val STOPWORDS: Set<String> = """
    a al algo algunas algunos ante antes aquí así aun aunque bien cada casa como cómo con contra cual cuando
    de del desde donde dos el él ella ellas ellos en entre era eran eres es esa ese eso esta está están estar
    este esto estos estoy fue fueron fui gran ha hace hacer hacia han has hasta hay hoy la las le les lo los
    mas más me mi mis mientras mucho muy nada ni no nos nosotros nuestra nuestro o otra otro para pero poco
    por porque que qué quien se sea ser si sí sin sobre solo sólo son soy su sus también tan tanto te tengo
    tiene tienen todo todos tu tus un una uno unos usted va vamos van voy y ya yo ayer mañana tarde noche
    semana días día siempre nunca cosas cosa gusta gustan encanta encantan quiero puedo
    creo estaba estado había bueno buena mejor peor luego ahora después entonces rato vez veces todavía
    the and for with that this from have about your what when just like
    mucha muchas muchos otras otros cuál dónde quién tener tenía ahí allí
    acabo llegar llegué vengo salgo salí toca tocó hice hago estuve termino terminé empiezo
    llamo nombre trabajo vivo dedico interesa gustaría hola gracias dime crees sabes eres
""".trim().split(Regex("\\s+")).toSet()

/** Analiza una frase libre y devuelve las señales que contiene. Funciona sin conexión. */
fun extract(text: String): List<Signal> {
    val out = mutableListOf<Signal>()
    val lower = text.lowercase()

    reName.find(text)?.let { out += Signal("nombre", it.groupValues[1].trim()) }
    reOccupation.find(lower)?.let {
        val job = cutClause(it.groupValues[1]).substringBefore(" y ")
        out += Signal("trabajo", normalizeTopic(job))
    }
    reLocation.find(text)?.let { out += Signal("lugar", it.groupValues[1].trim()) }

    // Rechazos primero: "no me gusta el fútbol" no es un gusto.
    val negSpans = mutableListOf<IntRange>()
    for (m in reDislike.findAll(lower)) {
        negSpans += m.range
        splitList(m.groupValues[1]).forEach { out += Signal("rechazo", it) }
    }
    for (m in reLike.findAll(lower)) {
        if (negSpans.any { m.range.first in it }) continue
        splitList(m.groupValues[1]).forEach { out += Signal("gusto", it) }
    }
    for (m in reActivity.findAll(lower)) {
        activityName(m.groupValues[1])?.let { out += Signal("actividad", it) }
    }

    val seen = out.flatMap { it.value.split(" ") }.toMutableSet()
    for (w in keywords(lower)) {
        if (seen.add(w)) out += Signal("palabra", w)
    }
    return out
}

/** Palabras con contenido (sin stopwords, ≥4 letras). */
fun keywords(text: String): List<String> =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}+#]+"))
        .filter { it.length >= 4 && it !in STOPWORDS }
        .distinct()

private fun splitList(s: String): List<String> =
    cutClause(s).split(reSplitList)
        .map { normalizeTopic(reLikePrefix.replace(it.trim(), "")) }
        .filter { it.isNotEmpty() && it !in STOPWORDS }

/** Corta en conectores que inician otra idea. */
private fun cutClause(s: String): String {
    var r = " $s "
    for (sep in listOf(" pero ", " aunque ", " porque ", " cuando ", " mientras ", " desde hace ", " hace ", " no ", " ni ")) {
        val i = r.indexOf(sep)
        if (i >= 0) r = r.substring(0, i)
    }
    return r.trim()
}

private fun activityName(s: String): String? {
    var words = s.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isNotEmpty() && words.first() in motionVerbs) words = words.drop(1)
    while (words.isNotEmpty() && (isLeadingFiller(words.first()) || words.first() in STOPWORDS)) words = words.drop(1)
    if (words.isEmpty() || words.first() in motionVerbs) return null
    return words.take(2).joinToString(" ")
}
