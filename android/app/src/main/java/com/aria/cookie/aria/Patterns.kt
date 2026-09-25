package com.aria.cookie.aria

import com.aria.cookie.core.TimelineEvent
import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * ARIA M3 · Patrones temporales de tu vida (puerto fiel de internal/temporal).
 *
 * "Cuando pasa A, en las siguientes W horas suele pasar B", comparado con la
 * misma hora del día en los días sin A (control por horario), con cota de
 * Wilson. Mismos resultados que Go sobre testdata/aria/timeline.json.
 */
@Serializable
data class Pattern(
    val cause: String,
    val effect: String,
    val windowHours: Int,
    val count: Int,
    val support: Int,
    val probability: Double,
    val baseline: Double,
    val lift: Double,
    val medianLagHours: Double,
    val score: Double,
)

object Patterns {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    private const val MIN_CONTROLS = 3

    data class Config(
        val windows: List<Int> = listOf(3, 24),
        val minCount: Int = 3,
        val minSupport: Int = 3,
        val minProb: Double = 0.5,
        val minLift: Double = 2.0,
        val maxSymbols: Int = 60,
        val max: Int = 12,
    )

    private val reSleep = Regex("\\((\\d+)h")
    private val reAppMin = Regex("\\s+\\d+ min$")
    private val reMsg = Regex("^mensaje de (.+?)(\\s*\\(.*\\))?$")
    private val rePhoto = Regex("^(\\d+) fotos")

    fun symbol(e: TimelineEvent): String {
        val t = e.text.trim().lowercase()
        return when (e.kind) {
            "lugar" -> "lugar:" + t.removePrefix("llegaste a ")
            "actividad" -> "actividad:$t"
            "movimiento" -> "movimiento:$t"
            "calendario" -> "evento:$t"
            "música" -> "música:$t"
            "estrés" -> "estrés:$t"
            "sueño" -> reSleep.find(t)?.let { if (it.groupValues[1].toInt() < 6) "sueño:corto" else "sueño:normal" } ?: ""
            "app" -> "app:" + reAppMin.replace(t, "")
            "mensaje" -> reMsg.find(t)?.let { "mensaje:" + it.groupValues[1].trim() } ?: ""
            "fotos" -> rePhoto.find(t)?.let { if (it.groupValues[1].toInt() >= 8) "fotos:muchas" else "" } ?: ""
            else -> ""
        }
    }

    fun mine(events: List<TimelineEvent>, cfg: Config = Config()): List<Pattern> {
        val occ = mutableMapOf<String, MutableList<Long>>()
        var first = 0L
        var last = 0L
        for (e in events) {
            val s = symbol(e)
            if (s.isEmpty()) continue
            occ.getOrPut(s) { mutableListOf() } += e.at
            if (first == 0L || e.at < first) first = e.at
            if (e.at > last) last = e.at
        }
        if (occ.size < 2 || last - first < 2 * DAY) return emptyList()
        val clean = occ.mapValues { (_, ts) -> dedupe(ts.sorted(), 30 * 60_000L) }
        val symbols = clean.keys.sortedWith(compareByDescending<String> { clean[it]!!.size }.thenBy { it }).take(cfg.maxSymbols)

        val best = linkedMapOf<Pair<String, String>, Pattern>()
        for (w in cfg.windows) {
            val wMs = w * HOUR
            for (a in symbols) {
                val occA = clean[a]!!
                if (occA.size < cfg.minCount) continue
                val controls = controlTimes(occA, first, last)
                if (controls.size < MIN_CONTROLS) continue
                for (b in symbols) {
                    if (a == b) continue
                    val (support, lags) = followed(occA, clean[b]!!, wMs)
                    if (support < cfg.minSupport) continue
                    val n = occA.size
                    val p = support.toDouble() / n
                    val ctrlHits = followed(controls, clean[b]!!, wMs).first
                    val base = (ctrlHits + 0.5) / (controls.size + 1)
                    val lift = p / base
                    if (p < cfg.minProb || lift < cfg.minLift) continue
                    val score = wilsonLower(support, n) * (ln(lift) / ln(2.0))
                    val key = a to b
                    val old = best[key]
                    if (old != null && old.score >= score) continue
                    best[key] = Pattern(a, b, w, n, support, p, base, lift, median(lags) / HOUR, score)
                }
            }
        }
        return best.values.sortedWith(compareByDescending<Pattern> { it.score }.thenBy { it.cause + it.effect }).take(cfg.max)
    }

    private fun followed(xs: List<Long>, ys: List<Long>, w: Long): Pair<Int, List<Double>> {
        var support = 0
        val lags = mutableListOf<Double>()
        for (a in xs) {
            val i = upperBound(ys, a)
            if (i < ys.size && ys[i] - a <= w) { support++; lags += (ys[i] - a).toDouble() }
        }
        return support to lags
    }

    private fun controlTimes(xs: List<Long>, first: Long, last: Long): List<Long> {
        val out = mutableListOf<Long>()
        for (a in xs) {
            var t = a - ((a - first) / DAY) * DAY
            while (t <= last) {
                if (t != a && !near(xs, t, 2 * HOUR)) out += t
                t += DAY
            }
        }
        return out.sorted()
    }

    private fun near(ts: List<Long>, t: Long, d: Long): Boolean {
        val i = lowerBound(ts, t - d)
        return i < ts.size && ts[i] <= t + d
    }

    /** Primer índice con valor > x. */
    private fun upperBound(xs: List<Long>, x: Long): Int {
        var lo = 0; var hi = xs.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (xs[m] > x) hi = m else lo = m + 1 }
        return lo
    }

    /** Primer índice con valor ≥ x. */
    private fun lowerBound(xs: List<Long>, x: Long): Int {
        var lo = 0; var hi = xs.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (xs[m] >= x) hi = m else lo = m + 1 }
        return lo
    }

    private fun dedupe(ts: List<Long>, gap: Long): List<Long> {
        val out = mutableListOf<Long>()
        for (t in ts) if (out.isEmpty() || t - out.last() >= gap) out += t
        return out
    }

    private fun median(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.sorted()[xs.size / 2]

    private fun wilsonLower(k: Int, n: Int): Double {
        if (n == 0) return 0.0
        val z = 1.645
        val p = k.toDouble() / n
        val nn = n.toDouble()
        val den = 1 + z * z / nn
        val center = p + z * z / (2 * nn)
        val margin = z * sqrt(p * (1 - p) / nn + z * z / (4 * nn * nn))
        return (center - margin) / den
    }

    fun describe(p: Pattern): String {
        val whenTxt = if (p.windowHours >= 24) "en el día siguiente" else "en las horas siguientes"
        val strength = if (p.lift > 10) "mucho más de lo normal" else "%.1f× más de lo normal".format(java.util.Locale.US, p.lift)
        return "Cuando ${human(p.cause)}, ${human(p.effect)} $whenTxt (${p.support} de ${p.count} veces; $strength)"
    }

    fun human(sym: String): String {
        val kind = sym.substringBefore(':')
        val v = sym.substringAfter(':')
        return when (kind) {
            "lugar" -> "vas a $v"
            "actividad" -> "toca $v"
            "movimiento" -> mapOf("caminar" to "caminas", "correr" to "corres", "bici" to "vas en bici", "en coche" to "vas en coche")[v] ?: v
            "evento" -> "tienes «$v»"
            "música" -> "escuchas a $v"
            "estrés" -> "tu estrés está $v"
            "sueño" -> if (v == "corto") "duermes poco" else "duermes bien"
            "app" -> "usas $v"
            "mensaje" -> "hablas con $v"
            "fotos" -> "haces muchas fotos"
            else -> sym
        }
    }
}
