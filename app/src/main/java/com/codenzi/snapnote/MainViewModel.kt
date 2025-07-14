package com.codenzi.snapnote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel // Bu satırı ekleyin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject // Bu satırı ekleyin

@HiltViewModel // ViewModel'in Hilt tarafından yönetileceğini belirtir
class MainViewModel @Inject constructor(private val noteDao: NoteDao) : ViewModel() {

    enum class SortOrder {
        CREATION_NEWEST, CREATION_OLDEST, CONTENT_AZ
    }

    // Arama sorgusunu tutan StateFlow
    private val _searchQuery = MutableStateFlow("")

    // Sıralama düzenini tutan StateFlow
    private val _sortOrder = MutableStateFlow(SortOrder.CREATION_NEWEST)
    val sortOrder = _sortOrder.asStateFlow()

    // Tüm notları, arama ve sıralama filtrelerini birleştiren ana StateFlow
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes = _notes.asStateFlow()

    init {
        // Notları, arama sorgusunu ve sıralama düzenini dinle
        viewModelScope.launch {
            combine(noteDao.getAllNotes(), _searchQuery, _sortOrder) { allNotes, query, sort ->
                // Arama filtresini uygula
                val filteredList = if (query.isBlank()) {
                    allNotes
                } else {
                    val lowercasedQuery = query.lowercase(Locale.getDefault())
                    allNotes.filter {
                        it.title.lowercase(Locale.getDefault()).contains(lowercasedQuery) ||
                                it.content.lowercase(Locale.getDefault()).contains(lowercasedQuery)
                    }
                }

                // Sıralama filtresini uygula
                when (sort) {
                    SortOrder.CREATION_NEWEST -> filteredList.sortedByDescending { it.createdAt }
                    SortOrder.CREATION_OLDEST -> filteredList.sortedBy { it.createdAt }
                    SortOrder.CONTENT_AZ -> filteredList.sortedBy { it.content.lowercase(Locale.getDefault()) }
                }
            }.collect { filteredAndSortedNotes ->
                _notes.value = filteredAndSortedNotes
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(sortOrder: SortOrder) {
        _sortOrder.value = sortOrder
    }

    fun moveNotesToTrash(notes: List<Note>) = viewModelScope.launch {
        notes.forEach { noteDao.softDeleteById(it.id, System.currentTimeMillis()) }
    }

    fun setPinnedStatus(notes: List<Note>, isPinned: Boolean) = viewModelScope.launch {
        val noteIds = notes.map { it.id }
        noteDao.setPinnedStatus(noteIds, isPinned)
    }
}