package com.aria.cookie.aria

import kotlin.math.sqrt

/**
 * ARIA M3 · Red de conceptos con activación propagada (puerto de internal/memory).
 *
 * Los conceptos que aparecen juntos en tus recuerdos quedan conectados; al
 * buscar, la activación se propaga a sus vecinos para encontrar recuerdos por
 * asociación ("maratón" → entrenar, dormir, rodilla).
 */
class ConceptGraph {
    private val edges = mutableMapOf<String, MutableMap<String, Double>>()

    val size get() = edges.size

    fun addDocument(concepts: Collection<String>, weight: Double = 1.0) {
        val uniq = concepts.filter { it.isNotEmpty() }.distinct()
        if (uniq.size < 2) return
        val w = weight / (uniq.size - 1)
        for (i in uniq.indices) for (j in i + 1 until uniq.size) {
            add(uniq[i], uniq[j], w)
            add(uniq[j], uniq[i], w)
        }
    }

    private fun add(a: String, b: String, w: Double) {
        val m = edges.getOrPut(a) { mutableMapOf() }
        m[b] = (m[b] ?: 0.0) + w
    }

    data class Activation(val concept: String, val level: Double)

    /** Conceptos nuevos activados, con nivel relativo (1 = el más activado) ≥ [minLevel]. */
    fun spread(seeds: Collection<String>, hops: Int = 2, decay: Double = 0.5, minLevel: Double = 0.2): List<Activation> {
        val act = mutableMapOf<String, Double>()
        var frontier = mutableMapOf<String, Double>()
        for (s in seeds.distinct()) if (s in edges) { act[s] = 1.0; frontier[s] = 1.0 }
        repeat(hops) {
            if (frontier.isEmpty()) return@repeat
            val next = mutableMapOf<String, Double>()
            for ((node, level) in frontier) {
                val nbs = edges[node] ?: continue
                val total = nbs.values.sum()
                if (total == 0.0) continue
                for ((nb, w) in nbs) {
                    val gain = level * decay * w / total / sqrt((edges[nb]?.size ?: 1).toDouble())
                    if (gain > (next[nb] ?: 0.0)) next[nb] = gain
                }
            }
            frontier = mutableMapOf()
            for ((n, l) in next) if (l > (act[n] ?: 0.0)) { act[n] = l; frontier[n] = l }
        }
        val seedSet = seeds.toSet()
        val top = act.filterKeys { it !in seedSet }.values.maxOrNull() ?: return emptyList()
        return act.filter { (c, l) -> c !in seedSet && l / top >= minLevel }
            .map { Activation(it.key, it.value / top) }
            .sortedWith(compareByDescending<Activation> { it.level }.thenBy { it.concept })
    }
}
