package com.plehooo.cinta

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class CintaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CintaWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            AppWidgetManager.ACTION_APPWIDGET_ENABLED -> wake(context)
        }
    }

    // Widget dipasang / dipicu sistem: langsung hidupkan sinkronisasi walau aplikasi tidak dibuka.
    private fun wake(context: Context) {
        val app = context.applicationContext
        SyncScheduler.schedule(app)
        SyncScheduler.enqueueNow(app)
        if (SupabaseProvider.configured) RealtimeService.start(app)
    }
}
