package com.codenzi.snapnote

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class TrashCleanupWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val noteDao = NoteDatabase.getDatabase(applicationContext).noteDao()
            // 30 günün milisaniye karşılığını hesapla
            val thirtyDaysInMillis = TimeUnit.DAYS.toMillis(30)
            // Şu anki zamandan 30 gün öncesinin zaman damgasını bul
            val thirtyDaysAgoTimestamp = System.currentTimeMillis() - thirtyDaysInMillis

            // DAO üzerinden eski notları silme işlemini çağır
            noteDao.deleteOldTrashedNotes(thirtyDaysAgoTimestamp)

            Result.success()
        } catch (e: Exception) {
            // Bir hata olursa görevi başarısız olarak işaretle
            Result.failure()
        }
    }
}