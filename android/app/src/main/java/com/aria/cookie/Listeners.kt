package com.aria.cookie

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Recibe los cambios de actividad física que detecta el teléfono. */
class MovementReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val app = context.applicationContext as CookieApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            result.transitionEvents.forEach { e ->
                val type = when (e.activityType) {
                    DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> "WALKING"
                    DetectedActivity.RUNNING -> "RUNNING"
                    DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
                    DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
                    else -> null
                }
                type?.let { app.engine.learnMovement(it) }
            }
            pending.finish()
        }
    }
}

/**
 * Lee las notificaciones de apps de mensajes y correo para saber con quién
 * hablas y de qué temas, SIN guardar el texto de los mensajes.
 */
class CookieNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = applicationContext as CookieApp
        if (!app.settings.senseNotifications || sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        val isMessage = n.category == Notification.CATEGORY_MESSAGE || n.category == Notification.CATEGORY_EMAIL ||
            sbn.packageName in MESSAGING
        if (!isMessage || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val extras = n.extras
        val sender = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName.substringAfterLast('.'))
        // Un grupo de chat puede mandar decenas por minuto: como mucho una vez cada 2 min por remitente.
        val key = "${sbn.packageName}/$sender"
        val last = lastSeen[key] ?: 0
        if (sbn.postTime - last < 120_000) return
        lastSeen[key] = sbn.postTime
        scope.launch { app.engine.learnNotification(label, sender, text, sbn.postTime) }
    }

    private val lastSeen = mutableMapOf<String, Long>()

    companion object {
        private val MESSAGING = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca", "com.instagram.android",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "org.thoughtcrime.securesms",
            "com.discord", "com.slack", "com.google.android.gm", "com.microsoft.office.outlook", "com.microsoft.teams",
        )
    }
}
