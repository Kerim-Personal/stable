package com.codenzi.snapnote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NoteViewModel @Inject constructor(
    private val repository: NoteRepository
) : ViewModel() {

    private val _note = MutableStateFlow<Note?>(null)
    val note = _note.asStateFlow()

    private val _isFinished = MutableStateFlow(false)
    val isFinished = _isFinished.asStateFlow()

    private val _noteMovedToTrash = MutableStateFlow(false)
    val noteMovedToTrash = _noteMovedToTrash.asStateFlow()

    private val gson = Gson()

    fun loadNote(noteId: Int) {
        if (noteId == 0) return
        viewModelScope.launch {
            _note.value = repository.getNoteById(noteId)
        }
    }

    fun saveOrUpdateNote(
        currentNoteId: Int?,
        title: String,
        contentHtml: String,
        checklistItems: List<ChecklistItem>,
        color: String,
        audioPath: String?,
        imagePath: String?,
        isFromWidget: Boolean
    ) {
        viewModelScope.launch {
            val jsonContent = gson.toJson(NoteContent(text = contentHtml, checklist = checklistItems.toMutableList(), audioFilePath = audioPath, imagePath = imagePath))

            if (currentNoteId != null && currentNoteId != 0) {
                _note.value?.let { existingNote ->
                    val updatedModifications = existingNote.modifiedAt.toMutableList().apply { add(System.currentTimeMillis()) }
                    val updatedNote = existingNote.copy(
                        title = title,
                        content = jsonContent,
                        modifiedAt = updatedModifications,
                        color = color
                    )
                    repository.update(updatedNote)
                }
            } else {
                val newNote = Note(
                    title = title,
                    content = jsonContent,
                    createdAt = System.currentTimeMillis(),
                    color = color,
                    showOnWidget = isFromWidget
                )
                repository.insert(newNote)
            }
            _isFinished.value = true
        }
    }

    fun moveNoteToTrash(noteId: Int?) {
        if (noteId == null || noteId == 0) return
        viewModelScope.launch {
            repository.softDeleteById(noteId, System.currentTimeMillis())
            _noteMovedToTrash.value = true
        }
    }
}