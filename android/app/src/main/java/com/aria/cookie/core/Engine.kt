package com.aria.cookie.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Todo lo que Cookie persiste. */
@Serializable
data class CookieState(
    val version: Int = 1,
    val profile: Profile = Profile(),
    val inbox: MutableList<Item> = mutableListOf(),
    val seen: MutableMap<String, Long> = mutableMapOf(),
    val chat: MutableList<ChatMessage> = mutableListOf(),
    var lastResearch: Long = 0,
    var lastBriefing: Long = 0,
    var lastError: String? = null,
)

private val signalWeights = mapOf("trabajo" to 3.0, "gusto" to 2.0, "actividad" to 1.0, "palabra" to 0.3)
private const val MAX_INBOX = 200
private const val SEEN_MAX_AGE = 30L * 24 * 3600 * 1000
const val RESEARCH_EVERY_MS = 3L * 3600 * 1000
const val MIN_BRIEFING_GAP_MS = 6L * 3600 * 1000

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowStructuredMapKeys = true }

/**
 * Orquesta aprendizaje, investigación y resúmenes. Todo vive en un JSON
 * privado de la app; [wipe] lo borra por completo.
 */
class Engine(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var state: CookieState = load()
    private val _flow = MutableStateFlow(snapshot())
    val flow: StateFlow<CookieState> = _flow

    private fun load(): CookieState =
        if (file.exists()) runCatching { json.decodeFromString<CookieState>(file.readText()) }.getOrElse { CookieState() }
        else CookieState()

    private fun snapshot(): CookieState = json.decodeFromString(json.encodeToString(CookieState.serializer(), state))

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(CookieState.serializer(), state))
        tmp.renameTo(file)
        _flow.value = snapshot()
    }

    private suspend fun <T> edit(block: CookieState.() -> T): T = mutex.withLock {
        val r = state.block()
        save()
        r
    }

    /** Aprende de una frase libre y marca que estás activo ahora. */
    suspend fun learn(text: String): List<Signal> = edit {
        val t = now()
        val p = profile
        val signals = extract(text)
        for (s in signals) when (s.kind) {
            "nombre" -> p.name = s.value.replaceFirstChar { it.uppercase() }
            "lugar" -> p.location = s.value
            "trabajo" -> { p.occupation = s.value; p.reinforce(s.value, "trabajo", 3.0, t) }
            "rechazo" -> p.dislike(s.value, t)
            "actividad" -> { p.recordActivity(s.value, t); p.reinforce(s.value, "actividad", 1.0, t) }
            else -> p.reinforce(s.value, s.kind, signalWeights[s.kind] ?: 0.3, t)
        }
        p.addDiary(text)
        p.observations++
        p.markActive(t)
        signals
    }

    /** Abrir la app también enseña tu ritmo. */
    suspend fun touch() = edit { profile.markActive(now()); profile.observations++ }

    fun topics(n: Int = 8, s: CookieState = state): List<Topic> {
        val t = now()
        return s.profile.topInterests(0, t)
            .filter { it.kind != "palabra" || it.mentions >= 3 }
            .take(n)
            .map { Topic(it.topic, it.effectiveWeight(t), it.kind) }
    }

    /** Investiga en todas las fuentes y guarda lo nuevo y relevante. */
    suspend fun research(sources: List<Source>): Int {
        val (profileCopy, topics, seen) = mutex.withLock { Triple(snapshot().profile, topics(), state.seen.toMap()) }
        if (topics.isEmpty()) error("Todavía no sé qué te interesa: cuéntame algo o conecta Spotify.")
        val all = mutableListOf<Item>()
        val errors = mutableListOf<String>()
        for (src in sources) {
            try { all += src.search(profileCopy, topics) } catch (e: Exception) { errors += "${src.name}: ${e.message}" }
        }
        return edit {
            val t = now()
            val ranked = rank(all, profile, topics, seen, t)
            ranked.forEach { this.seen[it.id] = t }
            inbox += ranked
            inbox.sortWith(compareBy<Item> { it.delivered }.thenByDescending { it.score })
            while (inbox.size > MAX_INBOX) inbox.removeAt(inbox.lastIndex)
            this.seen.entries.removeAll { t - it.value > SEEN_MAX_AGE }
            lastResearch = t
            lastError = errors.takeIf { it.isNotEmpty() && ranked.isEmpty() }?.joinToString("; ")
            if (ranked.isEmpty() && errors.size == sources.size && errors.isNotEmpty()) error(lastError!!)
            ranked.size
        }
    }

    fun unread(s: CookieState = state) = s.inbox.filter { !it.delivered }

    /** Marca como entregados los mejores [max] hallazgos y los devuelve. */
    suspend fun deliver(max: Int): List<Item> = edit {
        val items = inbox.filter { !it.delivered }.take(max)
        items.forEach { it.delivered = true }
        lastBriefing = now()
        items.map { it.copy() }
    }

    /** Tu reacción a un hallazgo refuerza o debilita su tema. */
    suspend fun feedback(id: String, useful: Boolean) = edit {
        val t = now()
        val it = inbox.firstOrNull { it.id == id } ?: return@edit
        it.delivered = true
        if (useful) {
            it.feedback = 1
            profile.reinforce(it.topic, "feedback", 1.0, t)
            keywords(it.title).forEach { k -> profile.reinforce(k, "palabra", 0.2, t) }
        } else {
            it.feedback = -1
            profile.reinforce(it.topic, "feedback", -1.0, t)
        }
        profile.markActive(t)
    }

    suspend fun learnSpotify(s: SpotifySnapshot): Int = edit { learnFromSpotify(profile, s, now()) }

    suspend fun disconnectSpotify() = edit { profile.music.connected = false; profile.music.nowPlaying = null }

    suspend fun addChat(m: ChatMessage) = edit { chat += m; while (chat.size > 200) chat.removeAt(0) }

    suspend fun forget(topic: String) = edit { profile.forget(topic) }

    suspend fun setError(e: String?) = edit { lastError = e }

    fun current(): CookieState = state

    /** Borra todo lo que Cookie sabe de ti. */
    suspend fun wipe() = mutex.withLock {
        file.delete()
        state = CookieState()
        _flow.value = snapshot()
    }

    /** ¿Toca investigar / avisar? (para el trabajo en segundo plano) */
    fun researchDue() = now() - state.lastResearch >= RESEARCH_EVERY_MS && topics(1).isNotEmpty()
    fun briefingDue() = unread().isNotEmpty() && now() - state.lastBriefing >= MIN_BRIEFING_GAP_MS &&
        state.profile.isGoodMoment(now())
}

fun greeting(p: Profile, now: Long): String {
    val h = zoned(now).hour
    val g = when (h) {
        in 5..11 -> "Buenos días"
        in 12..19 -> "Buenas tardes"
        else -> "Buenas noches"
    }
    return p.name?.let { "$g, $it" } ?: g
}
