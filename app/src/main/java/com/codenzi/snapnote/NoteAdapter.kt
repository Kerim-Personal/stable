package com.codenzi.snapnote

import android.graphics.Color
import android.net.Uri
import android.text.Html
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.card.MaterialCardView
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File

class NoteAdapter(
    private var notes: List<Note>,
    private val clickListener: (Note) -> Unit,
    private val longClickListener: (Note) -> Unit
) : RecyclerView.Adapter<NoteAdapter.NoteViewHolder>() {

    private val selectedItems = mutableSetOf<Int>()
    private val gson = Gson()

    fun toggleSelection(noteId: Int) {
        val index = notes.indexOfFirst { it.id == noteId }
        if (index == -1) return

        if (selectedItems.contains(noteId)) {
            selectedItems.remove(noteId)
        } else {
            selectedItems.add(noteId)
        }
        notifyItemChanged(index)
    }

    fun getSelectedNotes(): List<Note> {
        return notes.filter { selectedItems.contains(it.id) }
    }

    fun clearSelections() {
        val previouslySelectedIndices = selectedItems.mapNotNull { selectedId ->
            notes.indexOfFirst { it.id == selectedId }.takeIf { it != -1 }
        }
        selectedItems.clear()
        previouslySelectedIndices.forEach { notifyItemChanged(it) }
    }

    fun getSelectedItemCount(): Int = selectedItems.size

    fun selectAll() {
        if (notes.isEmpty()) return
        val allNoteIds = notes.map { it.id }
        selectedItems.clear()
        selectedItems.addAll(allNoteIds)
        notifyItemRangeChanged(0, notes.size)
    }

    inner class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val noteTitle: TextView = itemView.findViewById(R.id.tv_note_title)
        private val noteContent: TextView = itemView.findViewById(R.id.tv_note_content)
        private val cardContainer: MaterialCardView = itemView.findViewById(R.id.note_card_container)
        private val pinnedIcon: ImageView = itemView.findViewById(R.id.iv_pinned_icon)
        private val noteImage: ImageView = itemView.findViewById(R.id.iv_note_item_image)
        private val audioPlayerPreview: LinearLayout = itemView.findViewById(R.id.ll_audio_player_preview)
        private val audioTitlePreview: TextView = itemView.findViewById(R.id.tv_audio_title_preview)

        fun bind(note: Note) {
            noteTitle.isVisible = note.title.isNotBlank()
            noteTitle.text = note.title

            pinnedIcon.isVisible = note.showOnWidget

            try {
                val content = gson.fromJson(note.content, NoteContent::class.java)
                val textPreview: Spanned = Html.fromHtml(content.text, Html.FROM_HTML_MODE_LEGACY)

                val hasText = textPreview.isNotBlank()
                val hasChecklist = content.checklist.isNotEmpty()

                noteContent.isVisible = hasText || hasChecklist

                if (hasText) {
                    noteContent.text = textPreview
                } else {
                    noteContent.text = ""
                }

                if (hasChecklist) {
                    val checklistSummary = StringBuilder()
                    if (hasText) {
                        checklistSummary.append("\n\n")
                    }
                    val checkedCount = content.checklist.count { it.isChecked }
                    checklistSummary.append(itemView.context.getString(R.string.checklist_summary_preview, checkedCount, content.checklist.size))
                    noteContent.append(checklistSummary.toString())
                }

                noteImage.isVisible = content.imagePath != null
                if (content.imagePath != null) {
                    val path = content.imagePath
                    val dataToLoad: Any = if (path.startsWith("content://")) Uri.parse(path) else File(path)
                    noteImage.load(dataToLoad) {
                        crossfade(true)
                        placeholder(R.drawable.ic_image_24)
                        error(R.drawable.ic_image_24)
                    }
                }

                audioPlayerPreview.isVisible = content.audioFilePath != null
                if (content.audioFilePath != null) {
                    audioTitlePreview.text = note.title.ifBlank { itemView.context.getString(R.string.voice_recording_title) }
                }

            } catch (e: JsonSyntaxException) {
                val preview: Spanned = Html.fromHtml(note.content, Html.FROM_HTML_MODE_LEGACY)
                noteContent.text = preview
                noteContent.isVisible = preview.isNotBlank()
                noteImage.visibility = View.GONE
                audioPlayerPreview.visibility = View.GONE
            }

            if (selectedItems.contains(note.id)) {
                cardContainer.strokeWidth = 8
                cardContainer.strokeColor = ContextCompat.getColor(cardContainer.context, android.R.color.holo_blue_dark)
            } else {
                cardContainer.strokeWidth = 0
            }

            try {
                cardContainer.setCardBackgroundColor(note.color.toColorInt())
            } catch (e: Exception) {
                cardContainer.setCardBackgroundColor(Color.WHITE)
            }

            itemView.setOnClickListener { clickListener(note) }
            itemView.setOnLongClickListener {
                longClickListener(note)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.note_item, parent, false)
        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(notes[position])
    }

    override fun getItemCount() = notes.size

    fun updateNotes(newNotes: List<Note>) {
        val diffCallback = NoteDiffCallback(this.notes, newNotes)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        this.notes = newNotes
        diffResult.dispatchUpdatesTo(this)
    }
}

class NoteDiffCallback(
    private val oldList: List<Note>,
    private val newList: List<Note>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size
    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].id == newList[newItemPosition].id
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition] == newList[newItemPosition]
    }
}