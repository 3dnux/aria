package com.aria.cookie.core

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Algo que tu copia quiere hacer por ti. Nunca se ejecuta sin que lo
 * confirmes (salvo que tú mismo lo pidas desde una notificación).
 */
@Serializable
data class PendingAction(
    val id: String = UUID.randomUUID().toString().take(8),
    val type: String,
    val params: Map<String, String>,
    val description: String,
    var status: String = "pendiente", // pendiente, hecha, cancelada, error
    var result: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Tipos de acción que la copia puede proponer. */
object ActionTypes {
    const val PLAY_MUSIC = "poner_musica"
    const val ALARM = "poner_alarma"
    const val REMINDER = "recordatorio"
    const val EVENT = "crear_evento"
    const val MESSAGE = "borrador_mensaje"
    const val HOME = "casa_inteligente"
    const val OPEN = "abrir_enlace"
}

/** Descripción legible de una acción para mostrarla antes de confirmar. */
fun describeAction(type: String, p: Map<String, String>): String = when (type) {
    ActionTypes.PLAY_MUSIC -> "🎧 Poner «${p["consulta"]}» en Spotify"
    ActionTypes.ALARM -> "⏰ Alarma a las %s:%s%s".format(p["hora"], p["minuto"]?.padStart(2, '0') ?: "00", p["etiqueta"]?.let { " — $it" } ?: "")
    ActionTypes.REMINDER -> "🔔 Recordarte «${p["texto"]}» ${p["cuando"]?.let { "($it)" } ?: ""}"
    ActionTypes.EVENT -> "📅 Crear evento «${p["titulo"]}» ${p["inicio"].orEmpty()}"
    ActionTypes.MESSAGE -> "✉️ Borrador${p["para"]?.let { " para $it" } ?: ""}: «${p["texto"]}»"
    ActionTypes.HOME -> "🏠 ${p["servicio"]} ${p["entidad"]}"
    ActionTypes.OPEN -> "🔗 Abrir ${p["url"]}"
    else -> "$type $p"
}

/** Mensajes de estilo: cómo respondes tú de verdad (para imitarte). */
@Serializable
data class StyleExample(val prompt: String, val reply: String, val at: Long = System.currentTimeMillis())

/** Una ronda de "¿qué haría yo?": la copia adivina y tú respondes. */
@Serializable
data class QuizRound(
    val question: String,
    val twinGuess: String,
    val answer: String,
    val score: Double,
    val lesson: String,
    val at: Long = System.currentTimeMillis(),
)

/** Qué tanto se parece tu copia a ti (0-100), según las últimas pruebas. */
fun twinSimilarity(s: CookieState): Int? =
    s.quiz.takeLast(20).takeIf { it.isNotEmpty() }?.map { it.score }?.average()?.let { (it * 100).toInt() }
