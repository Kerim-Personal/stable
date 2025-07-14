package com.codenzi.snapnote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CameraNoteViewModel @Inject constructor(
    private val repository: NoteRepository
) : ViewModel() {

    fun savePhotoNote(imagePath: String, titleTemplate: String) {
        viewModelScope.launch {
            val title = "$titleTemplate - ${
                SimpleDateFormat(
                    "dd/MM/yyyy HH:mm",
                    Locale.getDefault()
                ).format(System.currentTimeMillis())
            }"
            val contentJson = Gson().toJson(
                NoteContent(
                    text = "",
                    checklist = mutableListOf(),
                    imagePath = imagePath
                )
            )
            val newNote = Note(
                title = title,
                content = contentJson,
                createdAt = System.currentTimeMillis()
            )
            repository.insert(newNote)
        }
    }
}