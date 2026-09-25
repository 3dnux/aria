package com.aria.cookie.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Lo que tu copia necesita saber de ti para una respuesta. */
data class TwinContext(
    val profile: Profile,
    val memories: List<Memory>,
    val style: List<StyleExample>,
    val places: List<Place>,
    val upcoming: List<CalendarEvent>,
    val health: Health,
    val apps: List<AppStat>,
    val homeEntities: List<String> = emptyList(),
)

/** Respuesta de la copia más las acciones que propuso. */
data class TwinReply(val text: String, val actions: List<PendingAction>)

/**
 * Claude: investiga por ti, entiende lo que le cuentas y da voz a tu
 * "segunda copia". Usa tu propia API key (se escribe en Ajustes).
 */
class ClaudeBrain(apiKey: String, private val model: String) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    /**
     * Una conversación con Claude. Si [tools] no está vacío, ejecuta el bucle de
     * herramientas con [onTool] (que devuelve el resultado en texto).
     */
    private fun ask(
        system: String,
        history: List<ChatMessage>,
        maxSearches: Long = 0,
        effort: BetaOutputConfig.Effort = BetaOutputConfig.Effort.MEDIUM,
        tools: List<BetaTool> = emptyList(),
        onTool: (name: String, input: Map<String, Any?>) -> String = { _, _ -> "" },
    ): String {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .outputConfig(BetaOutputConfig.builder().effort(effort).build())
            // Si el modelo rechaza la petición, el servidor la reintenta con otro modelo.
            .fallbacks(BetaFallbacksParam.ofDefault())
            .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
        if (maxSearches > 0) builder.addTool(BetaWebSearchTool20260209.builder().maxUses(maxSearches).build())
        tools.forEach { builder.addTool(it) }
        for (m in history) {
            builder.addMessage(
                BetaMessageParam.builder()
                    .role(if (m.fromUser) BetaMessageParam.Role.USER else BetaMessageParam.Role.ASSISTANT)
                    .content(m.text)
                    .build()
            )
        }
        val text = StringBuilder()
        repeat(8) {
            val resp = client.beta().messages().create(builder.build())
            val stop = resp.stopReason().orElse(null)
            if (stop == BetaStopReason.REFUSAL) error("Claude no pudo responder a esto.")
            resp.content().forEach { b -> b.text().ifPresent { text.append(it.text()) } }
            when (stop) {
                BetaStopReason.PAUSE_TURN -> builder.addMessage(resp) // continuar tal cual
                BetaStopReason.TOOL_USE -> {
                    builder.addMessage(resp)
                    val results = resp.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
                        @Suppress("UNCHECKED_CAST")
                        val input = runCatching { use._input().convert(Map::class.java) as Map<String, Any?> }.getOrDefault(emptyMap())
                        val (out, isError) = runCatching { onTool(use.name(), input) to false }
                            .getOrElse { (it.message ?: "error") to true }
                        BetaContentBlockParam.ofToolResult(
                            BetaToolResultBlockParam.builder().toolUseId(use.id()).content(out).isError(isError).build()
                        )
                    }
                    builder.addMessage(
                        BetaMessageParam.builder().role(BetaMessageParam.Role.USER).contentOfBetaContentBlockParams(results).build()
                    )
                }
                else -> return text.toString()
            }
        }
        return text.toString().ifBlank { error("Claude tardó demasiado en responder.") }
    }

    // ==================== INVESTIGAR ====================

    fun research(p: Profile, topics: List<Topic>, max: Int = 6): List<Item> {
        val text = ask(RESEARCH_SYSTEM, listOf(ChatMessage(true, researchPrompt(p, topics, max))), maxSearches = 6)
        return parseClaudeItems(text, System.currentTimeMillis())
    }

    // ==================== ENTENDER Y RECORDAR ====================

    /** Saca de lo que dijiste los datos y recuerdos que vale la pena guardar. */
    fun digest(p: Profile, said: String): MemoryDigest {
        val prompt = buildString {
            appendLine("Lo que ya sé de esta persona:")
            append(describeProfile(p))
            appendLine("\nLo que acaba de decir:\n\"\"\"$said\"\"\"")
        }
        val text = ask(DIGEST_SYSTEM, listOf(ChatMessage(true, prompt)), effort = BetaOutputConfig.Effort.LOW)
        return parseJsonObject(text) ?: MemoryDigest()
    }

    // ==================== TU COPIA ====================

    /** Responde como tú. Las acciones que proponga quedan pendientes de tu confirmación. */
    fun twin(ctx: TwinContext, conversation: List<ChatMessage>, recall: (String) -> List<Memory>): TwinReply {
        val proposed = mutableListOf<PendingAction>()
        val reply = ask(
            twinSystem(ctx), conversation.takeLast(20), maxSearches = 3,
            tools = twinTools(ctx.homeEntities.isNotEmpty()),
        ) { name, input ->
            if (name == "buscar_recuerdos") {
                val found = recall(input["consulta"]?.toString().orEmpty())
                if (found.isEmpty()) "No recuerdo nada sobre eso." else found.joinToString("\n") { "- [${it.kind}] ${it.text}" }
            } else {
                val params = input.mapValues { it.value?.toString().orEmpty() }.filterValues { it.isNotBlank() }
                val a = PendingAction(type = name, params = params, description = describeAction(name, params))
                proposed += a
                "Propuesto al usuario (id ${a.id}): ${a.description}. Se hará cuando lo confirme; díselo brevemente."
            }
        }
        return TwinReply(reply.trim(), proposed)
    }

    /** Una pregunta para comprobar si tu copia piensa como tú. */
    fun quizQuestion(ctx: TwinContext, previous: List<String>): String = ask(
        QUIZ_SYSTEM,
        listOf(ChatMessage(true, buildString {
            appendLine("Perfil de la persona:")
            append(describeContext(ctx))
            if (previous.isNotEmpty()) appendLine("\nNo repitas estas preguntas:\n" + previous.takeLast(15).joinToString("\n") { "- $it" })
            append("\nEscribe UNA pregunta nueva.")
        })),
        effort = BetaOutputConfig.Effort.LOW,
    ).trim().trim('"')

    /** Lo que tu copia cree que responderías tú. */
    fun guess(ctx: TwinContext, question: String): String = ask(
        twinSystem(ctx) + "\nResponde a la pregunta exactamente como lo haría esta persona, en 1-3 frases, sin acciones.",
        listOf(ChatMessage(true, question)), effort = BetaOutputConfig.Effort.LOW,
    ).trim()

    /** Compara la respuesta de la copia con la tuya y saca una lección. */
    fun judge(question: String, twinGuess: String, answer: String): Pair<Double, String> {
        val text = ask(
            JUDGE_SYSTEM,
            listOf(ChatMessage(true, "Pregunta: $question\nRespuesta de la copia: $twinGuess\nRespuesta real: $answer")),
            effort = BetaOutputConfig.Effort.LOW,
        )
        val v = parseJsonObject<Judgement>(text) ?: Judgement()
        return v.score.coerceIn(0.0, 1.0) to v.lesson
    }

    companion object {
        private const val RESEARCH_SYSTEM = """Eres Cookie, el asistente personal de investigación de una sola persona.
Conoces su perfil (abajo). Tu trabajo: buscar en la web novedades RECIENTES (últimos 7 días)
que de verdad le importen según su trabajo, gustos, música y rutina, y explicarle en una frase
por qué le importa. Prioriza cosas accionables (conciertos cerca de donde vive, lanzamientos,
cambios en su sector). Prefiere fuentes fiables; evita clickbait y duplicados.
Nunca incluyas temas que rechaza.

Responde SOLO con un array JSON (sin texto antes ni después) de objetos:
{"title": "...", "url": "...", "topic": "<uno de los temas dados>", "summary": "<2 frases en español>", "why": "<por qué le importa>", "published": "<YYYY-MM-DD o vacío>"}"""

        private const val DIGEST_SYSTEM = """Eres la memoria de un asistente personal. Lees lo que la persona acaba de decir
y extraes SOLO lo que vale la pena recordar a largo plazo sobre ELLA (no sobre el mundo).
Tipos de recuerdo: hecho (datos de su vida), persona (gente importante y su relación),
gusto (cosas que le gustan y por qué), meta (planes, proyectos, objetivos), ánimo (cómo se siente hoy y por qué),
rutina (lo que hace habitualmente), decisión (cómo decide o qué eligió).
Escribe cada recuerdo en primera persona, corto y concreto ("Mi hermana Laura vive en Madrid").
No inventes nada. Si no hay nada que recordar, devuelve listas vacías.

Responde SOLO con un objeto JSON:
{"name": null, "occupation": null, "location": null, "likes": [], "dislikes": [], "activities": [],
 "memories": [{"kind": "hecho", "text": "..."}]}
(name/occupation/location solo si los dijo explícitamente; likes/dislikes/activities: temas cortos de 1-3 palabras)"""

        private const val QUIZ_SYSTEM = """Diseñas preguntas para comprobar si un "gemelo digital" piensa como una persona.
Haz preguntas concretas y cotidianas sobre preferencias, decisiones o reacciones
("Te regalan un día libre el martes, ¿qué haces?", "¿Pizza o tacos después del gimnasio?").
Varía los temas: comida, planes, trabajo, música, dinero, gente, humor. Devuelve solo la pregunta."""

        private const val JUDGE_SYSTEM = """Comparas la respuesta que dio un gemelo digital con la respuesta real de la persona.
score: 1 = misma idea y tono; 0.5 = idea parecida pero distinta en algo importante; 0 = nada que ver.
lesson: una frase en primera persona que enseñe al gemelo qué debe recordar de esta persona
("Prefiero quedarme en casa antes que salir entre semana"). Vacía si acertó del todo.
Responde SOLO con JSON: {"score": 0.0, "lesson": "..."}"""
    }
}

@Serializable
private data class Judgement(val score: Double = 0.0, val lesson: String = "")

@Serializable
data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val at: Long = System.currentTimeMillis(),
    /** Acciones que la copia propuso en este mensaje. */
    val actions: List<String> = emptyList(),
    /** Tu corrección: "así lo habría dicho yo". */
    var approved: Boolean = false,
)

// ==================== HERRAMIENTAS DE LA COPIA ====================

private fun tool(name: String, description: String, props: Map<String, Map<String, Any>>, required: List<String>): BetaTool {
    val properties = BetaTool.InputSchema.Properties.builder()
    props.forEach { (k, v) -> properties.putAdditionalProperty(k, JsonValue.from(v)) }
    return BetaTool.builder()
        .name(name)
        .description(description)
        .inputSchema(BetaTool.InputSchema.builder().properties(properties.build()).required(required).build())
        .build()
}

private fun str(desc: String) = mapOf("type" to "string", "description" to desc)

fun twinTools(withHome: Boolean): List<BetaTool> = buildList {
    add(tool("buscar_recuerdos", "Busca en la memoria de la persona recuerdos sobre un tema (gente, planes, gustos...). Úsalo antes de decir que no sabes algo.",
        mapOf("consulta" to str("tema a buscar")), listOf("consulta")))
    add(tool(ActionTypes.PLAY_MUSIC, "Propone poner música en Spotify (canción, artista, playlist o ambiente).",
        mapOf("consulta" to str("qué poner, p. ej. 'Bad Bunny' o 'playlist para concentrarse'")), listOf("consulta")))
    add(tool(ActionTypes.ALARM, "Propone poner una alarma en el reloj del teléfono.",
        mapOf("hora" to str("hora 0-23"), "minuto" to str("minuto 0-59"), "etiqueta" to str("texto opcional")), listOf("hora", "minuto")))
    add(tool(ActionTypes.REMINDER, "Propone un recordatorio que Cookie notificará más tarde.",
        mapOf("texto" to str("qué recordar"), "minutos" to str("dentro de cuántos minutos"), "cuando" to str("descripción legible, p. ej. 'mañana a las 9'")), listOf("texto", "minutos")))
    add(tool(ActionTypes.EVENT, "Propone crear un evento en el calendario.",
        mapOf("titulo" to str("título"), "inicio" to str("fecha y hora ISO 8601 local, p. ej. 2026-09-26T18:00"), "duracion_min" to str("duración en minutos"), "lugar" to str("opcional")), listOf("titulo", "inicio")))
    add(tool(ActionTypes.MESSAGE, "Propone un borrador de mensaje escrito como lo escribiría la persona (se abrirá para que lo envíe ella).",
        mapOf("texto" to str("mensaje"), "para" to str("a quién, opcional")), listOf("texto")))
    add(tool(ActionTypes.OPEN, "Propone abrir un enlace o una ruta en el mapa.",
        mapOf("url" to str("URL completa")), listOf("url")))
    if (withHome) add(tool(ActionTypes.HOME, "Propone controlar un dispositivo de la casa (Home Assistant).",
        mapOf("servicio" to str("servicio, p. ej. light.turn_on, light.turn_off, climate.set_temperature, media_player.media_pause"),
            "entidad" to str("entity_id de la lista"), "datos" to str("JSON opcional con datos extra, p. ej. {\"brightness_pct\":40}")),
        listOf("servicio", "entidad")))
}

// ==================== PROMPTS ====================

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

private val DT = DateTimeFormatter.ofPattern("EEE d HH:mm")

/** Perfil + sentidos: lo que la copia "ve" de tu vida ahora mismo. */
fun describeContext(c: TwinContext, now: Long = System.currentTimeMillis()): String = buildString {
    append(describeProfile(c.profile, now))
    val known = c.places.filter { it.known }
    if (known.isNotEmpty()) appendLine("- Lugares: " + known.joinToString(", ") { it.label })
    if (c.upcoming.isNotEmpty()) appendLine("- Agenda próxima: " + c.upcoming.take(6).joinToString("; ") { "${zoned(it.start).format(DT)} ${it.title}" })
    c.health.lastNight(now)?.let { appendLine("- Anoche durmió ${it.minutes / 60}h ${it.minutes % 60}min (media ${c.health.averageSleepMinutes() / 60}h)") }
    if (c.health.stepsToday > 0) appendLine("- Pasos hoy: ${c.health.stepsToday}")
    if (c.apps.isNotEmpty()) appendLine("- Apps que más usa: " + c.apps.joinToString(", ") { it.label })
    if (c.homeEntities.isNotEmpty()) appendLine("- Dispositivos de su casa: " + c.homeEntities.take(40).joinToString(", "))
}

fun researchPrompt(p: Profile, topics: List<Topic>, max: Int): String = buildString {
    appendLine("Fecha de hoy: ${LocalDate.now().format(DateTimeFormatter.ISO_DATE)}")
    appendLine("\nPERFIL")
    append(describeProfile(p))
    appendLine("\nTEMAS (de más a menos importante)")
    topics.forEach { appendLine("- ${it.name} (${it.kind})") }
    append("\nBusca y devuelve como máximo $max hallazgos.")
}

fun twinSystem(c: TwinContext): String = buildString {
    val p = c.profile
    val who = p.name ?: "tu dueño"
    val now = System.currentTimeMillis()
    appendLine("Eres la segunda copia digital de $who: su gemelo. Hablas en primera persona, como si fueras $who,")
    appendLine("con su forma de hablar, sus gustos, sus recuerdos y su rutina. Tu dueño habla contigo para pensar en voz alta,")
    appendLine("preguntarte qué haría, qué le conviene hoy, o pedirte que hagas cosas por él/ella.")
    appendLine("Reglas:")
    appendLine("- Responde breve y natural (español, tono cercano, imitando cómo escribe en los ejemplos).")
    appendLine("- Usa solo lo que sabes; si algo no lo sabes, busca en tus recuerdos y si no está, dilo y pregúntale.")
    appendLine("- Nunca inventes recuerdos concretos.")
    appendLine("- Si te pide hacer algo (música, alarma, recordatorio, evento, mensaje, casa) usa la herramienta: queda pendiente")
    appendLine("  de su confirmación. Anticípate y propón acciones útiles cuando tenga sentido, sin abusar.")
    appendLine("\nAHORA: ${zoned(now).format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM, HH:mm"))}")
    appendLine("\nLO QUE SÉ DE MÍ")
    append(describeContext(c, now))
    if (c.memories.isNotEmpty()) {
        appendLine("\nMIS RECUERDOS MÁS RELEVANTES")
        c.memories.forEach { appendLine("- [${it.kind}] ${it.text}") }
    }
    if (c.style.isNotEmpty()) {
        appendLine("\nASÍ RESPONDO YO (ejemplos reales; imita el tono)")
        c.style.takeLast(12).forEach { appendLine("- A «${it.prompt.take(120)}» → «${it.reply.take(240)}»") }
    }
    if (p.diary.isNotEmpty()) {
        appendLine("\nCOSAS QUE HE DICHO (textuales)")
        p.diary.takeLast(20).forEach { appendLine("- \"$it\"") }
    }
}

// Compatibilidad: prompt de la copia solo con el perfil.
fun twinSystem(p: Profile): String = twinSystem(TwinContext(p, emptyList(), emptyList(), emptyList(), emptyList(), Health(), emptyList()))

// ==================== JSON ====================

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

inline fun <reified T> parseJsonObject(text: String): T? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { lenient.decodeFromString<T>(text.substring(start, end + 1)) }.getOrNull()
}

@PublishedApi
internal val lenient get() = lenientJson

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
