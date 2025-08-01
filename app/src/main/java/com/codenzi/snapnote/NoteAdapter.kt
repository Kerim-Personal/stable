package com.codenzi.snapnote

import android.graphics.Color
import android.net.Uri
import android.text.Html
import android.text.Spanned
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.card.MaterialCardView
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File // Bu satır artık resim yükleme için gerekli değil ama URI'ye çevrilmeyen eski yollar için kalabilir.

class NoteAdapter(
    private val clickListener: (Note) -> Unit,
    private val longClickListener: (Note) -> Boolean
) : ListAdapter<Note, NoteAdapter.NoteViewHolder>(NoteDiffCallback()) {

    private val selectedItems = mutableSetOf<Int>()
    private val gson = Gson()

    fun toggleSelection(noteId: Int) {
        val index = currentList.indexOfFirst { it.id == noteId }
        if (index == -1) return

        if (selectedItems.contains(noteId)) {
            selectedItems.remove(noteId)
        } else {
            selectedItems.add(noteId)
        }
        notifyItemChanged(index)
    }

    fun getSelectedNotes(): List<Note> {
        return currentList.filter { selectedItems.contains(it.id) }
    }

    fun clearSelections() {
        val previouslySelectedIndices = selectedItems.mapNotNull { selectedId ->
            currentList.indexOfFirst { it.id == selectedId }.takeIf { it != -1 }
        }
        selectedItems.clear()
        previouslySelectedIndices.forEach { notifyItemChanged(it) }
    }

    fun getSelectedItemCount(): Int = selectedItems.size

    fun selectAll() {
        if (currentList.isEmpty()) return
        val allNoteIds = currentList.map { it.id }
        selectedItems.clear()
        selectedItems.addAll(allNoteIds)
        notifyItemRangeChanged(0, currentList.size)
    }

    inner class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val noteTitle: TextView = itemView.findViewById(R.id.tv_note_title)
        private val noteContent: TextView = itemView.findViewById(R.id.tv_note_content)
        private val cardContainer: MaterialCardView = itemView.findViewById(R.id.note_card_container)
        private val pinnedIcon: ImageView = itemView.findViewById(R.id.iv_pinned_icon)
        private val noteImage: ImageView = itemView.findViewById(R.id.iv_note_item_image)
        private val audioPlayerPreview: LinearLayout = itemView.findViewById(R.id.ll_audio_player_preview)
        private val audioTitlePreview: TextView = itemView.findViewById(R.id.tv_audio_title_preview)
        private val checklistSummary: TextView = itemView.findViewById(R.id.tv_checklist_summary)

        fun bind(note: Note) {
            val backgroundColor = try {
                note.color.toColorInt()
            } catch (e: Exception) {
                Color.WHITE
            }
            val contrastingTextColor = if (ColorUtils.calculateLuminance(backgroundColor) > 0.5) {
                Color.BLACK
            } else {
                Color.WHITE
            }

            cardContainer.setCardBackgroundColor(backgroundColor)
            noteTitle.setTextColor(contrastingTextColor)
            noteContent.setTextColor(contrastingTextColor)
            checklistSummary.setTextColor(contrastingTextColor)
            audioTitlePreview.setTextColor(contrastingTextColor)
            pinnedIcon.setColorFilter(contrastingTextColor)

            noteTitle.isVisible = note.title.isNotBlank()
            noteTitle.text = note.title
            pinnedIcon.isVisible = note.showOnWidget

            try {
                val content = gson.fromJson(note.content, NoteContent::class.java)
                val textPreview: Spanned = Html.fromHtml(content.text, Html.FROM_HTML_MODE_LEGACY)

                noteContent.isVisible = textPreview.isNotBlank()
                noteContent.text = textPreview

                checklistSummary.isVisible = content.checklist.isNotEmpty()
                if (content.checklist.isNotEmpty()) {
                    val checkedCount = content.checklist.count { it.isChecked }
                    checklistSummary.text = itemView.context.getString(R.string.checklist_summary_preview, checkedCount, content.checklist.size)
                }

                noteImage.isVisible = content.imagePath != null
                content.imagePath?.let { path ->
                    // =============================================================
                    // *** İŞTE YAPILAN KRİTİK DEĞİŞİKLİK BURASI ***
                    // Eski hatalı kod kaldırıldı. Artık gelen 'path' string'i
                    // doğrudan Uri nesnesine çevriliyor. Coil kütüphanesi
                    // hem "file://" hem de "content://" şemalarını doğru işler.
                    val imageUri = Uri.parse(path)
                    noteImage.load(imageUri) { // 'dataToLoad' yerine 'imageUri' kullanılıyor
                        crossfade(true)
                        placeholder(R.drawable.ic_image_24)
                        error(R.drawable.ic_image_24)
                    }
                    // =============================================================
                }

                audioPlayerPreview.isVisible = content.audioFilePath != null
                content.audioFilePath?.let {
                    audioTitlePreview.text = note.title.ifBlank { itemView.context.getString(R.string.voice_recording_title) }
                }

            } catch (e: JsonSyntaxException) {
                val preview: Spanned = Html.fromHtml(note.content, Html.FROM_HTML_MODE_LEGACY)
                noteContent.text = preview
                noteContent.isVisible = preview.isNotBlank()
                noteImage.visibility = View.GONE
                audioPlayerPreview.visibility = View.GONE
                checklistSummary.visibility = View.GONE
            }

            if (selectedItems.contains(note.id)) {
                cardContainer.strokeWidth = 8
                val typedValue = TypedValue()
                cardContainer.context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
                cardContainer.strokeColor = typedValue.data
            } else {
                cardContainer.strokeWidth = 0
            }

            itemView.setOnClickListener { clickListener(note) }
            itemView.setOnLongClickListener { longClickListener(note) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.note_item, parent, false)
        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

class NoteDiffCallback : DiffUtil.ItemCallback<Note>() {
    override fun areItemsTheSame(oldItem: Note, newItem: Note): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Note, newItem: Note): Boolean {
        return oldItem == newItem
    }
}