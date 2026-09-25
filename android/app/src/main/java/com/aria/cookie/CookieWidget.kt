package com.aria.cookie

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.aria.cookie.core.currentPlace
import com.aria.cookie.core.greeting

/**
 * Widget: tu saludo, lo que Cookie cree que harás ahora, tu próximo evento y
 * un botón para hablarle por voz.
 */
class CookieWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        fun refresh(context: Context) {
            val app = context.applicationContext as CookieApp
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CookieWidget::class.java))
            if (ids.isEmpty()) return
            val s = app.engine.current()
            val now = System.currentTimeMillis()
            val lines = buildList {
                s.profile.predictNext(now)?.let { add("🔮 Ahora: ${it.activity}") }
                s.upcoming.firstOrNull { it.start > now }?.let {
                    add("📅 %s %s".format(com.aria.cookie.core.zoned(it.start).toLocalTime().toString().take(5), it.title))
                }
                currentPlace(s)?.takeIf { it.known }?.let { add("📍 ${it.label}") }
                s.profile.music.nowPlaying?.let { add("🎧 $it") }
                val unread = s.inbox.count { !it.delivered }
                if (unread > 0) add("📰 $unread novedades para ti")
            }.ifEmpty { listOf("Cuéntame algo de ti 🍪") }

            val views = RemoteViews(context.packageName, R.layout.widget_cookie).apply {
                setTextViewText(R.id.widget_title, "🍪 " + greeting(s.profile, now))
                setTextViewText(R.id.widget_body, lines.take(3).joinToString("\n"))
                setOnClickPendingIntent(R.id.widget_root, app.openAppIntent(requestCode = 100))
                setOnClickPendingIntent(R.id.widget_mic, app.openAppIntent({ it.putExtra(CookieApp.EXTRA_VOICE, true) }, 101))
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
