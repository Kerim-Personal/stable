package com.codenzi.snapnote

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.*
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Sürüm kontrolü ve veri sıfırlama
        checkVersionAndResetData()

        // PasswordManager'ı uygulama başlatılırken YALNIZCA BİR KEZ başlat.
        PasswordManager.initialize(applicationContext)

        // --- DÜZELTME BAŞLANGICI ---
        // Uygulama her açıldığında, önceki oturumlardan kalmış
        // olabilecek sahipsiz geçici resim dosyalarını temizle.
        ImageManager.cleanUpTemporaryFiles(applicationContext)
        // --- DÜZELTME SONU ---

        scheduleTrashCleanup()
    }

    private fun checkVersionAndResetData() {
        val prefs = getSharedPreferences("app_version_prefs", Context.MODE_PRIVATE)
        val savedVersionCode = prefs.getInt("version_code", -1)

        try {
            val currentVersionCode = packageManager.getPackageInfo(packageName, 0).versionCode

            // build.gradle.kts dosyasındaki versionCode'a göre kontrol yapılır
            if (savedVersionCode != -1 && currentVersionCode > savedVersionCode) {
                // Yeni bir sürüm yüklendi, verileri temizle
                DataWipeManager.wipeAllData(this) //
            }

            // Yeni sürüm kodunu kaydet
            prefs.edit().putInt("version_code", currentVersionCode).apply()

        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
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
            ExistingPeriodicWorkPolicy.KEEP,
            repeatingRequest
        )
    }
}