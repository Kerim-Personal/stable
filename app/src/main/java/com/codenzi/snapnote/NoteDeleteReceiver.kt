package com.codenzi.snapnote

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NoteDeleteReceiver : BroadcastReceiver() {

    companion object {
        // DÜZELTME: Eylem adı doğru paket adıyla güncellendi.
        const val ACTION_DELETE_NOTE = "com.codenzi.snapnote.ACTION_DELETE_NOTE"
        const val EXTRA_NOTE_ID = "EXTRA_NOTE_ID"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DELETE_NOTE) {
            val noteId = intent.getIntExtra(EXTRA_NOTE_ID, -1)
            if (noteId == -1) return

            val dao = NoteDatabase.getDatabase(context).noteDao()
            val pendingResult: PendingResult = goAsync()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    dao.hardDeleteById(noteId)

                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val componentName = ComponentName(context, NoteWidgetProvider::class.java)
                    val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                    appWidgetIds.forEach { appWidgetId ->
                        NoteWidgetProvider.updateAppWidget(context, appWidgetManager, appWidgetId)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}