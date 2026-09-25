package com.aria.cookie

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.aria.cookie.core.AppSession
import com.aria.cookie.core.CalendarEvent
import com.aria.cookie.core.HeartSample
import com.aria.cookie.core.PhotoInfo
import com.aria.cookie.core.SleepNight
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * Los "sentidos" de Cookie: leen tu calendario, dónde estás, cómo dormiste y
 * qué apps usas. Cada uno necesita tu permiso y se puede apagar en Ajustes.
 */
object Sensors {

    fun granted(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    // ==================== CALENDARIO ====================

    val calendarPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    /** Eventos de los últimos 14 días y los próximos 7. */
    fun readCalendar(ctx: Context): List<CalendarEvent> {
        if (!granted(ctx, Manifest.permission.READ_CALENDAR)) return emptyList()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath((now - 14 * 86_400_000L).toString())
            .appendPath((now + 7 * 86_400_000L).toString())
            .build()
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY,
        )
        val out = mutableListOf<CalendarEvent>()
        ctx.contentResolver.query(uri, cols, null, null, CalendarContract.Instances.BEGIN)?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1)?.trim().orEmpty()
                if (title.isEmpty()) continue
                out += CalendarEvent(c.getLong(0), title, c.getLong(2), c.getLong(3), c.getString(4), c.getInt(5) == 1)
            }
        }
        return out
    }

    // ==================== UBICACIÓN ====================

    val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
    /** Antes de Android 10 el permiso de ubicación ya incluía el segundo plano. */
    fun hasBackgroundLocation(ctx: Context) =
        if (Build.VERSION.SDK_INT < 29) hasLocation(ctx) else granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** Una lectura de ubicación (sin Google Play Services). */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(ctx: Context): Location? {
        if (!hasLocation(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        val fresh = if (Build.VERSION.SDK_INT >= 30) {
            withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    lm.getCurrentLocation(provider, null, ctx.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                }
            }
        } else null
        return fresh ?: lm.getLastKnownLocation(provider)?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000 }
    }

    // ==================== SALUD (Health Connect) ====================

    val healthPermissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    fun healthAvailable(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    data class HealthData(val nights: List<SleepNight>, val stepsToday: Long?, val stepsAvg: Long?, val heart: List<HeartSample> = emptyList())

    suspend fun readHealth(ctx: Context): HealthData? {
        if (!healthAvailable(ctx)) return null
        val client = HealthConnectClient.getOrCreate(ctx)
        val granted = client.permissionController.getGrantedPermissions()
        if (granted.isEmpty()) return null
        val now = Instant.now()
        val nights = if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
            client.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(now.minusSeconds(14 * 86_400L), now))
            ).records.map { SleepNight(it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
        } else emptyList()
        var today: Long? = null
        var avg: Long? = null
        if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
            val zone = ZoneId.systemDefault()
            val midnight = LocalDate.now().atStartOfDay(zone).toInstant()
            today = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(midnight, now)))[StepsRecord.COUNT_TOTAL]
            val week = client.aggregate(
                AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(midnight.minusSeconds(7 * 86_400L), midnight))
            )[StepsRecord.COUNT_TOTAL]
            avg = week?.div(7)
        }
        val heart = if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) {
            client.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(now.minusSeconds(2 * 86_400L), now)))
                .records.flatMap { r -> r.samples.map { HeartSample(it.time.toEpochMilli(), it.beatsPerMinute) } }
        } else emptyList()
        return HealthData(nights, today, avg, heart)
    }

    // ==================== USO DE APPS ====================

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Sesiones de uso (app en primer plano) desde [since]. */
    fun readUsage(ctx: Context, since: Long): List<AppSession> {
        if (!hasUsageAccess(ctx)) return emptyList()
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val from = maxOf(since, now - 24 * 3_600_000L)
        val events = usm.queryEvents(from, now)
        val open = mutableMapOf<String, Long>()
        val out = mutableListOf<AppSession>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> open[e.packageName] = e.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED -> open.remove(e.packageName)?.let { start ->
                    if (e.packageName != ctx.packageName) out += session(ctx, e.packageName, start, e.timeStamp)
                }
            }
        }
        return out
    }

    private fun session(ctx: Context, pkg: String, start: Long, end: Long): AppSession {
        val pm = ctx.packageManager
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        val label = info?.let { pm.getApplicationLabel(it).toString() } ?: pkg.substringAfterLast('.')
        val category = info?.let { categoryName(it.category) }
        return AppSession(pkg, label, category, start, end)
    }

    private fun categoryName(c: Int): String? = when (c) {
        ApplicationInfo.CATEGORY_GAME -> "videojuegos"
        ApplicationInfo.CATEGORY_AUDIO -> "música"
        ApplicationInfo.CATEGORY_VIDEO -> "series y vídeos"
        ApplicationInfo.CATEGORY_IMAGE -> "fotografía"
        ApplicationInfo.CATEGORY_SOCIAL -> "redes sociales"
        ApplicationInfo.CATEGORY_NEWS -> "noticias"
        ApplicationInfo.CATEGORY_MAPS -> "viajes"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productividad"
        else -> null
    }

    // ==================== FOTOS ====================

    val photoPermission get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Solo la fecha de cada foto (no se lee la imagen). */
    fun readPhotos(ctx: Context, since: Long): List<PhotoInfo> {
        if (!granted(ctx, photoPermission)) return emptyList()
        val from = maxOf(since, System.currentTimeMillis() - 30 * 86_400_000L)
        val out = mutableListOf<PhotoInfo>()
        ctx.contentResolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Images.Media.DATE_TAKEN),
            "${android.provider.MediaStore.Images.Media.DATE_TAKEN} > ?", arrayOf(from.toString()),
            "${android.provider.MediaStore.Images.Media.DATE_TAKEN} ASC",
        )?.use { c -> while (c.moveToNext()) out += PhotoInfo(c.getLong(0)) }
        return out
    }

    // ==================== MOVIMIENTO ====================

    val activityPermission get() = if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACTIVITY_RECOGNITION else "com.google.android.gms.permission.ACTIVITY_RECOGNITION"

    /** Pide al sistema que avise cuando empiezas a caminar, correr, ir en bici o en coche. */
    @SuppressLint("MissingPermission")
    fun startActivityRecognition(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29 && !granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)) return false
        val types = listOf(
            com.google.android.gms.location.DetectedActivity.WALKING, com.google.android.gms.location.DetectedActivity.RUNNING,
            com.google.android.gms.location.DetectedActivity.ON_BICYCLE, com.google.android.gms.location.DetectedActivity.IN_VEHICLE,
        )
        val transitions = types.map {
            com.google.android.gms.location.ActivityTransition.Builder().setActivityType(it)
                .setActivityTransition(com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
        }
        val pi = android.app.PendingIntent.getBroadcast(
            ctx, 5, android.content.Intent(ctx, MovementReceiver::class.java),
            android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return runCatching {
            com.google.android.gms.location.ActivityRecognition.getClient(ctx)
                .requestActivityTransitionUpdates(com.google.android.gms.location.ActivityTransitionRequest(transitions), pi)
            true
        }.getOrDefault(false)
    }

    // ==================== NOTIFICACIONES ====================

    fun hasNotificationAccess(ctx: Context) =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
}
