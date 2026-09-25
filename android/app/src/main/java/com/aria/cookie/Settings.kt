package com.aria.cookie

import android.content.Context

/**
 * Ajustes y credenciales. Se guardan en el almacenamiento privado de la app
 * (otras apps no pueden leerlo) y nunca se envían a ningún servidor propio.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("cookie_settings", Context.MODE_PRIVATE)

    var claudeKey: String
        get() = prefs.getString("claude_key", "").orEmpty()
        set(v) = prefs.edit().putString("claude_key", v.trim()).apply()

    var claudeModel: String
        get() = prefs.getString("claude_model", DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(v) = prefs.edit().putString("claude_model", v.trim()).apply()

    var spotifyClientId: String
        get() = prefs.getString("spotify_client_id", BuildConfig.SPOTIFY_CLIENT_ID).orEmpty()
        set(v) = prefs.edit().putString("spotify_client_id", v.trim()).apply()

    var spotifyAccess: String?
        get() = prefs.getString("spotify_access", null)
        set(v) = prefs.edit().putString("spotify_access", v).apply()

    var spotifyRefresh: String?
        get() = prefs.getString("spotify_refresh", null)
        set(v) = prefs.edit().putString("spotify_refresh", v).apply()

    var spotifyExpiresAt: Long
        get() = prefs.getLong("spotify_expires_at", 0)
        set(v) = prefs.edit().putLong("spotify_expires_at", v).apply()

    var pkceVerifier: String?
        get() = prefs.getString("pkce_verifier", null)
        set(v) = prefs.edit().putString("pkce_verifier", v).apply()

    var pkceState: String?
        get() = prefs.getString("pkce_state", null)
        set(v) = prefs.edit().putString("pkce_state", v).apply()

    var notifications: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(v) = prefs.edit().putBoolean("notifications", v).apply()

    val hasClaude get() = claudeKey.isNotBlank()
    val spotifyConnected get() = spotifyRefresh != null

    fun clearSpotify() = prefs.edit()
        .remove("spotify_access").remove("spotify_refresh").remove("spotify_expires_at").apply()

    fun wipe() = prefs.edit().clear().apply()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
    }
}
