package com.codenzi.snapnote

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.graphics.toColorInt
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Widget için asenkron ve ANR'a dayanıklı RemoteViewsFactory.
 * 'runBlocking' kaldırılarak veritabanı işlemleri arayüzü kilitlemeden
 * arka planda gerçekleştirilir.
 */
class NoteWidgetItemFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    // Veri listesinin birden fazla thread'den güvenli erişimi için @Volatile.
    @Volatile
    private var notes: List<Note> = emptyList()
    private val noteDao = NoteDatabase.getDatabase(context).noteDao()
    private val gson = Gson()
    // Arka plan işlemleri için kendi CoroutineScope'umuzu oluşturuyoruz.
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        // Factory ilk oluşturulduğunda veri yüklemesini tetikliyoruz.
        fetchData()
    }

    override fun onDataSetChanged() {
        // Widget'ın güncellenmesi gerektiğinde (örneğin bir not değiştiğinde)
        // veri setini yeniden yüklemek için bu metod tetiklenir.
        fetchData()
    }

    /**
     * Veritabanından notları asenkron olarak çeker ve widget'ı günceller.
     */
    private fun fetchData() {
        // Veritabanı sorgusunu IO thread'inde asenkron olarak başlatıyoruz.
        scope.launch {
            try {
                val newNotes = noteDao.getNotesForWidget()

                // Ana iş parçacığına geçerek 'notes' listesini güncelliyoruz.
                // Bu, getViewAt metodunun her zaman tutarlı veri görmesini sağlar.
                withContext(Dispatchers.Main) {
                    notes = newNotes
                    // Veri seti değiştiği için widget'ı güncellemesi gerektiğini sisteme bildiriyoruz.
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val componentName = ComponentName(context, NoteWidgetProvider::class.java)
                    val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.lv_widget_notes)
                }
            } catch (e: Exception) {
                // Hata durumunda loglama yapılabilir.
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        // Factory yok edildiğinde CoroutineScope'u iptal ederek
        // hafıza sızıntılarını önlüyoruz.
        scope.cancel()
        notes = emptyList()
    }

    override fun getCount(): Int = notes.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_note_item)

        if (position >= notes.size) {
            return views // Güvenlik kontrolü
        }

        try {
            val note = notes[position]

            if (note.title.isNotBlank()) {
                views.setViewVisibility(R.id.tv_widget_item_title, View.VISIBLE)
                views.setTextViewText(R.id.tv_widget_item_title, note.title)
            } else {
                views.setViewVisibility(R.id.tv_widget_item_title, View.GONE)
            }

            val contentPreview: String = try {
                val noteContent = gson.fromJson(note.content, NoteContent::class.java)
                val textPart = if (noteContent.text.isNotBlank()) Html.fromHtml(noteContent.text, Html.FROM_HTML_MODE_LEGACY).toString().trim() else ""
                val checklistPart = if (noteContent.checklist.isNotEmpty()) {
                    val checkedCount = noteContent.checklist.count { it.isChecked }
                    context.getString(R.string.checklist_summary_preview, checkedCount, noteContent.checklist.size)
                } else ""
                if (textPart.isNotEmpty() && checklistPart.isNotEmpty()) "$textPart\n$checklistPart" else textPart + checklistPart
            } catch (e: JsonSyntaxException) {
                Html.fromHtml(note.content, Html.FROM_HTML_MODE_LEGACY).toString()
            }

            views.setTextViewText(R.id.tv_widget_item_content, contentPreview)

            try {
                views.setInt(R.id.widget_item_container, "setBackgroundColor", note.color.toColorInt())
            } catch (e: Exception) {
                views.setInt(R.id.widget_item_container, "setBackgroundColor", Color.WHITE)
            }

            val fillInIntent = Intent().apply {
                val extras = Bundle()
                extras.putInt("NOTE_ID", note.id)
                putExtras(extras)
            }
            views.setOnClickFillInIntent(R.id.widget_item_container, fillInIntent)
        } catch (e: Exception) {
            views.setTextViewText(R.id.tv_widget_item_title, "Hata")
            views.setTextViewText(R.id.tv_widget_item_content, "Not yüklenemedi.")
        }

        return views
    }

    override fun getLoadingView(): RemoteViews? {
        // İsteğe bağlı: Veriler yüklenirken bir yükleme göstergesi gösterebilirsiniz.
        // Bunun için R.layout.widget_loading_item gibi basit bir layout oluşturmanız yeterlidir.
        return null
    }

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = notes.getOrNull(position)?.id?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}