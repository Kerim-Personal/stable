package com.codenzi.snapnote

import android.app.Application
import androidx.work.*
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // KALDIRILDI: Sürüm kontrolü ve veri sıfırlama mekanizması kaldırıldı.
        // checkVersionAndResetData()

        // PasswordManager'ı uygulama başlatılırken YALNIZCA BİR KEZ başlat.
        PasswordManager.initialize(applicationContext)

        // Uygulama her açıldığında, önceki oturumlardan kalmış
        // olabilecek sahipsiz geçici resim dosyalarını temizle.
        ImageManager.cleanUpTemporaryFiles(applicationContext)

        scheduleTrashCleanup()
    }

    private fun scheduleTrashCleanup() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresCharging(false)
            .build()

        val repeatingRequest = PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "trashCleanupWork",
            ExistingPeriodicWorkPolicy.KEEP, // Mevcut bir iş varsa devam ettir
            repeatingRequest
        )
    }
}