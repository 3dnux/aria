package com.aria.cookie

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aria.cookie.core.ActionTypes
import com.aria.cookie.core.PendingAction
import com.aria.cookie.core.http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Ejecuta lo que tu copia propuso, una vez que lo confirmas. Las acciones que
 * abren otra app (alarma, calendario, mensaje) te dejan revisar antes de guardar.
 */
class ActionExecutor(private val app: CookieApp) {

    /** Devuelve un texto con el resultado; lanza excepción si falla. */
    suspend fun run(ctx: Context, a: PendingAction): String {
        val p = a.params
        return when (a.type) {
            ActionTypes.PLAY_MUSIC -> playMusic(ctx, p["consulta"].orEmpty())
            ActionTypes.ALARM -> {
                ctx.launch(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, p["hora"]?.toIntOrNull() ?: error("Falta la hora"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, p["minuto"]?.toIntOrNull() ?: 0)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, p["etiqueta"] ?: "Cookie")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                "Alarma puesta"
            }
            ActionTypes.REMINDER -> {
                val minutes = p["minutos"]?.toLongOrNull()?.coerceAtLeast(1) ?: 60
                WorkManager.getInstance(ctx).enqueue(
                    OneTimeWorkRequestBuilder<ReminderWorker>()
                        .setInitialDelay(minutes, TimeUnit.MINUTES)
                        .setInputData(workDataOf("text" to p["texto"].orEmpty()))
                        .build()
                )
                "Te lo recordaré en $minutes min"
            }
            ActionTypes.EVENT -> {
                val start = runCatching { LocalDateTime.parse(p["inicio"]).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
                    .getOrElse { error("No entendí la fecha «${p["inicio"]}»") }
                val dur = p["duracion_min"]?.toLongOrNull() ?: 60
                ctx.launch(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, p["titulo"])
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + dur * 60_000)
                    .putExtra(CalendarContract.Events.EVENT_LOCATION, p["lugar"]))
                "Abrí el calendario para que lo guardes"
            }
            ActionTypes.MESSAGE -> {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, p["texto"])
                ctx.launch(Intent.createChooser(send, "Enviar${p["para"]?.let { " a $it" } ?: ""}"))
                "Borrador listo para enviar"
            }
            ActionTypes.OPEN -> { ctx.launch(Intent(Intent.ACTION_VIEW, Uri.parse(p["url"]))); "Abierto" }
            ActionTypes.HOME -> homeAssistant(p["servicio"].orEmpty(), p["entidad"].orEmpty(), p["datos"])
            else -> error("No sé hacer «${a.type}»")
        }
    }

    private suspend fun playMusic(ctx: Context, query: String): String {
        if (query.isBlank()) error("¿Qué pongo?")
        if (app.settings.spotifyConnected) {
            val uri = withContext(Dispatchers.IO) { runCatching { app.spotifyApi().play(query) } }
            uri.onSuccess { if (it == null) return "Sonando «$query» en Spotify" }
            // Sin Premium o sin dispositivo activo: se abre en la app de Spotify.
            uri.getOrNull()?.let { ctx.launch(Intent(Intent.ACTION_VIEW, Uri.parse(it))); return "Abrí «$query» en Spotify" }
        }
        ctx.launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))))
        return "Busqué «$query» en Spotify"
    }

    private suspend fun homeAssistant(service: String, entity: String, data: String?): String = withContext(Dispatchers.IO) {
        val s = app.settings
        if (!s.hasHome) error("Configura Home Assistant en Ajustes")
        val (domain, name) = service.split('.', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: error("Servicio no válido: $service")
        val body = JSONObject(data?.takeIf { it.isNotBlank() } ?: "{}").put("entity_id", entity).toString()
        val req = Request.Builder().url(s.homeUrl.trimEnd('/') + "/api/services/$domain/$name")
            .header("Authorization", "Bearer ${s.homeToken}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { if (!it.isSuccessful) error("Home Assistant respondió ${it.code}") }
        "Hecho: $service en $entity"
    }

    /** Lista de dispositivos de Home Assistant para que la copia sepa qué hay. */
    fun homeEntities(): List<String> {
        val s = app.settings
        if (!s.hasHome) return emptyList()
        return runCatching {
            val req = Request.Builder().url(s.homeUrl.trimEnd('/') + "/api/states").header("Authorization", "Bearer ${s.homeToken}").build()
            http.newCall(req).execute().use { resp ->
                val arr = org.json.JSONArray(resp.body?.string().orEmpty())
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { it.getString("entity_id").substringBefore('.') in setOf("light", "switch", "climate", "media_player", "fan", "cover", "scene", "script", "lock") }
                    .map { o ->
                        val name = o.optJSONObject("attributes")?.optString("friendly_name").orEmpty()
                        "${o.getString("entity_id")} (${name.ifBlank { "?" }}: ${o.optString("state")})"
                    }
            }
        }.getOrDefault(emptyList())
    }

    private fun Context.launch(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            error("No hay ninguna app para esto en tu teléfono")
        }
    }
}

/** Notifica un recordatorio que pediste a tu copia. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CookieApp
        val text = inputData.getString("text").orEmpty()
        if (!app.canNotify()) return Result.success()
        val n = NotificationCompat.Builder(app, CookieApp.CHANNEL_NUDGES)
            .setSmallIcon(R.drawable.ic_stat_cookie)
            .setContentTitle("🔔 Te lo recuerdo")
            .setContentText(text)
            .setContentIntent(app.openAppIntent())
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(app).notify(text.hashCode(), n)
        return Result.success()
    }
}
