package com.codenzi.snapnote

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepository @Inject constructor(private val noteDao: NoteDao) {

    suspend fun getNoteById(noteId: Int): Note? = noteDao.getNoteById(noteId)

    suspend fun insert(note: Note): Long = noteDao.insert(note)

    suspend fun update(note: Note) = noteDao.update(note)

    suspend fun softDeleteById(noteId: Int, timestamp: Long) = noteDao.softDeleteById(noteId, timestamp)

    // İleride ihtiyaç duyabileceğiniz diğer Dao fonksiyonlarını buraya ekleyebilirsiniz.
}