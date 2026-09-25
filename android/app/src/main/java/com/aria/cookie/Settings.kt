package com.aria.cookie

import android.content.Context
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Ajustes y credenciales, en el almacenamiento privado de la app. Las claves
 * y tokens se guardan cifrados con el Android Keystore.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("cookie_settings", Context.MODE_PRIVATE)

    private fun secret(key: String) = object : ReadWriteProperty<Any, String?> {
        override fun getValue(thisRef: Any, property: KProperty<*>): String? =
            prefs.getString(key, null)?.let { KeystoreCodec.decryptString(it) }?.ifEmpty { null }

        override fun setValue(thisRef: Any, property: KProperty<*>, value: String?) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(key) else putString(key, KeystoreCodec.encryptString(value.trim()))
            }.apply()
        }
    }

    private fun plain(key: String, default: String) = object : ReadWriteProperty<Any, String> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = prefs.getString(key, default).orEmpty().ifBlank { default }
        override fun setValue(thisRef: Any, property: KProperty<*>, value: String) = prefs.edit().putString(key, value.trim()).apply()
    }

    private fun flag(key: String, default: Boolean) = object : ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    }

    // Claude
    var claudeKeyOrNull by secret("claude_key")
    var claudeKey: String
        get() = claudeKeyOrNull.orEmpty()
        set(v) { claudeKeyOrNull = v }
    var claudeModel by plain("claude_model", DEFAULT_MODEL)

    // Spotify
    var spotifyClientId by plain("spotify_client_id", BuildConfig.SPOTIFY_CLIENT_ID)
    var spotifyAccess by secret("spotify_access")
    var spotifyRefresh by secret("spotify_refresh")
    var spotifyExpiresAt: Long
        get() = prefs.getLong("spotify_expires_at", 0)
        set(v) = prefs.edit().putLong("spotify_expires_at", v).apply()
    var spotifyScopes by plain("spotify_scopes", "")
    var pkceVerifier by secret("pkce_verifier")
    var pkceState by secret("pkce_state")

    // Home Assistant
    var homeUrl by plain("home_url", "")
    var homeToken by secret("home_token")

    // Comportamiento
    var notifications by flag("notifications", true)
    var speakReplies by flag("speak_replies", false)
    var biometricLock by flag("biometric_lock", false)
    var senseCalendar by flag("sense_calendar", false)
    var senseLocation by flag("sense_location", false)
    var senseHealth by flag("sense_health", false)
    var senseApps by flag("sense_apps", false)
    var learnWithClaude by flag("learn_with_claude", true)
    var senseMovement by flag("sense_movement", false)
    var senseNotifications by flag("sense_notifications", false)
    var sensePhotos by flag("sense_photos", false)
    var handsFree by flag("hands_free", false)
    var conversationMode by flag("conversation_mode", false)

    // Google Routes (tráfico real, opcional)
    var mapsKey by secret("maps_key")

    val hasClaude get() = claudeKey.isNotBlank()
    val spotifyConnected get() = spotifyRefresh != null
    val hasHome get() = homeUrl.isNotBlank() && !homeToken.isNullOrBlank()
    /** Los tokens antiguos no incluyen el permiso para controlar la reproducción. */
    val spotifyCanControl get() = spotifyScopes.contains("user-modify-playback-state")

    fun clearSpotify() {
        spotifyAccess = null
        spotifyRefresh = null
        prefs.edit().remove("spotify_expires_at").remove("spotify_scopes").apply()
    }

    fun wipe() = prefs.edit().clear().apply()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
    }
}
