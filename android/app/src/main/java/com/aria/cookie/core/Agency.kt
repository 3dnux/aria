package com.aria.cookie.core

import kotlinx.serialization.Serializable
import java.util.UUID

// ==================== MISIONES ====================

/**
 * Un objetivo que tu copia trabaja por su cuenta durante días o semanas
 * ("prepara mi maratón", "organiza el cumpleaños de Laura"): revisa tu
 * contexto, investiga, ajusta el plan y te propone acciones.
 */
@Serializable
data class Mission(
    val id: String = UUID.randomUUID().toString().take(8),
    val goal: String,
    var plan: List<String> = emptyList(),
    var status: String = "activa", // activa, pausada, cumplida
    val updates: MutableList<MissionUpdate> = mutableListOf(),
    var nextCheck: Long = 0,
    var everyHours: Int = 24,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class MissionUpdate(val at: Long, val text: String)

/** Lo que devuelve el agente tras trabajar una misión. */
@Serializable
data class MissionReport(
    val update: String = "",
    val plan: List<String> = emptyList(),
    val done: Boolean = false,
    val next_check_hours: Int = 24,
)

fun dueMissions(s: CookieState, now: Long) = s.missions.filter { it.status == "activa" && it.nextCheck <= now }

fun applyMissionReport(m: Mission, r: MissionReport, now: Long) {
    if (r.update.isNotBlank()) m.updates += MissionUpdate(now, r.update)
    while (m.updates.size > 50) m.updates.removeAt(0)
    if (r.plan.isNotEmpty()) m.plan = r.plan.take(12)
    if (r.done) m.status = "cumplida"
    m.everyHours = r.next_check_hours.coerceIn(2, 24 * 7)
    m.nextCheck = now + m.everyHours * 3_600_000L
}

// ==================== AUTONOMÍA ====================

const val ASK = "preguntar"
const val AUTO = "automático"

/** Acciones que pueden ir en automático si tú lo permites (nunca mensajes). */
val AUTONOMY_ALLOWED = listOf(ActionTypes.PLAY_MUSIC, ActionTypes.REMINDER, ActionTypes.OPEN, ActionTypes.HOME, ActionTypes.ALARM)

fun isAutomatic(s: CookieState, type: String) = type in AUTONOMY_ALLOWED && s.autonomy[type] == AUTO

// ==================== FIDELIDAD ====================

/** Una medición del parecido de tu copia sobre tus respuestas guardadas. */
@Serializable
data class FidelityRun(val at: Long, val score: Double, val questions: Int)

/**
 * Preguntas de examen: tus respuestas reales guardadas, excepto las 3 más
 * recientes (que la copia acaba de aprender) para medir sin hacer trampa.
 */
fun fidelityQuestions(s: CookieState, max: Int = 8): List<QuizRound> =
    s.quiz.dropLast(3).filter { it.answer.isNotBlank() }.shuffled(kotlin.random.Random(s.quiz.size)).take(max)

/** Memorias sin la respuesta exacta a la pregunta de examen (evita la chuleta). */
fun memoriesWithoutAnswer(memories: List<Memory>, q: QuizRound): List<Memory> =
    memories.filterNot { it.text.contains(q.question.take(40)) || it.text.contains(q.answer.take(40)) }
