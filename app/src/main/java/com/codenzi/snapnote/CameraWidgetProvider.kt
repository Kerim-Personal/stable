package com.codenzi.snapnote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.preference.PreferenceManager

class CameraWidgetProvider : AppWidgetProvider() {
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
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val remoteViews = RemoteViews(context.packageName, R.layout.widget_camera)

            // DÜZELTME: Gereksiz 'let' kaldırıldı.
            val intent = Intent().apply {
                val component = ComponentName("com.codenzi.snapnote", "com.codenzi.snapnote.WidgetCameraNoteActivity")
                setComponent(component)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                mutabilityFlag or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // DÜZELTME: getIdentifier yerine doğrudan kaynak ID'lerini kullanan daha verimli bir yapı.
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val backgroundDrawableName = sharedPrefs.getString("widget_background_selection", "widget_background")
            val backgroundResId = getBackgroundResource(backgroundDrawableName)
            remoteViews.setInt(R.id.camera_widget_container, "setBackgroundResource", backgroundResId)

            remoteViews.setOnClickPendingIntent(R.id.btn_take_photo, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
        }

        /**
         * SharedPreferences'tan gelen string key'e karşılık gelen drawable resource ID'sini döndürür.
         * Bu yöntem, getIdentifier kullanmaktan daha performanslı ve güvenlidir.
         */
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
                else -> R.drawable.widget_background // Varsayılan
            }
        }
    }
}