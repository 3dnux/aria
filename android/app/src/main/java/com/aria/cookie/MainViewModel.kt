package com.aria.cookie

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aria.cookie.core.ChatMessage
import com.aria.cookie.core.ClaudeBrain
import com.aria.cookie.core.CookieState
import com.aria.cookie.core.SpotifyAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val cookie = app as CookieApp
    val engine = cookie.engine
    val settings = cookie.settings
    val state: StateFlow<CookieState> = engine.flow

    /** Mensajes cortos para la barra inferior. */
    val toast = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val twinThinking = MutableStateFlow(false)
    /** Cambia cuando cambian los ajustes, para refrescar la UI. */
    private val _settingsVersion = MutableStateFlow(0)
    val settingsVersion: StateFlow<Int> = _settingsVersion

    init {
        viewModelScope.launch {
            engine.touch()
            if (settings.spotifyConnected) runCatching { cookie.syncSpotify() }
        }
    }

    private fun work(label: String? = null, block: suspend () -> String?) {
        viewModelScope.launch {
            busy.value = true
            try {
                toast.value = block() ?: label
            } catch (e: Exception) {
                toast.value = "⚠️ ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    fun learn(text: String) = work {
        if (text.isBlank()) return@work null
        val signals = engine.learn(text).filter { it.kind != "palabra" }
        if (signals.isEmpty()) "Lo guardé en tu diario 📝" else "Aprendí: " + signals.joinToString(", ") { "${it.kind} → ${it.value}" }
    }

    fun research() = work {
        if (settings.spotifyConnected) runCatching { cookie.syncSpotify() }
        val n = cookie.research()
        if (n == 0) "No encontré nada nuevo que valga tu tiempo" else "🔎 Encontré $n cosas nuevas para ti"
    }

    fun feedback(id: String, useful: Boolean) = work {
        engine.feedback(id, useful)
        if (useful) "👍 Buscaré más como esto" else "👎 Te mostraré menos de esto"
    }

    fun forget(topic: String) = work { engine.forget(topic); "🧹 Olvidé «$topic»" }

    fun syncSpotify() = work {
        val n = cookie.syncSpotify()
        "🎧 Spotify sincronizado ($n reproducciones nuevas)"
    }

    /** Habla con tu segunda copia. Lo que le cuentas también la enseña. */
    fun sendToTwin(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val msg = ChatMessage(fromUser = true, text = text.trim())
            engine.addChat(msg)
            engine.learn(text)
            if (!settings.hasClaude) {
                engine.addChat(ChatMessage(false, "Para hablar necesito tu clave de Claude (Ajustes). Mientras tanto, ya aprendí de lo que me dijiste. 🍪"))
                return@launch
            }
            twinThinking.value = true
            try {
                val history = engine.current().chat.toList()
                val reply = withContext(Dispatchers.IO) {
                    ClaudeBrain(settings.claudeKey, settings.claudeModel).twin(engine.current().profile, history)
                }
                engine.addChat(ChatMessage(false, reply))
            } catch (e: Exception) {
                engine.addChat(ChatMessage(false, "⚠️ No pude responder: ${e.message}"))
            } finally {
                twinThinking.value = false
            }
        }
    }

    /** URL del login de Spotify (o null si falta el Client ID). */
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

    /** Vuelta del navegador: ariacookie://spotify-callback?code=...&state=... */
    fun handleSpotifyCallback(uri: Uri) = work {
        uri.getQueryParameter("error")?.let { return@work "Spotify: acceso denegado ($it)" }
        val code = uri.getQueryParameter("code") ?: return@work "Spotify no devolvió código"
        if (uri.getQueryParameter("state") != settings.pkceState) return@work "Respuesta de Spotify no válida"
        val verifier = settings.pkceVerifier ?: return@work "Vuelve a intentar conectar Spotify"
        val tokens = withContext(Dispatchers.IO) { SpotifyAuth.exchangeCode(settings.spotifyClientId, code, verifier) }
        settings.spotifyAccess = tokens.access
        settings.spotifyRefresh = tokens.refresh
        settings.spotifyExpiresAt = tokens.expiresAt
        settings.pkceVerifier = null
        settings.pkceState = null
        bumpSettings()
        val n = cookie.syncSpotify()
        "🎧 ¡Spotify conectado! Aprendí de $n reproducciones"
    }

    fun disconnectSpotify() = work {
        settings.clearSpotify()
        engine.disconnectSpotify()
        bumpSettings()
        "Spotify desconectado"
    }

    fun saveSettings(clientId: String, claudeKey: String, model: String, notify: Boolean) {
        settings.spotifyClientId = clientId
        settings.claudeKey = claudeKey
        settings.claudeModel = model
        settings.notifications = notify
        bumpSettings()
        toast.value = "Ajustes guardados"
    }

    fun wipeAll() = work {
        engine.wipe()
        settings.wipe()
        bumpSettings()
        "🧹 He olvidado todo lo que sabía de ti"
    }

    private fun bumpSettings() { _settingsVersion.value++ }
}
