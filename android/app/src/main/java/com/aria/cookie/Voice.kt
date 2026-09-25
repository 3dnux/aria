package com.aria.cookie

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Voz de Cookie: elige la voz en español más natural del teléfono y habla
 * frase a frase mientras Claude escribe (no espera a tener toda la respuesta).
 */
class CookieVoice(ctx: Context, private val onIdle: () -> Unit = {}) {
    private var ready = false
    private val pending = StringBuilder()
    private var queued = 0
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            chooseVoice()
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (--queued <= 0) { queued = 0; onIdle() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { if (--queued <= 0) { queued = 0; onIdle() } }
        })
    }

    /** La voz en español de mayor calidad disponible (las de red suenan más humanas). */
    private fun chooseVoice() {
        val spanish = tts.voices.orEmpty().filter { it.locale.language == "es" && !it.features.contains("notInstalled") }
        val best = spanish.sortedWith(
            compareByDescending<Voice> { it.locale.country == Locale.getDefault().country }
                .thenByDescending { it.quality }
                .thenBy { it.latency }
        ).firstOrNull()
        if (best != null) tts.voice = best else tts.language = Locale("es")
        tts.setSpeechRate(1.05f)
    }

    /** Añade texto que llega en streaming; habla cada frase completa en cuanto existe. */
    fun feed(delta: String) {
        pending.append(delta)
        val text = pending.toString()
        val cut = text.indexOfLast { it == '.' || it == '?' || it == '!' || it == '\n' || it == '…' }
        if (cut >= 0 && cut >= 20) {
            speakNow(text.substring(0, cut + 1))
            pending.delete(0, cut + 1)
        }
    }

    /** Habla lo que quede pendiente. */
    fun flush() {
        if (pending.isNotBlank()) speakNow(pending.toString())
        pending.clear()
        if (queued == 0) onIdle()
    }

    fun say(text: String) { pending.clear(); stop(); speakNow(text) }

    private fun speakNow(raw: String) {
        val clean = raw.replace(Regex("[*_#>`]|https?://\\S+"), "").trim()
        if (clean.isEmpty() || !ready) return
        queued++
        tts.speak(clean, TextToSpeech.QUEUE_ADD, null, "c${System.nanoTime()}")
    }

    fun stop() { tts.stop(); queued = 0 }
    val speaking get() = tts.isSpeaking
    fun shutdown() = tts.shutdown()
}

/**
 * Escucha continua con el reconocedor de voz del teléfono (sin ventanas).
 * En modo conversación vuelve a escuchar cuando Cookie termina de hablar.
 * En modo "Oye Cookie" solo reacciona si oye la palabra de activación.
 */
class Listener(private val ctx: Context, private val onPartial: (String) -> Unit, private val onFinal: (String) -> Unit) {
    private var sr: SpeechRecognizer? = null
    var active = false
        private set

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return
        active = true
        if (sr == null) sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply { setRecognitionListener(listener) }
        sr?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        )
    }

    fun stop() {
        active = false
        sr?.cancel()
    }

    fun destroy() {
        active = false
        sr?.destroy()
        sr = null
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) onFinal(text) else if (active) start()
        }
        override fun onPartialResults(partial: Bundle?) {
            partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
        }
        override fun onError(error: Int) {
            // Sin permiso o sin servicio no tiene sentido reintentar.
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == 10 /* ERROR_LANGUAGE_NOT_SUPPORTED */) { active = false; return }
            if (active) android.os.Handler(ctx.mainLooper).postDelayed({ if (active) start() }, 600)
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

private val WAKE = Regex("(?i)\\b(oye|hey|ok|hola)?,?\\s*(cookie|cuki|kuki|cooki|kookie)\\b[,.!]?\\s*")

/** Devuelve la orden tras la palabra de activación ("oye cookie, pon música" → "pon música"), o null. */
fun afterWakeWord(text: String): String? {
    val m = WAKE.find(text) ?: return null
    return text.substring(m.range.last + 1).trim()
}

/**
 * Modo manos libres: servicio en primer plano que escucha "Oye Cookie",
 * habla con tu copia y te responde en voz alta, sin tocar el teléfono.
 * Experimental: el reconocedor del sistema puede emitir sonidos al reiniciarse.
 */
class HandsFreeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var voice: CookieVoice
    private lateinit var listener: Listener
    private var awaitingCommand = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val main = android.os.Handler(mainLooper)
        // El reconocedor solo puede usarse desde el hilo principal.
        voice = CookieVoice(this) { main.post { if (::listener.isInitialized) listener.start() } }
        listener = Listener(this, onPartial = {}, onFinal = ::heard)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val stop = PendingIntent.getService(this, 7, Intent(this, HandsFreeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CookieApp.CHANNEL_NUDGES)
            .setSmallIcon(R.drawable.ic_stat_cookie)
            .setContentTitle("🎙️ Cookie te escucha")
            .setContentText("Di «Oye Cookie» y lo que necesites")
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Parar", stop)
            .build()
        ServiceCompat.startForeground(this, 42, n,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        listener.start()
        // No se reinicia solo: Android no permite abrir el micrófono en segundo plano sin que lo pidas tú.
        return START_NOT_STICKY
    }

    private fun heard(text: String) {
        val command = if (awaitingCommand) text else afterWakeWord(text)
        when {
            command == null -> listener.start() // no era para Cookie
            command.isBlank() -> { awaitingCommand = true; voice.say("¿Sí?") }
            else -> {
                awaitingCommand = false
                listener.stop()
                val app = application as CookieApp
                scope.launch {
                    runCatching { app.askTwin(command, voice::feed) }
                        .onFailure { voice.say("No pude responder: ${it.message}") }
                    voice.flush()
                }
            }
        }
    }

    override fun onDestroy() {
        listener.destroy()
        voice.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        fun start(ctx: Context) = androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, HandsFreeService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, HandsFreeService::class.java))
    }
}

/**
 * Responder a Cookie desde la notificación (o desde el reloj, que refleja
 * las notificaciones del teléfono) sin abrir la app.
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString() ?: return
        // Claude puede tardar más de lo que Android permite a un receptor: se delega a WorkManager.
        androidx.work.WorkManager.getInstance(context).enqueue(
            androidx.work.OneTimeWorkRequestBuilder<TwinReplyWorker>()
                .setInputData(androidx.work.workDataOf("text" to text))
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        )
    }

    companion object {
        const val KEY_TEXT = "reply_text"

        /** Acción "Responder" que se añade a las notificaciones de Cookie. */
        fun action(ctx: Context, requestCode: Int): NotificationCompat.Action {
            val pi = PendingIntent.getBroadcast(
                ctx, requestCode, Intent(ctx, ReplyReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Action.Builder(0, "Responder a Cookie", pi)
                .addRemoteInput(RemoteInput.Builder(KEY_TEXT).setLabel("Háblale a tu copia").build())
                .setAllowGeneratedReplies(true)
                .build()
        }
    }
}

/** Responde en segundo plano a lo que le dijiste desde una notificación o el reloj. */
class TwinReplyWorker(ctx: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val app = applicationContext as CookieApp
        val text = inputData.getString("text").orEmpty()
        if (text.isBlank()) return androidx.work.ListenableWorker.Result.success()
        app.notifyTwinReply(runCatching { app.askTwin(text) }.getOrElse { "No pude responder: ${it.message}" })
        return androidx.work.ListenableWorker.Result.success()
    }

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, CookieApp.CHANNEL_TWIN)
            .setSmallIcon(R.drawable.ic_stat_cookie).setContentTitle("🍪 Pensando como tú…").setSilent(true).build()
        return androidx.work.ForegroundInfo(44, n)
    }
}
