package com.aria.cookie

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aria.cookie.core.ClaudeBrain
import com.aria.cookie.core.Engine
import com.aria.cookie.core.Item
import com.aria.cookie.core.RssSource
import com.aria.cookie.core.Source
import com.aria.cookie.core.SpotifyApi
import com.aria.cookie.core.SpotifyAuth
import com.aria.cookie.core.SpotifyAuthException
import com.aria.cookie.core.Profile
import com.aria.cookie.core.Topic
import com.aria.cookie.core.greeting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class CookieApp : Application() {
    lateinit var engine: Engine
    lateinit var settings: Settings

    override fun onCreate() {
        super.onCreate()
        instance = this
        engine = Engine(File(filesDir, "cookie.json"))
        settings = Settings(this)
        createChannel()
        scheduleBackgroundWork(this)
    }

    /** Fuentes activas: Google News siempre; Claude si hay API key. */
    fun sources(): List<Source> = buildList {
        add(RssSource())
        if (settings.hasClaude) add(ClaudeSource(settings))
    }

    fun spotifyApi(): SpotifyApi = SpotifyApi { validSpotifyToken() }

    /** Token vigente de Spotify; lo renueva si caducó. */
    @Synchronized
    fun validSpotifyToken(): String {
        val access = settings.spotifyAccess
        if (access != null && System.currentTimeMillis() < settings.spotifyExpiresAt) return access
        val refresh = settings.spotifyRefresh ?: throw SpotifyAuthException()
        val t = SpotifyAuth.refresh(settings.spotifyClientId, refresh)
        settings.spotifyAccess = t.access
        settings.spotifyRefresh = t.refresh
        settings.spotifyExpiresAt = t.expiresAt
        return t.access
    }

    /** Lee Spotify y aprende de él. Devuelve cuántas reproducciones nuevas vio. */
    suspend fun syncSpotify(): Int = withContext(Dispatchers.IO) {
        if (!settings.spotifyConnected) return@withContext 0
        try {
            engine.learnSpotify(spotifyApi().snapshot())
        } catch (e: SpotifyAuthException) {
            settings.clearSpotify()
            engine.disconnectSpotify()
            throw e
        }
    }

    suspend fun research(): Int = withContext(Dispatchers.IO) { engine.research(sources()) }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Tus novedades", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "Resúmenes que Cookie investiga para ti"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun notifyBriefing(items: List<Item>, profile: Profile) {
        if (items.isEmpty() || !settings.notifications) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val style = NotificationCompat.InboxStyle()
        items.forEach { style.addLine("• ${it.title}") }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cookie)
            .setContentTitle("🍪 ${greeting(profile, System.currentTimeMillis())}")
            .setContentText("Investigué ${items.size} cosas para ti: ${items.first().title}")
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(1, n)
    }

    companion object {
        const val CHANNEL = "briefings"
        lateinit var instance: CookieApp
            private set
    }
}

/** Claude como fuente de investigación. */
class ClaudeSource(private val settings: Settings) : Source {
    override val name = "claude"
    override fun search(profile: Profile, topics: List<Topic>) =
        ClaudeBrain(settings.claudeKey, settings.claudeModel).research(profile, topics)
}

fun scheduleBackgroundWork(context: Context) {
    val req = PeriodicWorkRequestBuilder<CookieWorker>(1, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("cookie", ExistingPeriodicWorkPolicy.KEEP, req)
}

/**
 * Modo autónomo: cada hora Cookie despierta, aprende de tu Spotify,
 * investiga si toca (cada 3 h) y, si es una de tus horas activas, te avisa.
 */
class CookieWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CookieApp
        val engine = app.engine
        runCatching { app.syncSpotify() }.onFailure { engine.setError(it.message) }
        if (engine.researchDue()) {
            runCatching { app.research() }.onFailure { engine.setError(it.message) }
        }
        if (engine.briefingDue()) {
            val items = engine.deliver(5)
            app.notifyBriefing(items, engine.current().profile)
        }
        return Result.success()
    }
}
