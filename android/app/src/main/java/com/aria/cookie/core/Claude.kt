package com.aria.cookie.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
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
    val self: SelfModel? = null,
    val weather: Weather? = null,
    val contacts: List<Contact> = emptyList(),
    val missions: List<Mission> = emptyList(),
    val days: List<DayEntry> = emptyList(),
    val currentPlace: String? = null,
    val guess: ContextGuess? = null,
    val patterns: List<com.aria.cookie.aria.Pattern> = emptyList(),
)

/** Respuesta de la copia más las acciones que propuso. */
data class TwinReply(val text: String, val actions: List<PendingAction>)

/** Prompt de sistema: la parte estable se cachea (más rápido y barato), la volátil no. */
data class SystemPrompt(val stable: String, val volatile: String = "")

/** Lo que la copia puede hacer mientras responde. */
class TwinHooks(
    val recall: (String) -> List<Memory>,
    val createMission: (goal: String, plan: List<String>) -> String = { _, _ -> "" },
    val isAutomatic: (type: String) -> Boolean = { false },
    val onDelta: ((String) -> Unit)? = null,
)

/**
 * Claude: investiga por ti, entiende lo que le cuentas, escribe tu diario,
 * reflexiona sobre quién eres, trabaja tus misiones y da voz a tu copia.
 * Usa tu propia API key (se escribe en Ajustes).
 */
class ClaudeBrain(apiKey: String, private val model: String) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    /**
     * Una conversación con Claude, con bucle de herramientas. Si [onDelta] no es
     * null, la respuesta llega en streaming (para leerla en voz alta al vuelo).
     */
    private fun ask(
        system: SystemPrompt,
        history: List<ChatMessage>,
        maxSearches: Long = 0,
        effort: BetaOutputConfig.Effort = BetaOutputConfig.Effort.MEDIUM,
        tools: List<ToolSpec> = emptyList(),
        onDelta: ((String) -> Unit)? = null,
        onTool: (name: String, input: Map<String, Any?>) -> String = { _, _ -> "" },
    ): String {
        val streaming = onDelta != null
        val systemBlocks = buildList {
            // Bloque estable con caché: no cambia entre mensajes seguidos.
            add(BetaTextBlockParam.builder().text(system.stable).cacheControl(BetaCacheControlEphemeral.builder().build()).build())
            if (system.volatile.isNotBlank()) add(BetaTextBlockParam.builder().text(system.volatile).build())
        }
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(if (streaming) 32000L else 16000L)
            .systemOfBetaTextBlockParams(systemBlocks)
            .outputConfig(BetaOutputConfig.builder().effort(effort).build())
            // Si el modelo rechaza la petición, el servidor la reintenta con otro modelo.
            .fallbacks(BetaFallbacksParam.ofDefault())
            .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
        if (maxSearches > 0) builder.addTool(BetaWebSearchTool20260209.builder().maxUses(maxSearches).build())
        tools.forEach { builder.addTool(it.build(eager = streaming)) }
        for (m in history) {
            builder.addMessage(
                BetaMessageParam.builder()
                    .role(if (m.fromUser) BetaMessageParam.Role.USER else BetaMessageParam.Role.ASSISTANT)
                    .content(m.text)
                    .build()
            )
        }
        val specs = tools.associateBy { it.name }
        val text = StringBuilder()
        repeat(8) {
            val resp = if (streaming) stream(builder.build(), onDelta!!) else client.beta().messages().create(builder.build())
            val stop = resp.stopReason().orElse(null)
            if (stop == BetaStopReason.REFUSAL) error("Claude no pudo responder a esto.")
            if (stop == BetaStopReason.MAX_TOKENS && resp.content().any { it.isToolUse() }) error("La respuesta se cortó; inténtalo de nuevo.")
            resp.content().forEach { b -> b.text().ifPresent { text.append(it.text()) } }
            when (stop) {
                BetaStopReason.PAUSE_TURN -> builder.addMessage(resp) // continuar tal cual
                BetaStopReason.TOOL_USE -> {
                    builder.addMessage(resp)
                    val results = resp.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
                        @Suppress("UNCHECKED_CAST")
                        val input = runCatching { use._input().convert(Map::class.java) as Map<String, Any?> }.getOrNull()
                        // Con streaming las entradas no se validan en el servidor: se validan aquí.
                        val missing = specs[use.name()]?.required?.filter { input?.get(it)?.toString().isNullOrBlank() }.orEmpty()
                        val (out, isError) = when {
                            input == null -> "Entrada JSON no válida; vuelve a intentarlo." to true
                            missing.isNotEmpty() -> "Faltan campos obligatorios: ${missing.joinToString()}" to true
                            else -> runCatching { onTool(use.name(), input) to false }.getOrElse { (it.message ?: "error") to true }
                        }
                        BetaContentBlockParam.ofToolResult(
                            BetaToolResultBlockParam.builder().toolUseId(use.id()).content(out).isError(isError).build()
                        )
                    }
                    builder.addMessage(
                        BetaMessageParam.builder().role(BetaMessageParam.Role.USER).contentOfBetaContentBlockParams(results).build()
                    )
                    if (streaming) onDelta!!("\n")
                }
                else -> return text.toString()
            }
        }
        return text.toString().ifBlank { error("Claude tardó demasiado en responder.") }
    }

    private fun stream(params: MessageCreateParams, onDelta: (String) -> Unit): BetaMessage {
        val acc = BetaMessageAccumulator.create()
        client.beta().messages().createStreaming(params).use { s ->
            s.stream().forEach { ev ->
                acc.accumulate(ev)
                ev.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { onDelta(it.text()) }
            }
        }
        return acc.message()
    }

    // ==================== INVESTIGAR ====================

    fun research(p: Profile, topics: List<Topic>, max: Int = 6): List<Item> {
        val text = ask(SystemPrompt(RESEARCH_SYSTEM), listOf(ChatMessage(true, researchPrompt(p, topics, max))), maxSearches = 6)
        return parseClaudeItems(text, System.currentTimeMillis())
    }

    // ==================== ENTENDER Y RECORDAR ====================

    /** Saca de lo que dijiste (uno o varios mensajes) los datos y recuerdos que valen la pena. */
    fun digest(p: Profile, said: List<String>): MemoryDigest {
        if (said.isEmpty()) return MemoryDigest()
        val prompt = buildString {
            appendLine("Lo que ya sé de esta persona:")
            append(describeProfile(p))
            appendLine("\nLo que ha dicho (en orden):")
            said.forEach { appendLine("\"\"\"$it\"\"\"") }
        }
        val text = ask(SystemPrompt(DIGEST_SYSTEM), listOf(ChatMessage(true, prompt)), effort = BetaOutputConfig.Effort.LOW)
        return parseJsonObject(text) ?: MemoryDigest()
    }

    fun digest(p: Profile, said: String) = digest(p, listOf(said))

    /** Escribe la entrada del diario de un día a partir de lo que Cookie vio. */
    fun writeDay(context: String): DayDigest {
        val text = ask(SystemPrompt(DAY_SYSTEM), listOf(ChatMessage(true, context)), effort = BetaOutputConfig.Effort.LOW)
        return parseJsonObject(text) ?: DayDigest()
    }

    /** Reflexiona sobre todo lo que sabe y reescribe "Quién soy". */
    fun reflect(context: String): SelfModel {
        val text = ask(SystemPrompt(REFLECT_SYSTEM), listOf(ChatMessage(true, context)), effort = BetaOutputConfig.Effort.HIGH)
        return (parseJsonObject<SelfModel>(text) ?: error("No pude reflexionar ahora")).copy(at = System.currentTimeMillis())
    }

    // ==================== TU COPIA ====================

    /** Responde como tú. Las acciones que proponga quedan pendientes de tu confirmación (o se hacen solas si lo permitiste). */
    fun twin(
        ctx: TwinContext, conversation: List<ChatMessage>, hooks: TwinHooks,
        effort: BetaOutputConfig.Effort = BetaOutputConfig.Effort.MEDIUM, maxSearches: Long = 3,
    ): TwinReply {
        val proposed = mutableListOf<PendingAction>()
        val reply = ask(
            twinSystem(ctx), conversation.takeLast(20), maxSearches = maxSearches, effort = effort,
            tools = twinTools(ctx.homeEntities.isNotEmpty()), onDelta = hooks.onDelta,
        ) { name, input -> handleTool(name, input, hooks, proposed) }
        return TwinReply(reply.trim(), proposed)
    }

    private fun handleTool(name: String, input: Map<String, Any?>, hooks: TwinHooks, proposed: MutableList<PendingAction>): String = when (name) {
        "buscar_recuerdos" -> {
            val found = hooks.recall(input["consulta"]?.toString().orEmpty())
            if (found.isEmpty()) "No recuerdo nada sobre eso." else found.joinToString("\n") { "- [${it.kind}] ${it.text}" }
        }
        "crear_mision" -> {
            val plan = (input["plan"] as? List<*>)?.mapNotNull { it?.toString() }
                ?: input["plan"]?.toString()?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
            hooks.createMission(input["objetivo"].toString(), plan)
        }
        else -> {
            val params = input.mapValues { it.value?.toString().orEmpty() }.filterValues { it.isNotBlank() }
            val a = PendingAction(type = name, params = params, description = describeAction(name, params))
            proposed += a
            if (hooks.isAutomatic(name)) "Hecho automáticamente (tiene permiso): ${a.description}. Díselo brevemente."
            else "Propuesto al usuario (id ${a.id}): ${a.description}. Se hará cuando lo confirme; díselo brevemente."
        }
    }

    /** Trabaja una misión: revisa contexto, investiga, ajusta el plan y propone acciones. */
    fun runMission(ctx: TwinContext, mission: Mission, hooks: TwinHooks): Pair<MissionReport, List<PendingAction>> {
        val proposed = mutableListOf<PendingAction>()
        val prompt = buildString {
            appendLine("MISIÓN: ${mission.goal}")
            if (mission.plan.isNotEmpty()) appendLine("Plan actual:\n" + mission.plan.joinToString("\n") { "- $it" })
            if (mission.updates.isNotEmpty()) appendLine("Avances anteriores:\n" + mission.updates.takeLast(8).joinToString("\n") { "- ${zoned(it.at).toLocalDate()}: ${it.text}" })
            appendLine("\nTrabaja ahora en esta misión.")
        }
        val text = ask(
            SystemPrompt(MISSION_SYSTEM + "\n\n" + twinSystem(ctx).stable, twinSystem(ctx).volatile),
            listOf(ChatMessage(true, prompt)), maxSearches = 5, effort = BetaOutputConfig.Effort.HIGH,
            tools = twinTools(ctx.homeEntities.isNotEmpty()).filter { it.name != "crear_mision" },
        ) { name, input -> handleTool(name, input, hooks, proposed) }
        return (parseJsonObject<MissionReport>(text) ?: MissionReport(update = text.take(400))) to proposed
    }

    /** Una pregunta para comprobar si tu copia piensa como tú. */
    fun quizQuestion(ctx: TwinContext, previous: List<String>): String = ask(
        SystemPrompt(QUIZ_SYSTEM),
        listOf(ChatMessage(true, buildString {
            appendLine("Perfil de la persona:")
            append(describeContext(ctx))
            if (previous.isNotEmpty()) appendLine("\nNo repitas estas preguntas:\n" + previous.takeLast(15).joinToString("\n") { "- $it" })
            append("\nEscribe UNA pregunta nueva.")
        })),
        effort = BetaOutputConfig.Effort.LOW,
    ).trim().trim('"')

    /** Lo que tu copia cree que responderías tú. */
    fun guess(ctx: TwinContext, question: String): String {
        val sys = twinSystem(ctx)
        return ask(
            sys.copy(volatile = sys.volatile + "\nResponde a la pregunta exactamente como lo haría esta persona, en 1-3 frases, sin acciones."),
            listOf(ChatMessage(true, question)), effort = BetaOutputConfig.Effort.LOW,
        ).trim()
    }

    /** Compara la respuesta de la copia con la tuya y saca una lección. */
    fun judge(question: String, twinGuess: String, answer: String): Pair<Double, String> {
        val text = ask(
            SystemPrompt(JUDGE_SYSTEM),
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

        private const val DIGEST_SYSTEM = """Eres la memoria de un asistente personal. Lees lo que la persona ha dicho
y extraes SOLO lo que vale la pena recordar a largo plazo sobre ELLA (no sobre el mundo).
Tipos de recuerdo: hecho (datos de su vida), persona (gente importante y su relación),
gusto (cosas que le gustan y por qué), meta (planes, proyectos, objetivos), ánimo (cómo se siente y por qué),
rutina (lo que hace habitualmente), decisión (cómo decide o qué eligió).
Escribe cada recuerdo en primera persona, corto y concreto ("Mi hermana Laura vive en Madrid").
Añade a cada recuerdo 3-6 "tags": conceptos y sinónimos en español para encontrarlo por significado
(p. ej. para "Mi mamá cumple años el 3 de mayo": ["madre", "familia", "cumpleaños", "mayo"]).
No inventes nada. Si no hay nada que recordar, devuelve listas vacías.

Responde SOLO con un objeto JSON:
{"name": null, "occupation": null, "location": null, "likes": [], "dislikes": [], "activities": [],
 "memories": [{"kind": "hecho", "text": "...", "tags": ["..."]}]}
(name/occupation/location solo si los dijo explícitamente; likes/dislikes/activities: temas cortos de 1-3 palabras)"""

        private const val DAY_SYSTEM = """Eres el diario personal de una persona. Recibes lo que su teléfono notó durante un día
(lugares, música, eventos, movimiento, apps, sueño, mensajes que te dijo). Escribe en PRIMERA PERSONA,
como si fuera ella, un resumen honesto y breve del día (2-4 frases), sin inventar nada que no esté en los datos.
mood: una o dos palabras sobre cómo parece que estuvo (o vacío si no hay pistas).
highlights: 1-4 momentos destacados cortos.
Responde SOLO con JSON: {"summary": "...", "mood": "...", "highlights": ["..."]}"""

        private const val REFLECT_SYSTEM = """Eres la conciencia reflexiva de un gemelo digital. Con todo lo que sabes de la persona
(perfil, recuerdos, diario, respuestas reales, retrato anterior) escribe su retrato actual, en PRIMERA PERSONA,
como si ella se describiera con total honestidad. Basado solo en evidencia; si algo es incierto, dilo.
- summary: quién soy en 4-6 frases (qué me mueve, cómo vivo, qué me importa ahora).
- values: 3-6 valores con evidencia implícita.
- decisionStyle: cómo decido (impulsivo/planificador, qué priorizo, cómo gasto, cómo elijo planes).
- communication: cómo hablo y escribo (tono, largo de mensajes, humor, muletillas, emojis).
- worries: lo que me preocupa o estresa ahora.
- goals: lo que estoy intentando lograr.
- changes: en qué he cambiado respecto al retrato anterior (vacío si no hay anterior).
Responde SOLO con JSON: {"summary": "...", "values": [], "decisionStyle": "...", "communication": "...", "worries": [], "goals": [], "changes": "..."}"""

        private const val MISSION_SYSTEM = """MODO MISIÓN. Trabajas por tu cuenta, sin que la persona esté mirando, en un objetivo suyo.
Revisa su contexto actual (agenda, sueño, pasos, clima, lugares), busca en la web lo que haga falta,
ajusta el plan y propone acciones concretas con las herramientas (quedarán pendientes de su confirmación
salvo las que tengan permiso automático). No propongas más de 2 acciones por sesión.
Al terminar responde SOLO con JSON:
{"update": "<qué hiciste y qué le recomiendas, 1-3 frases en primera persona del plural o dirigido a la persona>",
 "plan": ["<siguientes pasos>"], "done": false, "next_check_hours": 24}"""

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
    /** ARIA: cómo se resolvió ("local", "claude:low"...). */
    val route: String? = null,
    /** ARIA M2: confianza de una respuesta local. */
    val localConfidence: Double? = null,
    var gateJudged: Boolean = false,
)

// ==================== HERRAMIENTAS DE LA COPIA ====================

/** Definición de una herramienta (se construye con o sin streaming de entradas). */
class ToolSpec(val name: String, private val description: String, private val props: Map<String, Map<String, Any>>, val required: List<String>) {
    fun build(eager: Boolean): BetaTool {
        val properties = BetaTool.InputSchema.Properties.builder()
        props.forEach { (k, v) -> properties.putAdditionalProperty(k, JsonValue.from(v)) }
        return BetaTool.builder()
            .name(name)
            .description(description)
            .inputSchema(BetaTool.InputSchema.builder().properties(properties.build()).required(required).build())
            .apply { if (eager) eagerInputStreaming(true) }
            .build()
    }
}

private fun str(desc: String) = mapOf("type" to "string", "description" to desc)

fun twinTools(withHome: Boolean): List<ToolSpec> = buildList {
    add(ToolSpec("buscar_recuerdos", "Busca en la memoria de la persona recuerdos sobre un tema (gente, planes, gustos, días pasados...). Úsalo antes de decir que no sabes algo.",
        mapOf("consulta" to str("tema a buscar, con sinónimos si ayuda")), listOf("consulta")))
    add(ToolSpec("crear_mision", "Crea una misión: un objetivo de varios días que trabajarás por tu cuenta (p. ej. 'preparar el maratón de abril', 'organizar el cumpleaños de Laura').",
        mapOf("objetivo" to str("el objetivo"), "plan" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "primeros pasos")), listOf("objetivo")))
    add(ToolSpec(ActionTypes.PLAY_MUSIC, "Propone poner música en Spotify (canción, artista, playlist o ambiente).",
        mapOf("consulta" to str("qué poner, p. ej. 'Bad Bunny' o 'playlist para concentrarse'")), listOf("consulta")))
    add(ToolSpec(ActionTypes.ALARM, "Propone poner una alarma en el reloj del teléfono.",
        mapOf("hora" to str("hora 0-23"), "minuto" to str("minuto 0-59"), "etiqueta" to str("texto opcional")), listOf("hora", "minuto")))
    add(ToolSpec(ActionTypes.REMINDER, "Propone un recordatorio que Cookie notificará más tarde.",
        mapOf("texto" to str("qué recordar"), "minutos" to str("dentro de cuántos minutos"), "cuando" to str("descripción legible, p. ej. 'mañana a las 9'")), listOf("texto", "minutos")))
    add(ToolSpec(ActionTypes.EVENT, "Propone crear un evento en el calendario.",
        mapOf("titulo" to str("título"), "inicio" to str("fecha y hora ISO 8601 local, p. ej. 2026-09-26T18:00"), "duracion_min" to str("duración en minutos"), "lugar" to str("opcional")), listOf("titulo", "inicio")))
    add(ToolSpec(ActionTypes.MESSAGE, "Propone un borrador de mensaje escrito como lo escribiría la persona (se abrirá para que lo envíe ella).",
        mapOf("texto" to str("mensaje"), "para" to str("a quién, opcional")), listOf("texto")))
    add(ToolSpec(ActionTypes.OPEN, "Propone abrir un enlace o una ruta en el mapa.",
        mapOf("url" to str("URL completa")), listOf("url")))
    if (withHome) add(ToolSpec(ActionTypes.HOME, "Propone controlar un dispositivo de la casa (Home Assistant).",
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
    if (c.contacts.isNotEmpty()) appendLine("- Con quien más habla: " + c.contacts.joinToString(", ") { it.name })
    if (c.homeEntities.isNotEmpty()) appendLine("- Dispositivos de su casa: " + c.homeEntities.take(40).joinToString(", "))
}

/** Lo que cambia minuto a minuto (no se cachea). */
fun describeNow(c: TwinContext, now: Long = System.currentTimeMillis()): String = buildString {
    appendLine("AHORA: ${zoned(now).format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM, HH:mm"))}")
    c.currentPlace?.let { appendLine("- Está en: $it") }
    c.profile.music.nowPlaying?.let { appendLine("- Escuchando: $it") }
    c.weather?.let { appendLine("- Clima: ${it.description}, ${it.temperature.toInt()}° (hoy ${it.minToday.toInt()}–${it.maxToday.toInt()}°, lluvia ${it.rainProbNext3h}%)") }
    c.guess?.let { appendLine("- Lo que suele hacer en esta situación: ${it.activity} (${(it.probability * 100).toInt()}%)") }
    if (c.upcoming.isNotEmpty()) appendLine("- Agenda próxima: " + c.upcoming.take(6).joinToString("; ") { "${zoned(it.start).format(DT)} ${it.title}" })
    c.health.lastNight(now)?.let { appendLine("- Anoche durmió ${it.minutes / 60}h ${it.minutes % 60}min (media ${c.health.averageSleepMinutes() / 60}h)") }
    if (c.health.stepsToday > 0) appendLine("- Pasos hoy: ${c.health.stepsToday}")
    c.health.stress?.let { appendLine("- Estrés (por pulso): $it") }
    if (c.apps.isNotEmpty()) appendLine("- Apps que más usa: " + c.apps.joinToString(", ") { it.label })
    if (c.missions.isNotEmpty()) appendLine("- Misiones activas: " + c.missions.joinToString("; ") { "${it.goal} (${it.updates.lastOrNull()?.text?.take(80) ?: "sin avances"})" })
    if (c.days.isNotEmpty()) appendLine("- Días recientes: " + c.days.takeLast(3).joinToString(" | ") { "${it.date}: ${it.summary.take(160)}" })
}

fun researchPrompt(p: Profile, topics: List<Topic>, max: Int): String = buildString {
    appendLine("Fecha de hoy: ${LocalDate.now().format(DateTimeFormatter.ISO_DATE)}")
    appendLine("\nPERFIL")
    append(describeProfile(p))
    appendLine("\nTEMAS (de más a menos importante)")
    topics.forEach { appendLine("- ${it.name} (${it.kind})") }
    append("\nBusca y devuelve como máximo $max hallazgos.")
}

fun twinSystem(c: TwinContext): SystemPrompt {
    val p = c.profile
    val who = p.name ?: "tu dueño"
    val stable = buildString {
        appendLine("Eres la segunda copia digital de $who: su gemelo. Hablas en primera persona, como si fueras $who,")
        appendLine("con su forma de hablar, sus valores, sus recuerdos y su rutina. Tu dueño habla contigo para pensar en voz alta,")
        appendLine("preguntarte qué haría, qué le conviene hoy, o pedirte que hagas cosas por él/ella.")
        appendLine("Reglas:")
        appendLine("- Responde breve y natural (español, tono cercano, imitando cómo escribe). Si hablas por voz, frases cortas.")
        appendLine("- Usa solo lo que sabes; si algo no lo sabes, busca en tus recuerdos y si no está, dilo y pregúntale.")
        appendLine("- Nunca inventes recuerdos concretos.")
        appendLine("- Si te pide hacer algo usa las herramientas. Si un objetivo lleva días (entrenar, organizar, aprender), crea una misión.")
        appendLine("- Anticípate con propuestas útiles cuando tenga sentido, sin abusar.")
        c.self?.let { s ->
            appendLine("\nQUIÉN SOY (mi retrato, ${zoned(s.at).toLocalDate()})")
            appendLine(s.summary)
            if (s.values.isNotEmpty()) appendLine("- Valores: ${s.values.joinToString(", ")}")
            if (s.decisionStyle.isNotBlank()) appendLine("- Cómo decido: ${s.decisionStyle}")
            if (s.communication.isNotBlank()) appendLine("- Cómo hablo: ${s.communication}")
            if (s.worries.isNotEmpty()) appendLine("- Me preocupa: ${s.worries.joinToString(", ")}")
            if (s.goals.isNotEmpty()) appendLine("- Busco: ${s.goals.joinToString(", ")}")
        }
        appendLine("\nLO QUE SÉ DE MÍ")
        append(describeContext(c))
        if (c.patterns.isNotEmpty()) {
            appendLine("\nPATRONES QUE ARIA DESCUBRIÓ EN MI VIDA (correlaciones, no certezas)")
            c.patterns.take(6).forEach { appendLine("- " + com.aria.cookie.aria.Patterns.describe(it)) }
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
    val volatile = buildString {
        append(describeNow(c))
        if (c.memories.isNotEmpty()) {
            appendLine("\nMIS RECUERDOS MÁS RELEVANTES PARA ESTO")
            c.memories.forEach { appendLine("- [${it.kind}] ${it.text}") }
        }
    }
    return SystemPrompt(stable, volatile)
}

// Compatibilidad: prompt de la copia solo con el perfil.
fun twinSystem(p: Profile): String = twinSystem(TwinContext(p, emptyList(), emptyList(), emptyList(), emptyList(), Health(), emptyList()))
    .let { it.stable + "\n" + it.volatile }

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
