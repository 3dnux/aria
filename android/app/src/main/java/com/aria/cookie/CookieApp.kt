package com.aria.cookie

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
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
import com.aria.cookie.core.ChatMessage
import com.aria.cookie.core.ClaudeBrain
import com.aria.cookie.core.DayEntry
import com.aria.cookie.core.Engine
import com.aria.cookie.core.FidelityRun
import com.aria.cookie.core.Item
import com.aria.cookie.core.Nudge
import com.aria.cookie.core.PendingAction
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
import com.aria.cookie.core.TwinHooks
import com.aria.cookie.core.currentPlace
import com.aria.cookie.core.dayContext
import com.aria.cookie.core.dueMissions
import com.aria.cookie.core.eventKey
import com.aria.cookie.core.fidelityQuestions
import com.aria.cookie.core.greeting
import com.aria.cookie.core.memoriesWithoutAnswer
import com.aria.cookie.core.predictContext
import com.aria.cookie.core.reflectionContext
import com.aria.cookie.core.topApps
import com.aria.cookie.core.topContacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        CrashLog.install(this)
        engine = Engine(File(filesDir, "cookie.json"), KeystoreCodec)
        settings = Settings(this)
        executor = ActionExecutor(this)
        createChannels()
        scheduleBackgroundWork(this)
        if (settings.senseMovement) Sensors.startActivityRecognition(this)
    }

    fun brain() = ClaudeBrain(settings.claudeKey, settings.claudeModel)

    /** Fuentes activas: Google News siempre; Claude si hay API key. */
    fun sources(): List<Source> = buildList {
        add(RssSource())
        if (settings.hasClaude) add(ClaudeSource(settings))
    }

    // ==================== APRENDER ====================

    /**
     * Aprende de lo que dices: primero con reglas (sin conexión) y, con Claude,
     * entendiendo de verdad y guardando recuerdos. [now]=false lo deja en la cola
     * para procesarlo por lotes (más barato), p. ej. durante una conversación.
     */
    suspend fun learnDeep(text: String, now: Boolean = true): Pair<List<Signal>, Int> {
        val signals = engine.learn(text)
        if (!settings.hasClaude || !settings.learnWithClaude) return signals to 0
        engine.queueDigest(text)
        if (!now && engine.current().digestQueue.size < 4) return signals to 0
        return signals to flushDigest()
    }

    /** Procesa la cola de cosas dichas en una sola llamada a Claude. */
    suspend fun flushDigest(): Int = withContext(Dispatchers.IO) {
        if (!settings.hasClaude) return@withContext 0
        val batch = engine.takeDigestQueue()
        if (batch.isEmpty()) return@withContext 0
        runCatching {
            val d = brain().digest(engine.current().profile, batch)
            engine.applyMemories(d)
            d.memories.size
        }.onFailure { e -> batch.forEach { engine.queueDigest(it) }; CrashLog.write("digest", e.toString()) }
            .getOrDefault(0)
    }

    // ==================== TU COPIA ====================

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
            self = s.selfModels.lastOrNull(),
            weather = s.weather,
            contacts = topContacts(s),
            missions = s.missions.filter { it.status == "activa" },
            days = s.days.takeLast(3),
            currentPlace = currentPlace(s)?.takeIf { it.known }?.label,
            guess = predictContext(s, System.currentTimeMillis()),
        )
    }

    private fun hooks(onDelta: ((String) -> Unit)?) = TwinHooks(
        recall = { q -> engine.relevantMemories(q, 12) },
        createMission = { goal, plan ->
            kotlinx.coroutines.runBlocking { engine.addMission(goal, plan) }.let { "Misión creada (id ${it.id}). La trabajaré por mi cuenta y le avisaré de avances." }
        },
        isAutomatic = { engine.automatic(it) },
        onDelta = onDelta,
    )

    /**
     * Habla con tu copia desde cualquier sitio (app, voz, notificación, reloj).
     * Guarda la conversación, aprende de ella y ejecuta lo que tenga permiso automático.
     */
    suspend fun askTwin(text: String, onDelta: ((String) -> Unit)? = null): String {
        engine.addChat(ChatMessage(fromUser = true, text = text.trim()))
        CoroutineScope(Dispatchers.IO).launch { runCatching { learnDeep(text, now = false) } }
        if (!settings.hasClaude) {
            val msg = "Para hablar necesito tu clave de Claude (Ajustes). Mientras tanto, ya aprendí de lo que me dijiste. 🍪"
            engine.addChat(ChatMessage(false, msg))
            return msg
        }
        return try {
            val ctx = twinContext(text)
            val history = engine.current().chat.toList()
            val reply = withContext(Dispatchers.IO) { brain().twin(ctx, history, hooks(onDelta?.let { d -> { s: String -> CoroutineScope(Dispatchers.Main).launch { d(s) }; Unit } })) }
            val ids = reply.actions.map { a -> engine.proposeAction(a).id.also { runIfAutomatic(a) } }
            engine.addChat(ChatMessage(false, reply.text, actions = ids))
            reply.text
        } catch (e: Exception) {
            CrashLog.write("twin", e.stackTraceToString())
            val msg = "⚠️ No pude responder: ${e.message}"
            engine.addChat(ChatMessage(false, msg))
            msg
        }
    }

    /** Ejecuta una acción si tiene permiso automático. */
    suspend fun runIfAutomatic(a: PendingAction) {
        if (!engine.automatic(a.type)) return
        runCatching { executor.run(this, a) }
            .onSuccess { engine.setActionStatus(a.id, "hecha", "$it (automático)") }
            .onFailure { engine.setActionStatus(a.id, "error", it.message) }
    }

    /** Mide qué tanto se parece tu copia a ti con tus respuestas guardadas. */
    suspend fun evaluateFidelity(): FidelityRun = withContext(Dispatchers.IO) {
        val qs = fidelityQuestions(engine.current())
        require(qs.size >= 3) { "Necesito al menos 6 rondas de «¿Qué haría yo?» para examinar a tu copia" }
        val brain = brain()
        val base = twinContext("")
        val scores = qs.map { q ->
            val ctx = base.copy(memories = memoriesWithoutAnswer(engine.relevantMemories(q.question), q))
            brain.judge(q.question, brain.guess(ctx, q.question), q.answer).first
        }
        FidelityRun(System.currentTimeMillis(), scores.average(), qs.size).also { engine.addFidelity(it) }
    }

    // ==================== MENTE (en segundo plano) ====================

    /** Diario de los días pasados, reflexión semanal y misiones. */
    suspend fun mindWork() = withContext(Dispatchers.IO) {
        if (!settings.hasClaude) return@withContext
        flushDigest()
        val brain = brain()
        engine.daysToWrite().take(2).forEach { day ->
            runCatching {
                val d = brain.writeDay(dayContext(engine.current(), day))
                if (d.summary.isNotBlank()) engine.addDay(DayEntry(day.toString(), d.summary, d.mood, d.highlights))
            }.onFailure { CrashLog.write("diario", it.toString()) }
        }
        if (engine.reflectionDue()) {
            runCatching { engine.addSelfModel(brain.reflect(reflectionContext(engine.current(), System.currentTimeMillis()))) }
                .onFailure { CrashLog.write("reflexión", it.toString()) }
        }
        dueMissions(engine.current(), System.currentTimeMillis()).take(2).forEach { m ->
            runCatching {
                val ctx = twinContext(m.goal)
                val (report, actions) = brain.runMission(ctx, m, hooks(null))
                actions.forEach { a -> engine.proposeAction(a); runIfAutomatic(a) }
                engine.reportMission(m.id, report)
            }.onFailure { CrashLog.write("misión", it.toString()) }
        }
    }

    // ==================== SENTIDOS Y MUNDO ====================

    fun spotifyApi(): SpotifyApi = SpotifyApi { validSpotifyToken() }

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

    @Volatile private var lastLocation: android.location.Location? = null

    /** Lee todos los sentidos activos. Devuelve el lugar al que acabas de llegar (si cambió). */
    suspend fun senseAll(): Place? = withContext(Dispatchers.IO) {
        fun <T> safe(tag: String, f: () -> T) = runCatching(f).onFailure { CrashLog.write(tag, it.toString()) }
        runCatching { syncSpotify() }
        if (settings.senseCalendar) safe("calendario") { kotlinx.coroutines.runBlocking { engine.learnCalendar(Sensors.readCalendar(this@CookieApp)) } }
        if (settings.senseHealth) runCatching {
            Sensors.readHealth(this@CookieApp)?.let {
                engine.learnHealth(it.nights, it.stepsToday, it.stepsAvg)
                engine.learnHeart(it.heart)
            }
        }
        if (settings.senseApps) safe("apps") { kotlinx.coroutines.runBlocking { engine.learnApps(Sensors.readUsage(this@CookieApp, engine.current().lastUsageSync)) } }
        if (settings.sensePhotos) safe("fotos") { kotlinx.coroutines.runBlocking { engine.learnPhotos(Sensors.readPhotos(this@CookieApp, engine.current().lastPhotoSync)) } }
        var arrived: Place? = null
        if (settings.senseLocation) runCatching {
            Sensors.currentLocation(this@CookieApp)?.let { loc ->
                lastLocation = loc
                val o = engine.observeLocation(loc.latitude, loc.longitude)
                if (o.arrived) arrived = o.place
            }
        }
        arrived
    }

    /** Clima (cada hora) y tráfico hacia tu próximo evento con dirección. */
    suspend fun worldWork() = withContext(Dispatchers.IO) {
        val s = engine.current()
        val here = lastLocation?.let { it.latitude to it.longitude }
            ?: currentPlace(s)?.let { it.lat to it.lon }
            ?: s.places.maxByOrNull { it.samples }?.let { it.lat to it.lon }
            ?: return@withContext
        if (System.currentTimeMillis() - (s.weather?.at ?: 0) > 3_600_000L) {
            runCatching { engine.setWeather(World.weather(here.first, here.second)) }.onFailure { CrashLog.write("clima", it.toString()) }
        }
        val key = settings.mapsKey ?: return@withContext
        val now = System.currentTimeMillis()
        s.upcoming.filter { !it.allDay && !it.location.isNullOrBlank() && it.start - now in 0..3 * 3_600_000L }.take(2).forEach { e ->
            val known = s.travel.firstOrNull { it.eventKey == eventKey(e) }
            if (known == null || now - known.at > 20 * 60_000L) {
                runCatching { World.travelMinutes(key, here.first, here.second, e.location!!, e.start - 30 * 60_000L) }
                    .getOrNull()?.let { engine.setTravel(World.estimate(eventKey(e), it)) }
            }
        }
    }

    suspend fun research(): Int = withContext(Dispatchers.IO) { engine.research(sources()) }

    private fun homeEntities(): List<String> {
        if (!settings.hasHome) return emptyList()
        homeCache?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return it.second }
        return executor.homeEntities().also { homeCache = System.currentTimeMillis() to it }
    }

    // ==================== AVISOS ====================

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tus novedades", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Resúmenes que Cookie investiga para ti" })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_NUDGES, "Se adelanta por ti", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Avisos según tu agenda, clima, sueño, dónde estás y tu rutina" })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_TWIN, "Tu copia", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Respuestas de tu copia cuando le hablas desde una notificación o el reloj" })
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
            .addAction(ReplyReceiver.action(this, 2))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(1, n)
    }

    /**
     * Aviso anticipado con: "Sí, hazlo" (si trae acción), 👍/👎 para que aprenda
     * si te sirve, y "Responder" para hablarle sin abrir la app (también desde el reloj).
     */
    @Suppress("MissingPermission")
    suspend fun notifyNudge(n: Nudge) {
        val id = n.key.hashCode()
        n.action?.let { a ->
            engine.proposeAction(a)
            if (engine.automatic(a.type)) { runIfAutomatic(a); if (!canNotify()) return }
        }
        if (!canNotify()) return
        val b = NotificationCompat.Builder(this, CHANNEL_NUDGES)
            .setSmallIcon(R.drawable.ic_stat_cookie)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setContentIntent(openAppIntent(requestCode = id))
            .setAutoCancel(true)
        n.action?.takeIf { !engine.automatic(it.type) }?.let { a ->
            b.addAction(0, "Sí, hazlo", openAppIntent({ it.putExtra(EXTRA_RUN_ACTION, a.id).putExtra(EXTRA_NOTIFICATION, id) }, id + 1))
        }
        b.addAction(0, "👍", NudgeFeedbackReceiver.intent(this, n.key, true, id))
        b.addAction(0, "👎", NudgeFeedbackReceiver.intent(this, n.key, false, id))
        NotificationManagerCompat.from(this).notify(id, b.build())
    }

    @Suppress("MissingPermission")
    fun notifyTwinReply(text: String) {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(this, CHANNEL_TWIN)
            .setSmallIcon(R.drawable.ic_stat_cookie)
            .setContentTitle("🍪 Tu copia")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(requestCode = 3))
            .addAction(ReplyReceiver.action(this, 4))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(3, n)
    }

    companion object {
        const val CHANNEL = "briefings"
        const val CHANNEL_NUDGES = "nudges"
        const val CHANNEL_TWIN = "twin"
        const val EXTRA_RUN_ACTION = "run_action"
        const val EXTRA_NOTIFICATION = "notification_id"
        const val EXTRA_VOICE = "voice"
        lateinit var instance: CookieApp
            private set
    }
}

/** 👍/👎 desde la notificación: Cookie aprende qué avisos te sirven. */
class NudgeFeedbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val useful = intent.getBooleanExtra("useful", false)
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra("id", 0))
        val app = context.applicationContext as CookieApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { app.engine.nudgeFeedback(key, useful); pending.finish() }
    }

    companion object {
        fun intent(ctx: Context, key: String, useful: Boolean, id: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, id * 2 + if (useful) 1 else 0,
            Intent(ctx, NudgeFeedbackReceiver::class.java).putExtra("key", key).putExtra("useful", useful).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
 * Modo autónomo: cada 30 min Cookie lee tus sentidos, mira el clima y el
 * tráfico, se adelanta con avisos que aprenden de tus reacciones, investiga,
 * escribe tu diario, reflexiona sobre quién eres y trabaja tus misiones.
 */
class CookieWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CookieApp
        val engine = app.engine
        val arrived = runCatching { app.senseAll() }.getOrNull()
        runCatching { app.worldWork() }
        engine.takeNudges(arrived).take(2).forEach { app.notifyNudge(it) }
        if (engine.researchDue()) runCatching { app.research() }.onFailure { engine.setError(it.message) }
        if (engine.briefingDue()) app.notifyBriefing(engine.deliver(5), engine.current().profile)
        runCatching { app.mindWork() }.onFailure { CrashLog.write("mente", it.toString()) }
        CookieWidget.refresh(app)
        return Result.success()
    }
}
