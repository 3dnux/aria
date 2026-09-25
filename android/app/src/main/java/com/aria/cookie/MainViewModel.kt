package com.aria.cookie

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aria.cookie.core.ChatMessage
import com.aria.cookie.core.CookieState
import com.aria.cookie.core.QuizRound
import com.aria.cookie.core.SpotifyAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado de una ronda de "¿qué haría yo?". */
data class QuizUi(
    val question: String? = null,
    val guess: String? = null,
    val loading: Boolean = false,
    val last: QuizRound? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val cookie = app as CookieApp
    val engine = cookie.engine
    val settings = cookie.settings
    val state: StateFlow<CookieState> = engine.flow

    val toast = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val twinThinking = MutableStateFlow(false)
    val quiz = MutableStateFlow(QuizUi())
    /** Última respuesta de la copia, para leerla en voz alta. */
    val speak = MutableStateFlow<String?>(null)
    private val _settingsVersion = MutableStateFlow(0)
    val settingsVersion: StateFlow<Int> = _settingsVersion

    init {
        viewModelScope.launch {
            engine.touch()
            runCatching { cookie.senseAll() }
            CookieWidget.refresh(cookie)
        }
    }

    private fun work(block: suspend () -> String?) {
        viewModelScope.launch {
            busy.value = true
            try {
                toast.value = block()
            } catch (e: Exception) {
                toast.value = "⚠️ ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    // ==================== APRENDER ====================

    fun learn(text: String) = work {
        if (text.isBlank()) return@work null
        val (signals, memories) = cookie.learnDeep(text)
        val parts = signals.filter { it.kind != "palabra" }.map { "${it.kind} → ${it.value}" }.toMutableList()
        if (memories > 0) parts += "$memories recuerdo(s) nuevos"
        if (parts.isEmpty()) "Lo guardé en tu diario 📝" else "Aprendí: " + parts.joinToString(", ")
    }

    fun research() = work {
        runCatching { cookie.senseAll() }
        val n = cookie.research()
        if (n == 0) "No encontré nada nuevo que valga tu tiempo" else "🔎 Encontré $n cosas nuevas para ti"
    }

    fun senseNow() = work {
        val arrived = cookie.senseAll()
        engine.takeNudges(arrived).forEach { cookie.notifyNudge(it) }
        CookieWidget.refresh(cookie)
        "👀 Actualicé lo que sé de ti"
    }

    fun feedback(id: String, useful: Boolean) = work {
        engine.feedback(id, useful)
        if (useful) "👍 Buscaré más como esto" else "👎 Te mostraré menos de esto"
    }

    fun forget(topic: String) = work { engine.forget(topic); "🧹 Olvidé «$topic»" }
    fun forgetMemory(id: String) = work { engine.forgetMemory(id); "🧹 Recuerdo borrado" }
    fun renamePlace(id: String, label: String) = work { engine.renamePlace(id, label); "📍 Ahora sé que es «$label»" }

    // ==================== TU COPIA ====================

    /** Habla con tu copia. Lo que le cuentas también la enseña. */
    fun sendToTwin(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            engine.addChat(ChatMessage(fromUser = true, text = text.trim()))
            launch { runCatching { cookie.learnDeep(text) } } // aprende en paralelo
            if (!settings.hasClaude) {
                engine.addChat(ChatMessage(false, "Para hablar necesito tu clave de Claude (Ajustes). Mientras tanto, ya aprendí de lo que me dijiste. 🍪"))
                return@launch
            }
            twinThinking.value = true
            try {
                val history = engine.current().chat.toList()
                val ctx = cookie.twinContext(text)
                val reply = withContext(Dispatchers.IO) {
                    cookie.brain().twin(ctx, history) { q -> engine.relevantMemories(q, 12) }
                }
                val ids = reply.actions.map { engine.proposeAction(it).id }
                engine.addChat(ChatMessage(false, reply.text, actions = ids))
                if (settings.speakReplies) speak.value = reply.text
            } catch (e: Exception) {
                engine.addChat(ChatMessage(false, "⚠️ No pude responder: ${e.message}"))
            } finally {
                twinThinking.value = false
            }
        }
    }

    private fun promptBefore(m: ChatMessage): String {
        val chat = engine.current().chat
        val i = chat.indexOfFirst { it.at == m.at }
        return chat.take(i.coerceAtLeast(0)).lastOrNull { it.fromUser }?.text.orEmpty()
    }

    /** "Sí, así hablo yo": la respuesta se vuelve ejemplo de tu estilo. */
    fun approveTwin(m: ChatMessage) = work {
        engine.addStyle(promptBefore(m), m.text)
        engine.markApproved(m.at)
        "✅ Anotado: así hablas tú"
    }

    /** "Yo lo diría así": tu versión enseña a la copia. */
    fun correctTwin(m: ChatMessage, better: String) = work {
        if (better.isBlank()) return@work null
        engine.addStyle(promptBefore(m), better)
        engine.addChat(ChatMessage(true, "✏️ Yo lo diría así: $better"))
        "✏️ Aprendí de tu corrección"
    }

    fun startQuiz() {
        if (!settings.hasClaude) { toast.value = "La prueba necesita tu clave de Claude"; return }
        viewModelScope.launch {
            quiz.value = QuizUi(loading = true, last = quiz.value.last)
            try {
                val ctx = cookie.twinContext("")
                val (q, guess) = withContext(Dispatchers.IO) {
                    val brain = cookie.brain()
                    val q = brain.quizQuestion(ctx, engine.current().quiz.map { it.question })
                    val relevant = ctx.copy(memories = engine.relevantMemories(q))
                    q to brain.guess(relevant, q)
                }
                quiz.value = QuizUi(question = q, guess = guess, last = quiz.value.last)
            } catch (e: Exception) {
                quiz.value = QuizUi(last = quiz.value.last)
                toast.value = "⚠️ ${e.message}"
            }
        }
    }

    fun answerQuiz(answer: String) {
        val q = quiz.value
        if (q.question == null || q.guess == null || answer.isBlank()) return
        viewModelScope.launch {
            quiz.value = q.copy(loading = true)
            try {
                val (score, lesson) = withContext(Dispatchers.IO) { cookie.brain().judge(q.question, q.guess, answer) }
                val round = QuizRound(q.question, q.guess, answer, score, lesson)
                engine.addQuiz(round)
                cookie.learnDeep(answer)
                quiz.value = QuizUi(last = round)
            } catch (e: Exception) {
                quiz.value = q.copy(loading = false)
                toast.value = "⚠️ ${e.message}"
            }
        }
    }

    fun closeQuiz() { quiz.value = QuizUi() }

    // ==================== ACCIONES ====================

    fun confirmAction(ctx: Context, id: String) = work {
        val a = engine.action(id) ?: return@work "Esa acción ya no existe"
        try {
            val result = cookie.executor.run(ctx, a)
            engine.setActionStatus(id, "hecha", result)
            "✅ $result"
        } catch (e: Exception) {
            engine.setActionStatus(id, "error", e.message)
            throw e
        }
    }

    fun cancelAction(id: String) = work { engine.setActionStatus(id, "cancelada"); "Cancelado" }

    // ==================== SPOTIFY ====================

    fun syncSpotify() = work { "🎧 Spotify sincronizado (${cookie.syncSpotify()} reproducciones nuevas)" }

    fun spotifyLoginUrl(): String? {
        val id = settings.spotifyClientId
        if (id.isBlank()) {
            toast.value = "Primero pega tu Client ID de Spotify (ver instrucciones)"
            return null
        }
        val pkce = SpotifyAuth.newPkce()
        settings.pkceVerifier = pkce.verifier
        settings.pkceState = pkce.state
        return SpotifyAuth.authorizeUrl(id, pkce)
    }

    fun handleSpotifyCallback(uri: Uri) = work {
        uri.getQueryParameter("error")?.let { return@work "Spotify: acceso denegado ($it)" }
        val code = uri.getQueryParameter("code") ?: return@work "Spotify no devolvió código"
        if (uri.getQueryParameter("state") != settings.pkceState) return@work "Respuesta de Spotify no válida"
        val verifier = settings.pkceVerifier ?: return@work "Vuelve a intentar conectar Spotify"
        val tokens = withContext(Dispatchers.IO) { SpotifyAuth.exchangeCode(settings.spotifyClientId, code, verifier) }
        settings.spotifyAccess = tokens.access
        settings.spotifyRefresh = tokens.refresh
        settings.spotifyExpiresAt = tokens.expiresAt
        settings.spotifyScopes = tokens.scope ?: SpotifyAuth.SCOPES.joinToString(" ")
        settings.pkceVerifier = null
        settings.pkceState = null
        bumpSettings()
        "🎧 ¡Spotify conectado! Aprendí de ${cookie.syncSpotify()} reproducciones"
    }

    fun disconnectSpotify() = work {
        settings.clearSpotify()
        engine.disconnectSpotify()
        bumpSettings()
        "Spotify desconectado"
    }

    // ==================== AJUSTES ====================

    fun update(block: Settings.() -> Unit) {
        settings.block()
        bumpSettings()
    }

    fun saveSettings(clientId: String, claudeKey: String, model: String, homeUrl: String, homeToken: String) {
        settings.spotifyClientId = clientId
        settings.claudeKey = claudeKey
        settings.claudeModel = model
        settings.homeUrl = homeUrl
        settings.homeToken = homeToken
        bumpSettings()
        toast.value = "Ajustes guardados"
    }

    fun wipeAll() = work {
        engine.wipe()
        settings.wipe()
        bumpSettings()
        "🧹 He olvidado todo lo que sabía de ti"
    }

    fun bumpSettings() { _settingsVersion.value++ }
}
