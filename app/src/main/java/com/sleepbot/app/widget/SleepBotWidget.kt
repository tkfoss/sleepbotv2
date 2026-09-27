package com.sleepbot.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.sleepbot.app.R
import com.sleepbot.app.app

/**
 * Pushes the current punch state to every 1x1 home-screen widget (legacy `SleepBotWidget.updateWidgets`).
 * Called by [com.sleepbot.app.session.SleepSession] after every punch.
 */
object SleepBotWidget {
    fun update(context: Context) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        val ids = mgr.getAppWidgetIds(ComponentName(context, SleepBotWidgetProvider::class.java))
        if (ids.isEmpty()) return
        mgr.updateAppWidget(ids, views(context))
    }

    internal fun views(context: Context): RemoteViews {
        val awake = context.app.prefs.isAwake
        val rv = RemoteViews(context.packageName, R.layout.widget_sleepbot)
        rv.setImageViewResource(R.id.widget_image, if (awake) R.drawable.widget_gotosleep else R.drawable.widget_wakeup)
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, PunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        rv.setOnClickPendingIntent(R.id.widget_image, pi)
        return rv
    }
}

/** Classic AppWidgetProvider; updates are pushed only (updatePeriodMillis = 0). */
class SleepBotWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetManager.updateAppWidget(appWidgetIds, SleepBotWidget.views(context))
    }
}
