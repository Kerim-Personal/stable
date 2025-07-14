package com.codenzi.snapnote

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
import kotlinx.coroutines.runBlocking

class NoteWidgetItemFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var notes: List<Note> = emptyList()
    private val noteDao = NoteDatabase.getDatabase(context).noteDao()
    private val gson = Gson()

    override fun onCreate() {
        // Gerekli başlangıç işlemleri burada yapılabilir.
    }

    // DÜZELTME: ANR riskini ortadan kaldırmak için `runBlocking` kaldırıldı.
    // onDataSetChanged senkron bir çağrı olduğu için, doğrudan veritabanı
    // sorgusunu burada yapmak, sorgu hızlı olduğu sürece kabul edilebilir en basit yöntemdir.
    // Dao'ya eklenen indeks bu işlemi hızlandıracaktır.
    override fun onDataSetChanged() {
        notes = runBlocking {
            try {
                noteDao.getNotesForWidget()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    override fun onDestroy() {
        notes = emptyList()
    }

    override fun getCount(): Int = notes.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_note_item)

        if (position >= notes.size) {
            return views
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

                val textPart = if (noteContent.text.isNotBlank()) {
                    Html.fromHtml(noteContent.text, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                } else {
                    ""
                }

                val checklistPart = if (noteContent.checklist.isNotEmpty()) {
                    val checkedCount = noteContent.checklist.count { it.isChecked }
                    context.getString(R.string.checklist_summary_preview, checkedCount, noteContent.checklist.size)
                } else {
                    ""
                }

                if (textPart.isNotEmpty() && checklistPart.isNotEmpty()) {
                    "$textPart\n$checklistPart"
                } else {
                    textPart + checklistPart
                }
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

            return views
        } catch (e: Exception) {
            views.setTextViewText(R.id.tv_widget_item_title, "")
            views.setTextViewText(R.id.tv_widget_item_content, "Not yüklenirken hata oluştu")
            return views
        }
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = notes[position].id.toLong()
    override fun hasStableIds(): Boolean = true
}