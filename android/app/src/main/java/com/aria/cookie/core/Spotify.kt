package com.aria.cookie.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** Datos de Spotify ya simplificados, listos para aprender de ellos. */
data class SpotifySnapshot(
    val displayName: String?,
    val topArtists: List<SpotifyArtist>,
    val topTracks: List<String>,
    val recent: List<SpotifyPlay>,
    val nowPlaying: String?,
)

data class SpotifyArtist(val name: String, val genres: List<String>)
data class SpotifyPlay(val track: String, val artist: String, val playedAt: Long)

/**
 * Autorización con Spotify por "Authorization Code + PKCE": no hace falta
 * secreto de cliente, solo tu Client ID (developer.spotify.com).
 */
object SpotifyAuth {
    const val REDIRECT_URI = "ariacookie://spotify-callback"
    val SCOPES = listOf(
        "user-read-private",
        "user-top-read",
        "user-read-recently-played",
        "user-read-currently-playing",
        "user-read-playback-state",
        "user-library-read",
        "user-follow-read",
    )

    data class Pkce(val verifier: String, val challenge: String, val state: String)

    fun newPkce(): Pkce {
        val rnd = SecureRandom()
        val bytes = ByteArray(64).also(rnd::nextBytes)
        val verifier = b64url(bytes)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = b64url(ByteArray(16).also(rnd::nextBytes))
        return Pkce(verifier, challenge, state)
    }

    fun authorizeUrl(clientId: String, pkce: Pkce): String =
        "https://accounts.spotify.com/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("state", pkce.state)
            .addQueryParameter("scope", SCOPES.joinToString(" "))
            .build().toString()

    data class Tokens(val access: String, val refresh: String?, val expiresAt: Long)

    fun exchangeCode(clientId: String, code: String, verifier: String): Tokens = tokenRequest(
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
    )

    fun refresh(clientId: String, refreshToken: String): Tokens = tokenRequest(
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
    ).let { if (it.refresh == null) it.copy(refresh = refreshToken) else it }

    private fun tokenRequest(body: FormBody): Tokens {
        val req = Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Spotify rechazó el acceso (HTTP ${resp.code}): $text")
            val o = Json.parseToJsonElement(text).jsonObject
            return Tokens(
                access = o.str("access_token")!!,
                refresh = o.str("refresh_token"),
                expiresAt = System.currentTimeMillis() + (o.str("expires_in")?.toLong() ?: 3600) * 1000 - 60_000,
            )
        }
    }

    private fun b64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Cliente mínimo de la Web API de Spotify (solo lectura). */
class SpotifyApi(private val token: () -> String) {

    fun snapshot(): SpotifySnapshot {
        val me = get("/me")?.jsonObject
        val artists = get("/me/top/artists?time_range=short_term&limit=20")?.items().orEmpty().map {
            SpotifyArtist(
                name = it.jsonObject.str("name").orEmpty(),
                genres = (it.jsonObject["genres"] as? JsonArray)?.mapNotNull { g -> g.jsonPrimitive.content }.orEmpty(),
            )
        }.filter { it.name.isNotBlank() }
        val tracks = get("/me/top/tracks?time_range=short_term&limit=20")?.items().orEmpty().mapNotNull { trackLabel(it.jsonObject) }
        val recent = get("/me/player/recently-played?limit=50")?.items().orEmpty().mapNotNull {
            val o = it.jsonObject
            val track = o["track"]?.jsonObject ?: return@mapNotNull null
            val playedAt = o.str("played_at")?.let { s -> runCatching { Instant.parse(s).toEpochMilli() }.getOrNull() }
                ?: return@mapNotNull null
            SpotifyPlay(track.str("name").orEmpty(), firstArtist(track), playedAt)
        }
        val now = get("/me/player/currently-playing")?.jsonObject?.get("item")
            ?.takeIf { it !is JsonNull }?.jsonObject?.let { trackLabel(it) }
        return SpotifySnapshot(me?.str("display_name"), artists, tracks, recent, now)
    }

    private fun get(path: String): JsonElement? {
        val req = Request.Builder().url("https://api.spotify.com/v1$path")
            .header("Authorization", "Bearer ${token()}").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 204) return null // nada sonando
            if (resp.code == 401) throw SpotifyAuthException()
            if (!resp.isSuccessful) return null // un endpoint no disponible no debe tumbar el resto
            val text = resp.body?.string().orEmpty()
            return if (text.isBlank()) null else Json.parseToJsonElement(text)
        }
    }

    private fun JsonElement.items(): List<JsonElement> = (jsonObject["items"] as? JsonArray).orEmpty()

    private fun trackLabel(track: JsonObject): String? {
        val name = track.str("name") ?: return null
        val artist = firstArtist(track)
        return if (artist.isBlank()) name else "$name — $artist"
    }

    private fun firstArtist(track: JsonObject): String =
        (track["artists"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("name").orEmpty()
}

class SpotifyAuthException : Exception("La sesión de Spotify caducó; vuelve a conectarla.")

private fun JsonObject.str(key: String): String? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

/**
 * Aprende de tu música: artistas y géneros se vuelven intereses (y temas a
 * investigar: conciertos, lanzamientos...), y cada reproducción enseña a
 * Cookie a qué horas estás despierto y qué escuchas en cada momento.
 */
fun learnFromSpotify(p: Profile, s: SpotifySnapshot, now: Long): Int {
    val m = p.music
    m.connected = true
    m.spotifyName = s.displayName ?: m.spotifyName
    if (p.name == null && !s.displayName.isNullOrBlank()) p.name = s.displayName.split(" ").first()
    if (s.topArtists.isNotEmpty()) m.topArtists = s.topArtists.map { it.name }
    if (s.topTracks.isNotEmpty()) m.topTracks = s.topTracks
    m.nowPlaying = s.nowPlaying

    // Artistas: el peso se fija según el ranking actual (no se acumula en cada sincronización).
    s.topArtists.take(10).forEachIndexed { i, a -> setWeight(p, a.name, "música", 2.5 - i * 0.2, now) }
    val genres = s.topArtists.flatMap { it.genres }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key }
    if (genres.isNotEmpty()) m.genres = genres.take(8)
    genres.take(5).forEachIndexed { i, g -> setWeight(p, g, "música", 1.2 - i * 0.15, now) }

    // Reproducciones nuevas → ritmo de vida y música por hora.
    var learned = 0
    for (play in s.recent.sortedBy { it.playedAt }) {
        if (play.playedAt <= m.lastPlayedAt) continue
        p.markActive(play.playedAt)
        p.observations++
        val hour = zoned(play.playedAt).hour
        if (play.artist.isNotBlank()) {
            val byArtist = m.byHour.getOrPut(hour) { mutableMapOf() }
            byArtist[play.artist] = (byArtist[play.artist] ?: 0) + 1
        }
        m.lastPlayedAt = play.playedAt
        learned++
    }
    m.lastSync = now
    return learned
}

private fun setWeight(p: Profile, topic: String, kind: String, weight: Double, now: Long) {
    val t = normalizeTopic(topic)
    if (t.isEmpty() || t in p.dislikes) return
    val i = p.interests.getOrPut(t) { Interest(t, kind = kind, firstSeen = now, lastSeen = now) }
    i.weight = maxOf(i.effectiveWeight(now), weight)
    i.lastSeen = now
    i.kind = if (i.kind == "trabajo") i.kind else kind
    if (i.mentions == 0) i.mentions = 1
}
