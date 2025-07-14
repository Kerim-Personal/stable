package com.codenzi.snapnote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.net.toUri
import androidx.preference.PreferenceManager

class NoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.note_widget_layout)

            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val backgroundDrawableName = sharedPrefs.getString("widget_background_selection", "widget_background")
            val backgroundResId = getBackgroundResource(backgroundDrawableName)
            views.setInt(R.id.widget_container_layout, "setBackgroundResource", backgroundResId)

            val serviceIntent = Intent(context, NoteWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = this.toUri(Intent.URI_INTENT_SCHEME).toUri()
            }
            views.setRemoteAdapter(R.id.lv_widget_notes, serviceIntent)
            views.setEmptyView(R.id.lv_widget_notes, R.id.tv_widget_empty)

            val clickIntent = Intent(context, NoteActivity::class.java)
            val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val clickPendingIntent = PendingIntent.getActivity(
                context, 0, clickIntent, PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag
            )
            views.setPendingIntentTemplate(R.id.lv_widget_notes, clickPendingIntent)

            val newNoteIntent = Intent().apply {
                val component = ComponentName("com.codenzi.snapnote", "com.codenzi.snapnote.WidgetNoteActivity")
                setComponent(component)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("FROM_WIDGET", true)
            }

            val newNotePendingIntent = PendingIntent.getActivity(
                context, 1, newNoteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_widget_new, newNotePendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
            appWidgetManager.notifyAppWidgetViewDataChanged(intArrayOf(appWidgetId), R.id.lv_widget_notes)
        }

        private fun getBackgroundResource(name: String?): Int {
            return when (name) {
                "widget_background" -> R.drawable.widget_background
                "bg1" -> R.drawable.bg1
                "bg2" -> R.drawable.bg2
                "bg3" -> R.drawable.bg3
                "bg4" -> R.drawable.bg4
                "bg5" -> R.drawable.bg5
                "bg6" -> R.drawable.bg6
                "bg7" -> R.drawable.bg7
                "bg8" -> R.drawable.bg8
                "bg9" -> R.drawable.bg9
                "bg10" -> R.drawable.bg10
                else -> R.drawable.widget_background
            }
        }
    }
}