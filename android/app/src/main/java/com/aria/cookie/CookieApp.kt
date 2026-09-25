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
import com.aria.cookie.core.Nudge
import com.aria.cookie.core.Place
import com.aria.cookie.core.Profile
import com.aria.cookie.core.RssSource
import com.aria.cookie.core.Signal
import com.aria.cookie.core.Source
import com.aria.cookie.core.SpotifyApi
import com.aria.cookie.core.SpotifyAuth
import com.aria.cookie.core.SpotifyAuthException
import com.aria.cookie.core.Topic
import com.aria.cookie.core.TwinContext
import com.aria.cookie.core.greeting
import com.aria.cookie.core.topApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class CookieApp : Application() {
    lateinit var engine: Engine
    lateinit var settings: Settings
    lateinit var executor: ActionExecutor
    @Volatile private var homeCache: Pair<Long, List<String>>? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        engine = Engine(File(filesDir, "cookie.json"), KeystoreCodec)
        settings = Settings(this)
        executor = ActionExecutor(this)
        createChannels()
        scheduleBackgroundWork(this)
    }

    fun brain() = ClaudeBrain(settings.claudeKey, settings.claudeModel)

    /** Fuentes activas: Google News siempre; Claude si hay API key. */
    fun sources(): List<Source> = buildList {
        add(RssSource())
        if (settings.hasClaude) add(ClaudeSource(settings))
    }

    // ==================== APRENDER ====================

    /**
     * Aprende de lo que dices: primero con reglas (sin conexión) y, si hay clave
     * de Claude, entendiendo de verdad la frase y guardando recuerdos.
     */
    suspend fun learnDeep(text: String): Pair<List<Signal>, Int> {
        val signals = engine.learn(text)
        var memories = 0
        if (settings.hasClaude && settings.learnWithClaude) {
            runCatching {
                val before = engine.current().memories.size
                val digest = withContext(Dispatchers.IO) { brain().digest(engine.current().profile, text) }
                engine.applyMemories(digest)
                memories = digest.memories.size.coerceAtLeast(engine.current().memories.size - before)
            }
        }
        return signals to memories
    }

    /** Lo que tu copia necesita para responder a [query]. */
    suspend fun twinContext(query: String): TwinContext = withContext(Dispatchers.IO) {
        val s = engine.current()
        TwinContext(
            profile = s.profile,
            memories = engine.relevantMemories(query),
            style = s.style.toList(),
            places = s.places.toList(),
            upcoming = s.upcoming.toList(),
            health = s.health,
            apps = topApps(s),
            homeEntities = homeEntities(),
        )
    }

    private fun homeEntities(): List<String> {
        if (!settings.hasHome) return emptyList()
        homeCache?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return it.second }
        return executor.homeEntities().also { homeCache = System.currentTimeMillis() to it }
    }

    // ==================== SENTIDOS ====================

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
        t.scope?.let { settings.spotifyScopes = it }
        return t.access
    }

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

    /**
     * Lee todos los sentidos activos. Devuelve el lugar al que acabas de llegar
     * (si cambió) para poder anticiparse.
     */
    suspend fun senseAll(): Place? = withContext(Dispatchers.IO) {
        runCatching { syncSpotify() }
        if (settings.senseCalendar) runCatching { engine.learnCalendar(Sensors.readCalendar(this@CookieApp)) }
        if (settings.senseHealth) runCatching {
            Sensors.readHealth(this@CookieApp)?.let { engine.learnHealth(it.nights, it.stepsToday, it.stepsAvg) }
        }
        if (settings.senseApps) runCatching { engine.learnApps(Sensors.readUsage(this@CookieApp, engine.current().lastUsageSync)) }
        var arrived: Place? = null
        if (settings.senseLocation) runCatching {
            Sensors.currentLocation(this@CookieApp)?.let { loc ->
                val o = engine.observeLocation(loc.latitude, loc.longitude)
                if (o.arrived) arrived = o.place
            }
        }
        arrived
    }

    suspend fun research(): Int = withContext(Dispatchers.IO) { engine.research(sources()) }

    // ==================== AVISOS ====================

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tus novedades", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Resúmenes que Cookie investiga para ti" })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_NUDGES, "Se adelanta por ti", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Avisos según tu agenda, tu sueño, dónde estás y tu rutina" })
    }

    fun canNotify(): Boolean = settings.notifications && (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun openAppIntent(extra: (Intent) -> Unit = {}, requestCode: Int = 0): PendingIntent = PendingIntent.getActivity(
        this, requestCode,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).also(extra),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @Suppress("MissingPermission")
    fun notifyBriefing(items: List<Item>, profile: Profile) {
        if (items.isEmpty() || !canNotify()) return
        val style = NotificationCompat.InboxStyle()
        items.forEach { style.addLine("• ${it.title}") }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cookie)
            .setContentTitle("🍪 ${greeting(profile, System.currentTimeMillis())}")
            .setContentText("Investigué ${items.size} cosas para ti: ${items.first().title}")
            .setStyle(style)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(1, n)
    }

    /** Aviso anticipado; si trae acción, el botón la ejecuta al tocarlo. */
    @Suppress("MissingPermission")
    suspend fun notifyNudge(n: Nudge) {
        if (!canNotify()) return
        val id = n.key.hashCode()
        val b = NotificationCompat.Builder(this, CHANNEL_NUDGES)
            .setSmallIcon(R.drawable.ic_stat_cookie)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setContentIntent(openAppIntent(requestCode = id))
            .setAutoCancel(true)
        n.action?.let { a ->
            engine.proposeAction(a)
            b.addAction(0, "Sí, hazlo", openAppIntent({ it.putExtra(EXTRA_RUN_ACTION, a.id).putExtra(EXTRA_NOTIFICATION, id) }, id + 1))
        }
        NotificationManagerCompat.from(this).notify(id, b.build())
    }

    companion object {
        const val CHANNEL = "briefings"
        const val CHANNEL_NUDGES = "nudges"
        const val EXTRA_RUN_ACTION = "run_action"
        const val EXTRA_NOTIFICATION = "notification_id"
        const val EXTRA_VOICE = "voice"
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
    val req = PeriodicWorkRequestBuilder<CookieWorker>(30, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("cookie", ExistingPeriodicWorkPolicy.UPDATE, req)
}

/**
 * Modo autónomo: cada 30 min Cookie despierta, lee tus sentidos (Spotify,
 * agenda, ubicación, sueño, apps), se adelanta con avisos, investiga cada 3 h
 * y te entrega el resumen en tus horas activas.
 */
class CookieWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CookieApp
        val engine = app.engine
        val arrived = runCatching { app.senseAll() }.getOrNull()
        engine.takeNudges(arrived).take(2).forEach { app.notifyNudge(it) }
        if (engine.researchDue()) {
            runCatching { app.research() }.onFailure { engine.setError(it.message) }
        }
        if (engine.briefingDue()) {
            app.notifyBriefing(engine.deliver(5), engine.current().profile)
        }
        CookieWidget.refresh(app)
        return Result.success()
    }
}
