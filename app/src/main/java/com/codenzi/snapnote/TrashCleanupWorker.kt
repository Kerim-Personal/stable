package com.codenzi.snapnote

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.TimeUnit

class TrashCleanupWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val noteDao = NoteDatabase.getDatabase(applicationContext).noteDao()
    private val gson = Gson()

    override suspend fun doWork(): Result {
        return try {
            val thirtyDaysInMillis = TimeUnit.DAYS.toMillis(30)
            val thirtyDaysAgoTimestamp = System.currentTimeMillis() - thirtyDaysInMillis

            // Silinecek eski notları veritabanından al
            val oldNotes = noteDao.getOldTrashedNotes(thirtyDaysAgoTimestamp)

            if (oldNotes.isNotEmpty()) {
                // Her not için ilişkili dosyaları sil
                for (note in oldNotes) {
                    try {
                        val content = gson.fromJson(note.content, NoteContent::class.java)
                        content.imagePath?.let { path -> deleteFileFromPath(path) }
                        content.audioFilePath?.let { path -> deleteFileFromPath(path) }
                    } catch (e: Exception) {
                        Log.e("TrashCleanupWorker", "Note ${note.id} işlenirken hata oluştu.", e)
                    }
                }
                // Dosyalar silindikten sonra notları veritabanından sil
                noteDao.hardDeleteByIds(oldNotes.map { it.id })
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("TrashCleanupWorker", "Çöp temizleme işlemi başarısız oldu.", e)
            Result.failure()
        }
    }

    private fun deleteFileFromPath(path: String) {
        try {
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                applicationContext.contentResolver.delete(uri, null, null)
            } else {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("TrashCleanupWorker", "Dosya silinemedi: $path", e)
        }
    }
}