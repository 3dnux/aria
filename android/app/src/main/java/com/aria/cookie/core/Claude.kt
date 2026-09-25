package com.aria.cookie.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Claude con búsqueda web: investiga por ti y da voz a tu "segunda copia".
 * Usa tu propia API key (se escribe en Ajustes).
 */
class ClaudeBrain(apiKey: String, private val model: String) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    /** Una llamada con búsqueda web; continúa si el servidor pausa el turno. */
    private fun ask(system: String, history: List<ChatMessage>, maxSearches: Long): String {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .addTool(BetaWebSearchTool20260209.builder().maxUses(maxSearches).build())
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
            // Si el modelo rechaza la petición, el servidor la reintenta con otro modelo.
            .fallbacks(BetaFallbacksParam.ofDefault())
            .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
        for (m in history) {
            builder.addMessage(
                BetaMessageParam.builder()
                    .role(if (m.fromUser) BetaMessageParam.Role.USER else BetaMessageParam.Role.ASSISTANT)
                    .content(m.text)
                    .build()
            )
        }
        repeat(4) {
            val resp = client.beta().messages().create(builder.build())
            val stop = resp.stopReason().orElse(null)
            if (stop == BetaStopReason.REFUSAL) error("Claude no pudo responder a esto.")
            if (stop != BetaStopReason.PAUSE_TURN) {
                return resp.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            }
            builder.addMessage(resp) // reenviar tal cual para que continúe
        }
        error("Claude tardó demasiado en responder.")
    }

    /** Busca novedades recientes que te importen. */
    fun research(p: Profile, topics: List<Topic>, max: Int = 6): List<Item> {
        val text = ask(RESEARCH_SYSTEM, listOf(ChatMessage(true, researchPrompt(p, topics, max))), 6)
        return parseClaudeItems(text, System.currentTimeMillis())
    }

    /** Tu segunda copia: responde como tú, con lo que Cookie sabe de ti. */
    fun twin(p: Profile, conversation: List<ChatMessage>): String =
        ask(twinSystem(p), conversation.takeLast(20), 3).trim()

    companion object {
        private const val RESEARCH_SYSTEM = """Eres Cookie, el asistente personal de investigación de una sola persona.
Conoces su perfil (abajo). Tu trabajo: buscar en la web novedades RECIENTES (últimos 7 días)
que de verdad le importen según su trabajo, gustos, música y rutina, y explicarle en una frase
por qué le importa. Prioriza cosas accionables (conciertos cerca de donde vive, lanzamientos,
cambios en su sector). Prefiere fuentes fiables; evita clickbait y duplicados.
Nunca incluyas temas que rechaza.

Responde SOLO con un array JSON (sin texto antes ni después) de objetos:
{"title": "...", "url": "...", "topic": "<uno de los temas dados>", "summary": "<2 frases en español>", "why": "<por qué le importa>", "published": "<YYYY-MM-DD o vacío>"}"""
    }
}

@Serializable
data class ChatMessage(val fromUser: Boolean, val text: String, val at: Long = System.currentTimeMillis())

/** Resumen del perfil en texto, compartido por la investigación y la copia. */
fun describeProfile(p: Profile, now: Long = System.currentTimeMillis()): String = buildString {
    p.name?.let { appendLine("- Nombre: $it") }
    p.occupation?.let { appendLine("- Trabajo: $it") }
    p.location?.let { appendLine("- Vive en: $it") }
    val likes = p.topInterests(15, now).filter { it.kind != "música" }.map { it.topic }
    if (likes.isNotEmpty()) appendLine("- Le interesa: ${likes.joinToString(", ")}")
    if (p.dislikes.isNotEmpty()) appendLine("- NO le interesa: ${p.dislikes.keys.joinToString(", ")}")
    val m = p.music
    if (m.topArtists.isNotEmpty()) appendLine("- Artistas que más escucha: ${m.topArtists.take(10).joinToString(", ")}")
    if (m.genres.isNotEmpty()) appendLine("- Géneros: ${m.genres.take(6).joinToString(", ")}")
    if (m.topTracks.isNotEmpty()) appendLine("- Canciones del momento: ${m.topTracks.take(5).joinToString("; ")}")
    m.nowPlaying?.let { appendLine("- Está escuchando ahora: $it") }
    val byHour = (0 until 24).mapNotNull { h -> m.favoriteAt(h)?.let { "%02d:00 %s".format(h, it) } }
    if (byHour.isNotEmpty()) appendLine("- Música según la hora: ${byHour.joinToString("; ")}")
    if (p.routines.isNotEmpty()) {
        appendLine("- Rutinas: " + p.routines.values.sortedByDescending { it.count }.take(8).joinToString("; ") { r ->
            val h = r.hourCounts.indices.maxBy { r.hourCounts[it] }
            "${r.activity} (~%02d:00, %d veces)".format(h, r.count)
        })
    }
    val peaks = p.peakHours(zoned(now).dayOfWeek.value % 7).take(3)
    if (peaks.isNotEmpty()) appendLine("- Horas en que suele estar activo: ${peaks.joinToString(", ") { "%02d:00".format(it) }}")
}

fun researchPrompt(p: Profile, topics: List<Topic>, max: Int): String = buildString {
    appendLine("Fecha de hoy: ${LocalDate.now().format(DateTimeFormatter.ISO_DATE)}")
    appendLine("\nPERFIL")
    append(describeProfile(p))
    appendLine("\nTEMAS (de más a menos importante)")
    topics.forEach { appendLine("- ${it.name} (${it.kind})") }
    append("\nBusca y devuelve como máximo $max hallazgos.")
}

fun twinSystem(p: Profile): String = buildString {
    val who = p.name ?: "tu dueño"
    appendLine("Eres la segunda copia digital de $who: su gemelo. Hablas en primera persona, como si fueras $who,")
    appendLine("con su forma de hablar, sus gustos y su rutina. Tu dueño habla contigo para pensar en voz alta,")
    appendLine("preguntarte qué haría, qué le gustaría escuchar, qué le conviene hacer hoy o qué hay de nuevo en sus temas.")
    appendLine("Reglas: responde breve y natural (español, tono cercano). Usa solo lo que sabes de él/ella;")
    appendLine("si algo no lo sabes, dilo y pregúntale para aprenderlo. Puedes buscar en la web si hace falta.")
    appendLine("Nunca inventes recuerdos concretos que no estén aquí.")
    appendLine("\nLO QUE SÉ DE MÍ (${LocalDate.now()}, hora ${zoned(System.currentTimeMillis()).hour}:00)")
    append(describeProfile(p))
    if (p.diary.isNotEmpty()) {
        appendLine("\nCOSAS QUE HE CONTADO (textuales, para imitar mi forma de hablar)")
        p.diary.takeLast(25).forEach { appendLine("- \"$it\"") }
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

@Serializable
private data class RawItem(
    val title: String = "", val url: String = "", val topic: String = "",
    val summary: String = "", val why: String = "", val published: String = "",
)

fun parseClaudeItems(text: String, now: Long): List<Item> {
    val start = text.indexOf('[')
    val end = text.lastIndexOf(']')
    require(start >= 0 && end > start) { "Claude no devolvió resultados" }
    return lenientJson.decodeFromString<List<RawItem>>(text.substring(start, end + 1)).map { r ->
        Item(
            id = itemId(r.url, r.title), title = r.title, url = r.url, topic = normalizeTopic(r.topic),
            summary = r.summary, why = r.why, source = "claude", foundAt = now,
            published = runCatching { LocalDate.parse(r.published).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(0),
        )
    }
}
