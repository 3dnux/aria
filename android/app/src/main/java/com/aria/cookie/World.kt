package com.aria.cookie

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.aria.cookie.core.TravelEstimate
import com.aria.cookie.core.Weather
import com.aria.cookie.core.http
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** El mundo exterior: clima y tráfico. */
object World {

    /** Clima actual y del día con Open-Meteo (gratis, sin clave). */
    fun weather(lat: Double, lon: Double): Weather {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", "%.3f".format(java.util.Locale.US, lat))
            .addQueryParameter("longitude", "%.3f".format(java.util.Locale.US, lon))
            .addQueryParameter("current", "temperature_2m,weather_code")
            .addQueryParameter("hourly", "precipitation_probability")
            .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min")
            .addQueryParameter("forecast_days", "1")
            .addQueryParameter("timezone", "auto")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("Clima no disponible (${resp.code})")
            return parseWeather(resp.body!!.string(), System.currentTimeMillis())
        }
    }

    fun parseWeather(json: String, now: Long): Weather {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val hourly = o.optJSONObject("hourly")
        val zone = runCatching { ZoneId.of(o.optString("timezone")) }.getOrDefault(ZoneId.systemDefault())
        var rain = 0
        if (hourly != null) {
            val times = hourly.getJSONArray("time")
            val probs = hourly.getJSONArray("precipitation_probability")
            for (i in 0 until times.length()) {
                val t = LocalDateTime.parse(times.getString(i)).atZone(zone).toInstant().toEpochMilli()
                if (t in now - 3_600_000L..now + 3 * 3_600_000L && !probs.isNull(i)) rain = maxOf(rain, probs.getInt(i))
            }
        }
        val daily = o.optJSONObject("daily")
        return Weather(
            temperature = cur.getDouble("temperature_2m"),
            code = cur.getInt("weather_code"),
            rainProbNext3h = rain,
            maxToday = daily?.getJSONArray("temperature_2m_max")?.optDouble(0) ?: 0.0,
            minToday = daily?.getJSONArray("temperature_2m_min")?.optDouble(0) ?: 0.0,
            at = now,
        )
    }

    /**
     * Tiempo de viaje con tráfico real hasta la dirección de un evento
     * (Google Routes API; necesita tu clave en Ajustes).
     */
    fun travelMinutes(apiKey: String, fromLat: Double, fromLon: Double, destination: String, departure: Long): Int? {
        val body = JSONObject()
            .put("origin", JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", fromLat).put("longitude", fromLon))))
            .put("destination", JSONObject().put("address", destination))
            .put("travelMode", "DRIVE")
            .put("routingPreference", "TRAFFIC_AWARE")
            .put("departureTime", Instant.ofEpochMilli(maxOf(departure, System.currentTimeMillis() + 60_000)).toString())
            .toString()
        val req = Request.Builder().url("https://routes.googleapis.com/directions/v2:computeRoutes")
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask", "routes.duration")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val routes = JSONObject(resp.body!!.string()).optJSONArray("routes") ?: return null
            val dur = routes.optJSONObject(0)?.optString("duration")?.removeSuffix("s")?.toLongOrNull() ?: return null
            return (dur / 60).toInt().coerceAtLeast(1)
        }
    }

    fun estimate(eventKey: String, minutes: Int) = TravelEstimate(eventKey, minutes, System.currentTimeMillis())
}

/** Registro de errores en el teléfono para poder diagnosticar fallos. */
object CrashLog {
    private const val MAX = 200_000L
    private lateinit var file: File

    fun install(ctx: Context) {
        file = File(File(ctx.filesDir, "diag").apply { mkdirs() }, "diagnostico.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write("CRASH", "${t.name}: ${e.stackTraceToString()}") }
            previous?.uncaughtException(t, e)
        }
    }

    @Synchronized
    fun write(tag: String, msg: String) {
        if (!::file.isInitialized) return
        runCatching {
            if (file.length() > MAX) file.writeText(file.readText().takeLast((MAX / 2).toInt()))
            file.appendText("${Instant.now()} [$tag] ${msg.take(4000)}\n")
        }
    }

    /** Comparte el registro (no incluye tu perfil ni tus claves). */
    fun share(ctx: Context) {
        if (!::file.isInitialized || !file.exists()) file.writeText("Sin errores registrados.\n")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Enviar diagnóstico de Cookie",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
